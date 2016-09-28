(ns oc.auth.ring
  (:require [cheshire.core :as json]))

(def json-mime-type "application/json")
(def html-mime-type "text/html")
(def text-mime-type "text/plain")

(defn ring-response
  "Helper to format a generic JSON body ring response"
  [body status mime-type]
  {:body body
   :status status
   :headers {"Content-Type" mime-type}})

(defn json-response
  "Helper to format a generic JSON body ring response"
  [body status]
  (ring-response (json/generate-string body {:pretty true}) status json-mime-type))

(defn html-response
  "Helper to format a generic JSON body ring response"
  [body status]
  (ring-response body status html-mime-type))

(defn text-response
  "Helper to format a generic JSON body ring response"
  [body status]
  (ring-response body status text-mime-type))

(defn error-response
  "Helper to format a JSON ring response with an error and :ok false"
  [error status]
  (json-response {:ok false :error error} status))