(ns oc.auth.email
  (:require [clojure.string :as s]
            [clojure.walk :refer (keywordize-keys)]
            [if-let.core :refer (if-let*)]
            [schema.core :as schema]
            [buddy.hashers :as hashers]
            [oc.lib.hateoas :as hateoas]
            [oc.auth.resources.user :as user]))

(def prefix "email-")

(def ^:private crypto-algo "bcrypt+sha512$")

;; ----- Schema -----

; active - verified email
; pending - awaiting invite response
; unverified - awaiting email verification
(def statuses #{"pending" "unverified" "active"})

(def EmailUser 
  (merge user/User {
   :email schema/Str
   :password-hash schema/Str
   :status (schema/pred #(statuses %))
   :auth-source (schema/pred #(= "email" %))}))

(def updateable-props #{:email :password :name :first-name :last-name :real-name :avatar})
(def public-props [:email :avatar :name :first-name :last-name :real-name])
(def jwt-props [:user-id :org-id :name :first-name :last-name :real-name :avatar :email :auth-source])
(def private-props [:password-hash :created-at :updated-at])

;; ----- HATEOAS -----

(def ^:private text "text/plain")
(def ^:private user-type "application/vnd.open-company.user.v1+json")
(def ^:private invite-type "application/vnd.open-company.invitation.v1+json")
(def ^:private user-collection-type "application/vnd.collection+vnd.open-company.user+json;version=1")

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

(def reset-link (hateoas/link-map "reset-password"
                                  hateoas/POST
                                  "/email/reset"
                                  "application/json"))

(defn invite-link [org-id] (hateoas/link-map "invite" 
                                             hateoas/POST
                                             (s/join "/" ["/org" org-id "users" "invite"])
                                             invite-type))

(defn self-link [org-id user-id] (hateoas/self-link (user-url org-id user-id) user-type))

(defn partial-update-link [org-id user-id] (hateoas/partial-update-link (user-url org-id user-id) user-type))

(defn re-invite-link [org-id user-id] (hateoas/link-map "invite"
                                                        hateoas/POST
                                                        (s/join "/" [(user-url org-id user-id) "invite"])
                                                        invite-type))

(defn delete-link [org-id user-id] (hateoas/delete-link (user-url org-id user-id)))

(defn enumerate-link [org-id] (hateoas/link-map "users" 
                                                 hateoas/GET
                                                 (s/join "/" ["/org" org-id "users"])
                                                 user-collection-type))

(def auth-settings {:links [auth-link create-link reset-link]})

(defn authed-settings [org-id user-id] {:links [(self-link org-id user-id)
                                                (partial-update-link org-id user-id)
                                                refresh-link
                                                (invite-link org-id)
                                                (enumerate-link org-id)]})

(defn user-links 
  ([user] (user-links nil user))
  
  ([listing-user-id user]
  (if-let* [user-id (:user-id user)
            org-id (:org-id user)
            user-response (select-keys user [:user-id :real-name :avatar :email :status])
            named-response (if (and (= listing-user-id user-id) (s/blank? (:real-name user-response))) 
                            (assoc user-response :real-name "You")
                            user-response)
            everyone-links [(self-link org-id user-id)]
            update-links (if (= listing-user-id user-id)
                          (conj everyone-links (partial-update-link org-id user-id))
                          everyone-links)
            delete-links (if (not= listing-user-id user-id)
                          (conj update-links (delete-link org-id user-id))
                          update-links)
            links (if (and (not= listing-user-id user-id) (= (:status user) "pending"))
                    (conj delete-links (re-invite-link org-id user-id))
                    delete-links)]
    (assoc named-response :links links))))

(defn users-links
  ([conn org-id] (user-links org-id nil))
  ([conn org-id user-id]
  (if-let [users (user/list-users conn org-id)]
    (map (partial user-links user-id) users)
    [])))

;; ----- Password authentication -----

(defn- password-hash [password]
  (s/join "$" (rest (s/split (hashers/derive password {:alg :bcrypt+sha512}) #"\$"))))

(defn- password-match? [password password-hash]
  (hashers/check password (str crypto-algo password-hash) {:alg :bcrypt+sha512}))

(defn authenticate? [conn email password]
  (if-let [user (user/get-user-by-email conn email)]
    (password-match? password (:password-hash user))
    false))

;; ----- Email user creation -----

(defn- short-uuid []
  (str prefix (subs (str (java.util.UUID/randomUUID)) 9 18)))

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
                  (dissoc :password)
                  (assoc :status status)
                  (assoc :auth-source "email")
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

;; ----- Existing user functions -----

(defn reset-password! [conn user-id password]
  (user/update-user conn user-id {:password-hash (password-hash password)}))

(defn update-user
  "Given a map of user and a map of partial updates, update the user."
  [conn user user-map]
  (let [new-first-name (:first-name user-map)
        first-name (or new-first-name (:first-name user))
        new-last-name (:last-name user-map)
        last-name (or new-last-name (:lastname user))
        real-name-map (if (:real-name user-map)
                    user-map ; keep the updating real-name
                    (if (or new-first-name new-last-name)
                      (assoc user-map :real-name (s/trim (s/join " " [first-name last-name])))
                      user-map)) ; no name update
        name-map (if (and (s/blank? (:name user)) ; don't have a name
                          (not (:name user-map)) ; name wasn't provided
                          (or new-first-name new-last-name)) ; but first or last name was provided
                      (assoc real-name-map :name (or new-first-name new-last-name))
                      real-name-map)
        password (:password user-map)
        password-map (if password (-> name-map
                                    (assoc :password-hash (password-hash password))
                                    (dissoc :password))
                                  name-map)]
        
    (schema/validate EmailUser (merge user password-map))
    (user/update-user conn (:user-id user) password-map)))

(comment 

  (require '[oc.auth.email :as email] :reload)
  (require '[oc.auth.resources.user :as user] :reload)

  (def u (read-string (slurp "./opt/identities/simone.edn")))
  (email/create-user! conn (email/->user u "active" "S$cr$ts"))

  (def u (read-string (slurp "./opt/identities/nietzsche.edn")))
  (email/create-user! conn (email/->user u "pending" "S$cr$ts"))

  (def u (read-string (slurp "./opt/identities/cioran.edn")))
  (email/create-user! conn (email/->user u "unverified" "S$cr$ts"))

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