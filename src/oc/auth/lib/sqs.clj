(ns oc.auth.lib.sqs
  (:require [schema.core :as schema]
            [amazonica.aws.sqs :as sqs]
            [taoensso.timbre :as timbre]
            [oc.auth.config :as c]))

(def EmailInvite
  {:type schema/Keyword
   :email schema/Str
   :company-name schema/Str
   :logo schema/Str
   :token-link schema/Str})

(defn ->invite [payload]
  {:pre [(map? payload)
         (string? (:email payload))
         (string? (:token-link payload))]}
  (merge (select-keys payload [:email :token-link])
    {:type  :invite
     :company-name (or (:company-name payload) "")
     :logo         (or (:logo payload) "")}))

(defn send-invite!
  [invite]
  (timbre/info "Request to send invite to" (:email invite))
  (schema/validate EmailInvite invite)
  (timbre/info "Sending...")
  (sqs/send-message
    {:access-key c/aws-access-key-id
     :secret-key c/aws-secret-access-key}
    c/aws-sqs-email-queue
    invite)
  (timbre/info "Sent"))