(ns oc.auth.ring
  (:require [cheshire.core :as json]))

(def json-mime-type "application/json")
(def html-mime-type "text/html")
(def text-mime-type "text/plain")

(defn ring-response
  "Helper to format a generic JSON body ring response"
  [body status headers]
  {:body body
   :status status
   :headers headers})

(defn json-response
  "Helper to format a generic JSON body ring response"
  ([body status] (json-response body status {}))
  
  ([body status headers]
  (ring-response (json/generate-string body {:pretty true}) status (merge {"Content-Type" json-mime-type} headers))))

(defn html-response
  "Helper to format a generic JSON body ring response"
  ([body status] (html-response body status {}))
  
  ([body status headers]
  (ring-response body status (merge {"Content-Type" html-mime-type} headers))))

(defn text-response
  "Helper to format a generic JSON body ring response"
  ([body status] (text-response body status {}))
  
  ([body status headers]
  (ring-response body status (merge {"Content-Type" text-mime-type} headers))))

(defn error-response
  "Helper to format a JSON ring response with an error and :ok false"
  ([error status] (error-response error status {}))
  
  ([error status headers]
  (json-response {:ok false :error error} status headers)))