(ns oc.auth.lib.sqs
  (:require [schema.core :as schema]
            [amazonica.aws.sqs :as sqs]
            [taoensso.timbre :as timbre]
            [oc.lib.schema :as lib-schema]
            [oc.auth.config :as config]))

;; ----- SQS Message Schemas -----

(def EmailInvite
  {:type (schema/pred #(= "invite" %))
   :from schema/Str
   :reply-to schema/Str
   :to lib-schema/EmailAddress
   :first-name schema/Str
   :org-name schema/Str
   :logo-url schema/Str
   :token-link lib-schema/NonBlankStr})

(def PasswordReset
  {:type (schema/pred #(= "reset" %))
   :to lib-schema/EmailAddress
   :token-link lib-schema/NonBlankStr})

;; ----- SQS Message Creation -----

(defn ->invite [payload from reply-to]
  {:pre [(map? payload)
         (string? (:email payload))
         (string? (:token-link payload))
         (or (nil? from) (string? from))
         (or (nil? reply-to) (string? reply-to))]}
  {
    :type "invite"
    :to (:email payload)
    :from (or from "")
    :reply-to (or reply-to "")
    :first-name (or (:first-name payload) "")
    :org-name (or (:org-name payload) "")
    :logo-url (or (:logo-url payload) "")
    :token-link (:token-link payload)
  })

(defn ->reset [payload]
  {:pre [(map? payload)
         (string? (:email payload))
         (string? (:token-link payload))]}
  {
    :type "reset"
    :to (:email payload)
    :token-link (:token-link payload)
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