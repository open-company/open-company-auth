(ns oc.auth.lib.sqs
  (:require [clojure.string :as s]
            [amazonica.aws.sqs :as sqs]
            [taoensso.timbre :as timbre]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.lib.text :as str]
            [oc.auth.config :as config]))

;; SQS message types
(def invite "invite")
(def reset "reset")
(def verify "verify")
(def welcome "welcome")
(def bot-removed "bot-removed")

;; Bot receiver type
(def receiver :user)

;; ----- Utility Functions -----

(defn- token-link [token-type token]
  (s/join "/" [config/ui-server-url (str (name token-type) "?token=" token)]))

;; ----- SQS Message Schemas -----

(def EmailInvite
  {
    :type (schema/enum invite)
    :from schema/Str ; inviter's name
    :from-avatar schema/Str ; inviter's avatar Url
    :reply-to schema/Str ; inviter's email address
    :to lib-schema/EmailAddress ; invitee's email address
    :first-name schema/Str ; invitee's first name
    :org-name schema/Str
    :org-logo-url schema/Str
    :org-logo-width schema/Int
    :org-logo-height schema/Int
    :token-link lib-schema/NonBlankStr
    :note schema/Str
  })

(def EmailBotRemoved
  {
    :type (schema/enum "bot-removed")
    :reply-to schema/Str ; inviter's email address
    :to [lib-schema/EmailAddress] ; invitee's email address
    :org-name schema/Str
    :org-slug lib-schema/NonBlankStr
    :org-logo-url schema/Str
    :org-logo-width schema/Int
    :org-logo-height schema/Int
  })

(def TokenAuth
  {
    :type (schema/enum reset verify)
    :to lib-schema/EmailAddress
    :token-link lib-schema/NonBlankStr
   })

(def BotTrigger 
  "All Slack bot triggers have the following properties."
  {
    :type (schema/enum invite welcome)
    :bot {
       :token lib-schema/NonBlankStr
       :id lib-schema/NonBlankStr
    }
    :receiver {
      :type (schema/enum :all-members :user :channel)
      :slack-org-id lib-schema/NonBlankStr
      (schema/optional-key :id) schema/Str
  }})

(def SlackInvite
  "A Slack bot trigger to invite a user."
  (merge BotTrigger {
    :from schema/Str ; inviter's name
    :from-id (schema/maybe schema/Str) ; inviter's Slack ID
    :first-name schema/Str ; invitee's first name
    :org-name schema/Str
    :url lib-schema/NonBlankStr
    :note schema/Str
    :org-logo-url schema/Str
    :org-logo-width schema/Int
    :org-logo-height schema/Int
  }))

;; ----- SQS Message Creation -----

(schema/defn ^:always-validate ->email-invite :- EmailInvite
  [payload from :- (schema/maybe schema/Str) from-avatar :- (schema/maybe schema/Str)
   reply-to :- (schema/maybe lib-schema/EmailAddress)]
  {:pre [(map? payload)
         (lib-schema/valid-email-address? (:email payload))
         (lib-schema/uuid-string? (:token payload))]}
  {
    :type (name invite)
    :to (:email payload)
    :from (or from "")
    :from-avatar (or from-avatar "")
    :note (or (str/strip-xss-tags (:note payload)) "")
    :reply-to (or reply-to "")
    :first-name (or (:first-name payload) "")
    :org-name (or (:org-name payload) "")
    :org-logo-url (or (:logo-url payload) "")
    :org-logo-width (or (:logo-width payload) 0)
    :org-logo-height (or (:logo-height payload) 0)
    :token-link (token-link invite (:token payload))
  })

(schema/defn ^:always-validate ->email-bot-removed :- EmailBotRemoved
  [org to :- [lib-schema/EmailAddress]]
  {:pre [(map? org)]}
  {
    :type (name bot-removed)
    :to to
    :org-name (or (:name org) "")
    :org-slug (or (:slug org) "")
    :org-logo-url (or (:logo-url org) "")
    :org-logo-width (or (:logo-width org) 0)
    :org-logo-height (or (:logo-height org) 0)
  })

(schema/defn ^:always-validate ->slack-invite :- SlackInvite
  [payload from :- (schema/maybe schema/Str)]
  {:pre [(map? payload)
         (schema/validate lib-schema/NonBlankStr (:slack-id payload))
         (schema/validate lib-schema/NonBlankStr (:slack-org-id payload))
         (schema/validate lib-schema/NonBlankStr (:bot-token payload))
         (schema/validate lib-schema/NonBlankStr (:bot-user-id payload))]}
  {
    :type invite
    :from (or from "")
    :from-id (:from-id payload)
    :org-name (or (:org-name payload) "")
    :org-logo-url (or (:logo-url payload) "")
    :org-logo-width (or (:logo-width payload) 0)
    :org-logo-height (or (:logo-height payload) 0)
    :first-name (or (:first-name payload) "")
    :note (or (str/strip-xss-tags (:note payload)) "")
    :url (str config/ui-server-url "/sign-up/slack")
    :receiver {:slack-org-id (:slack-org-id payload)
               :type receiver
               :id (:slack-id payload)}
    :bot {:token (:bot-token payload)
          :id (:bot-user-id payload)}
  })

(schema/defn ^:always-validate ->slack-welcome :- BotTrigger
  [payload]
  {:pre [(map? payload)
         (schema/validate lib-schema/NonBlankStr (:id payload))
         (schema/validate lib-schema/NonBlankStr (:slack-org-id payload))
         (schema/validate lib-schema/NonBlankStr (:token payload))
         (schema/validate lib-schema/NonBlankStr (:bot-user-id payload))]}
  {
    :type welcome
    :receiver {:slack-org-id (:slack-org-id payload)
               :type :user
               :id (:id payload)}
    :bot {:token (:token payload)
          :id (:bot-user-id payload)}
  })

(defn ->token-auth [payload]
  {:pre [(map? payload)
         (lib-schema/valid-email-address? (:email payload))
         (lib-schema/uuid-string? (:token payload))]}
  {
    :type (name (:type payload))
    :to (:email payload)
    :token-link (token-link (:type payload) (:token payload))
  })

;; ----- SQS Message Functions -----

(defn send!
  ([msg-schema msg sqs-queue] (send! msg-schema msg sqs-queue 0))
  ([msg-schema msg sqs-queue seconds-delay]
  (let [type (or (:type msg) (-> msg :script :id))
        to (or (:to msg) (-> msg :receiver :id))]
    (timbre/info "Request to send" type "to:" to)
    (schema/validate msg-schema msg)
    (timbre/info "Sending" type "to:" to)
    (sqs/send-message
      {:access-key config/aws-access-key-id
       :secret-key config/aws-secret-access-key}
      :queue-url sqs-queue
      :message-body msg
      :delay-seconds seconds-delay)
    (timbre/info "Sent" type "to:" to "with delay" seconds-delay))))