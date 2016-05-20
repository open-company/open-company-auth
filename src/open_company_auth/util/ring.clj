(ns open-company-auth.util.ring
  (:require [cheshire.core :as json]))

(def json-mime-type {"Content-Type" "application/json"})
(def html-mime-type {"Content-Type" "text/html"})

(defn ring-response
  "Helper to format a generic ring response"
  [body headers status] {
    :body body
    :headers headers
    :status status})

(defn json-response
  "Helper to format a generic JSON body ring response"
  [body headers status]
  (ring-response (json/generate-string body {:pretty true}) headers status))

(defn error-response
  "Helper to format a JSON ring response with an error and :ok false"
  [error status]
  (json-response {:ok false :error error} json-mime-type status))