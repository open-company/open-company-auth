(ns oc.auth.lib.sqs
  (:require [clojure.string :as s]
            [amazonica.aws.sqs :as sqs]
            [taoensso.timbre :as timbre]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.auth.config :as config]))

;; SQS message types
(def invite "invite")
(def reset "reset")
(def verify "verify")

;; ----- Utility Functions -----

(defn- token-link [token-type token]
  (s/join "/" [config/ui-server-url (str (name token-type) "?token=" token)]))

;; ----- SQS Message Schemas -----

(def EmailInvite
  {:type (schema/pred #(= invite %))
   :from schema/Str ; inviter's name
   :reply-to schema/Str ; inviter's email address
   :to lib-schema/EmailAddress ; invitee's email address
   :first-name schema/Str ; invitee's first name
   :org-name schema/Str
   :logo-url schema/Str
   :token-link lib-schema/NonBlankStr})

(def TokenAuth
  {:type (schema/enum reset verify)
   :to lib-schema/EmailAddress
   :token-link lib-schema/NonBlankStr})

(def SlackInvite
  {:type (schema/pred #(= invite %))
   :from schema/Str ; inviter's name
   :to lib-schema/NonBlankStr ; invitee's slack-id
   :first-name schema/Str ; invitee's first name
   :org-name schema/Str
   :slack-org-id lib-schema/NonBlankStr
   :bot-token lib-schema/NonBlankStr})

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
    :type invite
    :to (:slack-id payload)
    :from (or from "")
    :first-name (or (:first-name payload) "")
    :org-name (or (:org-name payload) "")
    :slack-org-id (:slack-org-id payload)
    :bot-token (:bot-token payload)
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
  [type msg sqs-queue]
  (timbre/info "Request to send" (:type msg) "to:" (:to msg))
  (schema/validate type msg)
  (timbre/info "Sending" (:type msg) "to:" (:to msg))
  (sqs/send-message
    {:access-key config/aws-access-key-id
     :secret-key config/aws-secret-access-key}
    sqs-queue
    msg)
  (timbre/info "Sent" (:type msg) "to:" (:to msg)))