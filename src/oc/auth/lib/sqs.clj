(ns oc.auth.lib.sqs
  (:require [schema.core :as schema]
            [amazonica.aws.sqs :as sqs]
            [taoensso.timbre :as timbre]
            [oc.auth.config :as c]))

;; ----- SQS Message Schemas -----

(def EmailInvite
  {:type (schema/pred #(= "invite" %))
   :from schema/Str
   :reply-to schema/Str
   :to schema/Str
   :company-name schema/Str
   :logo schema/Str
   :token-link schema/Str})

(def PasswordReset
  {:type (schema/pred #(= "reset" %))
   :to schema/Str
   :token-link schema/Str})

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
    :company-name (or (:company-name payload) "")
    :logo (or (:logo payload) "")
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
  (timbre/info "Request to send" (:type msg) "to" (:to msg))
  (schema/validate type msg)
  (timbre/info "Sending...")
  (sqs/send-message
    {:access-key c/aws-access-key-id
     :secret-key c/aws-secret-access-key}
    c/aws-sqs-email-queue
    msg)
  (timbre/info "Sent"))