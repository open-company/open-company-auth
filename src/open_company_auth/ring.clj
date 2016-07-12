(ns open-company-auth.ring
  (:require [cheshire.core :as json]))

(def json-mime-type {"Content-Type" "application/json"})
(def html-mime-type {"Content-Type" "text/html"})

(defn json-response
  "Helper to format a generic JSON body ring response"
  [body status]
  {:body (json/generate-string body {:pretty true})
   :status status
   :headers json-mime-type})

(defn error-response
  "Helper to format a JSON ring response with an error and :ok false"
  [error status]
  (json-response {:ok false :error error} status))