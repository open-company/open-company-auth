(ns oc.auth.lib.sqs
  (:require [clojure.string :as s]
            [amazonica.aws.sqs :as sqs]
            [taoensso.timbre :as timbre]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.auth.config :as config]))

;; SQS message types
(def invite :invite)
(def reset "reset")
(def verify "verify")

;; Bot receiver type
(def receiver :user)

;; ----- Utility Functions -----

(defn- token-link [token-type token]
  (s/join "/" [config/ui-server-url (str (name token-type) "?token=" token)]))

;; ----- SQS Message Schemas -----

(def EmailInvite
  {
    :type (schema/pred #(= invite %))
    :from schema/Str ; inviter's name
    :reply-to schema/Str ; inviter's email address
    :to lib-schema/EmailAddress ; invitee's email address
    :first-name schema/Str ; invitee's first name
    :org-name schema/Str
    :logo-url schema/Str
    :token-link lib-schema/NonBlankStr
  })

(def TokenAuth
  {
    :type (schema/enum reset verify)
    :to lib-schema/EmailAddress
    :token-link lib-schema/NonBlankStr
   })

(def SlackInvite
  {
    :script {
      :id (schema/pred #(= invite %))
      :from schema/Str ; inviter's name
      :first-name schema/Str ; invitee's first name
      :org-name schema/Str
    } 
    :receiver {:id lib-schema/NonBlankStr ; invitee's slack-id 
               :type (schema/pred #(= receiver %))}
    :bot {:token lib-schema/NonBlankStr}
  })

;; ----- SQS Message Creation -----

(schema/defn ^:always-validate ->email-invite [payload from :- (schema/maybe schema/Str)
                                                 reply-to :- (schema/maybe lib-schema/EmailAddress)]
  {:pre [(map? payload)
         (lib-schema/valid-email-address? (:email payload))
         (lib-schema/uuid-string? (:token payload))]}
  {
    :type invite
    :to (:email payload)
    :from (or from "")
    :reply-to (or reply-to "")
    :first-name (or (:first-name payload) "")
    :org-name (or (:org-name payload) "")
    :logo-url (or (:logo-url payload) "")
    :token-link (token-link invite (:token payload))
  })

(schema/defn ^:always-validate ->slack-invite [payload from :- (schema/maybe schema/Str)]
  {:pre [(map? payload)
         (schema/validate lib-schema/NonBlankStr (:slack-id payload))
         (schema/validate lib-schema/NonBlankStr (:slack-org-id payload))
         (schema/validate lib-schema/NonBlankStr (:bot-token payload))]}
  {
    :script {:id invite
             :from (or from "")
             :org-name (or (:org-name payload) "")
             :first-name (or (:first-name payload) "")}
    :receiver {:id (:slack-id payload) :type receiver}
    :bot {:token (:bot-token payload)}
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
  [msg-schema msg sqs-queue]
  (let [type (or (:type msg) (-> msg :script :id))
        to (or (:to msg) (-> msg :receiver :id))]
    (timbre/info "Request to send" type "to:" to)
    (schema/validate msg-schema msg)
    (timbre/info "Sending" type "to:" to)
    (sqs/send-message
      {:access-key config/aws-access-key-id
       :secret-key config/aws-secret-access-key}
      sqs-queue
      msg)
    (timbre/info "Sent" type "to:" to)))