(ns open-company-auth.jwt
  (:require [clj-jwt.core  :refer :all]
            [clj-jwt.key   :refer [private-key public-key]]
            [open-company-auth.config :as config]
            [clojure.java.io :as io]))

(defn generate 
  "Get a JWT token from a payload"
  [payload]
  (-> payload
      jwt
      (sign :HS256 config/passphrase)
      to-str))

(defn check-token
  "Verify a JWT token"
  [token]
  (try
    (do
      (-> token
        str->jwt
        (verify config/passphrase))
      true)
    (catch Exception e 
      false)))

(defn decode [token]
  (-> token
      str->jwt))