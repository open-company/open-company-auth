(ns oc.auth.lib.recipient-validation
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [oc.auth.config :as config]
            [taoensso.timbre :as timbre]))

(def spark-post-endpoint "https://api.sparkpost.com")

(def discard-results #{"risky" "undeliverable"})

(defn validate! [email-address]
  (timbre/debugf "Will validate email address: %s" email-address)
  (if-not config/spark-post-api-key
    (timbre/error "No API key available for SparkPost, can not validate recipient.")
    (let [validation-resp (http/get (format "%s/api/v1/recipient-validation/single/%s" spark-post-endpoint email-address)
                                    {:headers {"Authorization" config/spark-post-api-key}})
          parsed-body (-> validation-resp
                          :body
                          (json/parse-string true))]
      (timbre/debugf "Validation response: %s" parsed-body)
      (timbre/infof "Recipient result: %s discard? %s" (-> parsed-body :results :result) (-> parsed-body :results :result discard-results))
      (-> parsed-body
          :results
          :result
          (discard-results)
          not))))