(ns open-company-auth.jwt
  (:require [clj-jwt.core :as jwt]
            [clj-time.core :as t]
            [open-company-auth.config :as config]))

(defn expire [payload]
  (let [expire-by (-> (if (:bot payload) 24 2)
                      t/hours t/from-now .getMillis)]
    (assoc payload :expire expire-by)))

(defn generate
  "Get a JSON Web Token from a payload"
  [payload]
  (-> payload
      expire
      jwt/jwt
      (jwt/sign :HS256 config/passphrase)
      jwt/to-str))

(defn check-token
  "Verify a JSON Web Token"
  [token]
  (try
    (do
      (-> token
        jwt/str->jwt
        (jwt/verify config/passphrase))
      true)
    (catch Exception e
      false)))

(defn decode
  "Decode a JSON Web Token"
  [token]
  (jwt/str->jwt token))