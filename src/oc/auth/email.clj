(ns oc.auth.email
  (:require [clojure.string :as s]
            [clojure.walk :refer (keywordize-keys)]
            [schema.core :as schema]
            [buddy.hashers :as hashers]
            [oc.lib.hateoas :as hateoas]
            [oc.lib.slugify :refer (slugify)]
            [oc.auth.config :as config]
            [oc.auth.user :as user]))

(def ^:private prefix "email-")

(def auth-link (hateoas/link-map "authenticate" 
                                 hateoas/GET
                                 "/email-auth"
                                 "text/plain"))

(def refresh-link (hateoas/link-map "refresh" 
                                    hateoas/GET
                                    "/email/refresh-token"
                                    "text/plain"))

(def create-link (hateoas/link-map "create" 
                                 hateoas/POST
                                 "/email/users"
                                 "application/vnd.open-company.user.v1+json"))

(def auth-settings {:links [auth-link create-link]})

(defn- short-uuid []
  (str prefix (subs (str (java.util.UUID/randomUUID)) 9 18)))

(defn- password-hash [password]
  (s/join "$" (rest (s/split (hashers/derive password {:alg :bcrypt+sha512}) #"\$"))))

(defn- password-match? [password password-hash]
  (hashers/check password (str "bcrypt+sha512$" password-hash) {:alg :bcrypt+sha512}))

(defn authenticate? [conn email password]
  (if-let [user (user/get-user-by-email conn email)]
    (password-match? password (:password-hash user))
    false))

(schema/defn ->user :- user/User
  "Take a minimal map describing a user, and a password and 'fill the blanks'"
  [user-map password]
  {:pre [(map? user-map)
         (string? password)
         (not (s/blank? password))]}
  (let [props (-> user-map
                  keywordize-keys
                  (assoc :user-id (short-uuid))
                  (assoc :org-id (short-uuid))
                  (assoc :password-hash (password-hash password))
                  (dissoc :auth-source :password)
                  (update :first-name #(or % ""))
                  (update :last-name #(or % ""))
                  (update :name #(or % (:first-name user-map) (:last-name user-map) (:real-name user-map) ""))
                  (update :avatar #(or % nil)))] ;; TODO Gravatar
    (if (s/blank? (:real-name props))
      (assoc props :real-name (s/trim (s/join " " [(:first-name props) (:last-name props)])))
      props)))

(schema/defn ^:always-validate create-user!
  "Given a map of user properties, persist it to the database."
  [conn user :- user/User]
  (user/create-user! conn user))

(comment 

  (require '[oc.auth.email :as email] :reload)
  (require '[oc.auth.user :as user] :reload)

  (def u (read-string (slurp "./opt/identities/simone.edn")))

  (email/create-user! conn (email/->user u "S$cr$ts"))
  (user/get-user-by-email conn (:email u))
  (email/authenticate? conn (:email u) "S$cr$ts")
  (user/delete-user conn (:user-id u))
  
  )