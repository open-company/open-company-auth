(ns open-company-auth.jwt
  (:require [clj-jwt.core :as jwt]
            [open-company-auth.config :as config]))

(defn generate
  "Get a JSON Web Token from a payload"
  [payload]
  (-> payload
      jwt/jwt
      (jwt/sign :HS256 config/passphrase)
      to-str))

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