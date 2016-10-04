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

(def prefix "email-")

(def ^:private text "text/plain")
(def ^:private user-type "application/vnd.open-company.user.v1+json")
(def ^:private invite-type "application/vnd.open-company.invitation.v1+json")
(def ^:private user-collection-type "application/vnd.collection+vnd.open-company.user+json;version=1")

(def ^:private crypto-algo "bcrypt+sha512$")

(defn user-url [org-id user-id] (s/join "/" ["/org" org-id "users" user-id]))

(def auth-link (hateoas/link-map "authenticate" 
                                 hateoas/GET
                                 "/email/auth"
                                 text))

(def refresh-link (hateoas/link-map "refresh" 
                                    hateoas/GET
                                    "/email/refresh-token"
                                    text))

(def create-link (hateoas/link-map "create" 
                                 hateoas/POST
                                 "/email/users"
                                 user-type))

(defn invite-link [org-id] (hateoas/link-map "invite" 
                                             hateoas/POST
                                             (s/join "/" ["/org" org-id "users" "invite"])
                                             invite-type))

(defn self-link [org-id user-id] (hateoas/self-link (user-url org-id user-id) user-type))

(defn re-invite-link [org-id user-id] (hateoas/link-map "invite"
                                                        hateoas/POST
                                                        (s/join "/" [(user-url org-id user-id) "invite"])
                                                        invite-type))

(defn delete-link [org-id user-id] (hateoas/delete-link (user-url org-id user-id)))

(defn enumerate-link [org-id] (hateoas/link-map "users" 
                                                 hateoas/GET
                                                 (s/join "/" ["/org" org-id "users"])
                                                 user-collection-type))

(def auth-settings {:links [auth-link create-link]})

(defn authed-settings [org-id] {:links [auth-link
                                        refresh-link
                                        (invite-link org-id)
                                        (enumerate-link org-id)]})

(defn- short-uuid []
  (str prefix (subs (str (java.util.UUID/randomUUID)) 9 18)))

(defn- password-hash [password]
  (s/join "$" (rest (s/split (hashers/derive password {:alg :bcrypt+sha512}) #"\$"))))

(defn- password-match? [password password-hash]
  (hashers/check password (str crypto-algo password-hash) {:alg :bcrypt+sha512}))

(defn authenticate? [conn email password]
  (if-let [user (user/get-user-by-email conn email)]
    (password-match? password (:password-hash user))
    false))

;; ----- Schema -----

; active - verified email
; pending - awaiting invite response
; unverified - awaiting email verification
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
                  (update :avatar #(or % "")))] ;; TODO Gravatar
    (if (s/blank? (:real-name props))
      (assoc props :real-name (s/trim (s/join " " [(:first-name props) (:last-name props)])))
      props))))

(schema/defn ^:always-validate create-user!
  "Given a map of user properties, persist it to the database."
  [conn user :- EmailUser]
  (user/create-user! conn user))

(defn reset-password! [conn user-id password]
  (user/update-user conn user-id {:password-hash (password-hash password)}))

;; ----- User links -----

(defn user-links [user]
  (if-let* [user-id (:user-id user)
            org-id (:org-id user)
            user-response (select-keys user [:user-id :real-name :avatar :email :status])
            everyone-links [(self-link org-id user-id) (delete-link org-id user-id)]
            links (if (= (:status user) "pending")
                    (conj everyone-links (re-invite-link org-id user-id))
                    everyone-links)]
    (assoc user-response :links links)))

(defn users-links
  [conn org-id]
  (if-let [users (user/list-users conn org-id)]
    (map user-links users)
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
  (email/reset-password! (:user-id u) "$ecret$")
  (email/authenticate? conn (:email u) "S$cr$ts")
  (email/authenticate? conn (:email u) "$ecret$")
  (user/delete-user! conn (:user-id u))

  (user/list-users conn (:org-id u))
  (email/user-links u)
  (email/users-links conn (:org-id u))  

  (user/delete-all-users! conn)

)