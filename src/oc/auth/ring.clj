(ns oc.auth.ring
  (:require [defun :refer (defun)]
            [cheshire.core :as json]))

(def json-mime-type "application/json")
(def html-mime-type "text/html")
(def text-mime-type "text/plain")

(defn ring-response
  "Helper to format a generic ring response"
  [body status headers]
  {:body body
   :status status
   :headers headers})

(defun json-response
  "Helper to format a generic JSON body ring response"
  ([body status] (json-response body status json-mime-type {}))
  
  ([body status headers :guard map?] (json-response body status json-mime-type headers))

  ([body status mime-type :guard string?] (json-response body status mime-type {}))

  ([body status mime-type headers]
  {:pre [(map? body)
         (integer? status)
         (string? mime-type)
         (map? headers)]}
  (ring-response (json/generate-string body {:pretty true}) status (merge {"Content-Type" mime-type} headers))))

(defn html-response
  "Helper to format a generic JSON body ring response"
  ([body status] (html-response body status {}))
  
  ([body status headers]
  {:pre [(string? body)
         (integer? status)
         (map? headers)]}
  (ring-response body status (merge {"Content-Type" html-mime-type} headers))))

(defn text-response
  "Helper to format a generic JSON body ring response"
  ([body status] (text-response body status {}))
  
  ([body status headers]
  {:pre [(string? body)
         (integer? status)
         (map? headers)]}
  (ring-response body status (merge {"Content-Type" text-mime-type} headers))))

(defn error-response
  "Helper to format a JSON ring response with an error and :ok false"
  ([error status] (error-response error status {}))
  
  ([error status headers]
  {:pre [(integer? status)
         (map? headers)]}
  (ring-response error status headers)))