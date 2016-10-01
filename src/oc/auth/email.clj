(ns oc.auth.email
  (:require [clojure.string :as s]
            [clojure.walk :refer (keywordize-keys)]
            [if-let.core :refer (if-let*)]
            [schema.core :as schema]
            [buddy.hashers :as hashers]
            [oc.lib.hateoas :as hateoas]
            [oc.lib.slugify :refer (slugify)]
            [oc.auth.config :as config]
            [oc.auth.user :as user]))

(def ^:private prefix "email-")

(def auth-link (hateoas/link-map "authenticate" 
                                 hateoas/GET
                                 "/email/auth"
                                 "text/plain"))

(def refresh-link (hateoas/link-map "refresh" 
                                    hateoas/GET
                                    "/email/refresh-token"
                                    "text/plain"))

(def create-link (hateoas/link-map "create" 
                                 hateoas/POST
                                 "/email/users"
                                 "application/vnd.open-company.user.v1+json"))

(defn self-link [user-id] (hateoas/self-link (str "/email/users/" user-id) "application/vnd.open-company.user.v1+json"))

(defn delete-link [user-id] (hateoas/delete-link (str "/email/users/" user-id)))

(def enumerate-link (hateoas/link-map "users" 
                                 hateoas/GET
                                 "/email/users"
                                 "application/vnd.collection+vnd.open-company.user+json;version=1"))

(def auth-settings {:links [auth-link create-link]})

(def authed-settings {:links [auth-link refresh-link create-link enumerate-link]})

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

;; ----- Schema -----

(def statuses #{"pending" "unverified" "active"})

(def EmailUser 
  (merge user/User {
   :email schema/Str
   :password-hash schema/Str
   :status (schema/pred #(statuses %))}))

;; ----- Email user creation -----

(schema/defn ->user :- EmailUser
  "Take a minimal map describing a user, and a password and 'fill the blanks'"
  
  ([user-map password] (->user user-map "unverified" password)) ; default status is email unverified

  ([user-map status password]
  {:pre [(map? user-map)
         (string? password)
         (not (s/blank? password))
         (statuses status)]}
  (let [props (-> user-map
                  keywordize-keys
                  (update :user-id #(or % (short-uuid)))
                  (update :org-id #(or % (short-uuid)))
                  (assoc :password-hash (password-hash password))
                  (assoc :status status)
                  (dissoc :auth-source :password)
                  (update :first-name #(or % ""))
                  (update :last-name #(or % ""))
                  (update :name #(or % (:first-name user-map) (:last-name user-map) (:real-name user-map) ""))
                  (update :avatar #(or % nil)))] ;; TODO Gravatar
    (if (s/blank? (:real-name props))
      (assoc props :real-name (s/trim (s/join " " [(:first-name props) (:last-name props)])))
      props))))

(schema/defn ^:always-validate create-user!
  "Given a map of user properties, persist it to the database."
  [conn user :- EmailUser]
  (user/create-user! conn user))

;; ----- User links -----

(defn user-link [user]
  (if-let* [user-id (:user-id user)
           user-response (select-keys user [:user-id :real-name :avatar :email :status])]
    (assoc user-response :links [(self-link user-id) (delete-link user-id)])))

(defn user-links
  [conn org-id]
  (if-let [users (user/list-users conn org-id)]
    (map user-link users)
    []))


(comment 

  (require '[oc.auth.email :as email] :reload)
  (require '[oc.auth.user :as user] :reload)

  (def u (read-string (slurp "./opt/identities/simone.edn")))
  (email/create-user! conn (email/->user u "active" "S$cr$ts"))

  (def u (read-string (slurp "./opt/identities/cioran.edn")))
  (email/create-user! conn (email/->user u "active" "S$cr$ts"))

  (def u (read-string (slurp "./opt/identities/nietzsche.edn")))
  (email/create-user! conn (email/->user u "active" "S$cr$ts"))

  (user/get-user-by-email conn (:email u))
  (email/authenticate? conn (:email u) "S$cr$ts")
  (user/delete-user conn (:user-id u))

  (user/list-users conn (:org-id u))
  (email/user-link u)
  (email/user-links conn (:org-id u))  

  (user/delete-all-users! conn)

)