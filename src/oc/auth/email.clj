(ns oc.auth.email
  (:require [clojure.string :as s]
            [buddy.hashers :as hashers]
            [oc.auth.config :as config]))

(def ^:private prefix "email:")

(def auth-settings {:auth-url (s/join "/" [config/auth-server-url "email-auth"])
                    :refresh-url (s/join "/" [config/auth-server-url "email" "refresh-token"])})

(defn- password-hash [password]
  (s/join "$" (rest (s/split (hashers/derive password {:alg :bcrypt+sha512}) #"\$"))))

(defn- password-match? [password password-hash]
  (hashers/check password (str "bcrypt+sha512$" password-hash) {:alg :bcrypt+sha512}))