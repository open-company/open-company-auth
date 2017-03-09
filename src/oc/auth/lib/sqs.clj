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
   :from schema/Str
   :reply-to schema/Str
   :to lib-schema/EmailAddress
   :first-name schema/Str
   :org-name schema/Str
   :logo-url schema/Str
   :token-link lib-schema/NonBlankStr})

(def TokenAuth
  {:type (schema/enum reset verify)
   :to lib-schema/EmailAddress
   :token-link lib-schema/NonBlankStr})

;; ----- SQS Message Creation -----

(schema/defn ^:always-validate ->invite [payload from :- (schema/maybe lib-schema/EmailAddress)
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
  [type msg]
  (timbre/info "Request to send:" (:type msg) "to:" (:to msg))
  (schema/validate type msg)
  (timbre/info "Sending:" (:type msg) "to:" (:to msg))
  (sqs/send-message
    {:access-key config/aws-access-key-id
     :secret-key config/aws-secret-access-key}
    config/aws-sqs-email-queue
    msg)
  (timbre/info "Sent:" (:type msg) "to:" (:to msg)))