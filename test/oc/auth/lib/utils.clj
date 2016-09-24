(ns oc.auth.lib.utils
  "Namespace for tests utilities"
  (:require [ring.mock.request :refer (request body content-type header)]
            [oc.auth.app :refer (app)]
            [clojure.string :as s]
            [cheshire.core :as json]))

(defn base-mime-type
  "Base mime type"
  [full-mime-type]
  (first (s/split full-mime-type #";")))

(defn response-mime-type
  "Get a response mime type"
  [response]
  (base-mime-type (get-in response [:headers "Content-Type"])))

(defn- apply-headers
  "Add the map of headers to the ring mock request."
  [request headers]
  (if (= headers {})
    request
    (let [key (first (keys headers))]
      (recur (header request key (get headers key)) (dissoc headers key)))))

(defn api-request
  "Pretends to execute a REST API request using ring mock."
  [method url options]
  (let [initial-request (request method url)
        headers (merge {:Accept-Charset "utf-8"} (:headers options))
        headers-request (apply-headers initial-request headers)
        body-value (:body options)
        body-request (body headers-request (json/generate-string body-value))]
    (app body-request)))