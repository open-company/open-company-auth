(ns oc.auth.email
  (:require [clojure.string :as s]
            [buddy.hashers :as hashers]
            [oc.auth.config :as config]
            [oc.auth.user :as user]))

(def ^:private prefix "email:")

(def auth-settings {:auth-url (s/join "/" [config/auth-server-url "email-auth"])
                    :refresh-url (s/join "/" [config/auth-server-url "email" "refresh-token"])})

(defn- password-hash [password]
  (s/join "$" (rest (s/split (hashers/derive password {:alg :bcrypt+sha512}) #"\$"))))

(defn- password-match? [password password-hash]
  (hashers/check password (str "bcrypt+sha512$" password-hash) {:alg :bcrypt+sha512}))

(defn authenticate? [conn email password]
  (if-let [user (user/get-user-by-email conn email)]
    (password-match? password (:password-hash user))
    false))

(defn create-user!
  "Given a map of user properties, and a password, persist it to the database."
  [conn user-map password]
  (user/create-user! conn (assoc user-map :password-hash (password-hash password))))

(comment 

  (require '[oc.auth.email :as email] :reload)
  (require '[oc.auth.user :as user] :reload)

  (def u (read-string (slurp "./opt/identities/simone.edn")))

  (email/create-user! conn u "S$cr$ts")
  (user/get-user-by-email conn (:email u))
  (email/authenticate? conn (:email u) "S$cr$ts")
  (user/delete-user conn (:user-id u))
  
  )