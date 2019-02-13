(ns oc.auth.resources.user
  "User stored in RethinkDB."
  (:require [clojure.string :as s]
            [clojure.walk :refer (keywordize-keys)]
            [defun.core :refer (defun)]
            [if-let.core :refer (if-let*)]
            [schema.core :as schema]
            [buddy.hashers :as hashers]
            [oc.lib.db.common :as db-common]
            [oc.lib.jwt :as jwt]
            [oc.lib.schema :as lib-schema]
            [oc.auth.resources.team :as team-res]))

;; ----- RethinkDB metadata -----

(def table-name "users")
(def primary-key :user-id)

;; ----- Schema -----

(def statuses 
  "
  Possible user statuses:

  pending - awaiting invite response or email verification, can't login
  unverified - awaiting email verification, but can login
  active - Slack auth'd or verified email or invite
  "
  #{:pending :unverified :active})

(def mediums #{:email :slack :in-app})
(def digest-mediums (disj mediums :in-app))

(def QSGChecklist
  {(schema/optional-key :should-show-qsg?) (schema/maybe schema/Bool)
   (schema/optional-key :show-guide?) (schema/maybe schema/Bool)
   (schema/optional-key :invited?) (schema/maybe schema/Bool)
   (schema/optional-key :add-post?) (schema/maybe schema/Bool)
   (schema/optional-key :add-reminder?) (schema/maybe schema/Bool)
   (schema/optional-key :add-section?) (schema/maybe schema/Bool)
   (schema/optional-key :section-dialog-seen?) (schema/maybe schema/Bool)
   (schema/optional-key :slack-dismissed?) (schema/maybe schema/Bool)
   (schema/optional-key :guide-dismissed?) (schema/maybe schema/Bool)
   (schema/optional-key :tooltip-shown?) (schema/maybe schema/Bool)})

(def ^:private UserCommon
  (merge {:user-id lib-schema/UniqueID
          :teams [lib-schema/UniqueID]

          (schema/optional-key :one-time-token) lib-schema/UUIDStr

          :email (schema/maybe lib-schema/EmailAddress)          
          (schema/optional-key :password-hash) lib-schema/NonBlankStr

          :first-name schema/Str
          :last-name schema/Str
          :avatar-url (schema/maybe schema/Str)

          (schema/optional-key :title) (schema/maybe schema/Str)

          (schema/optional-key :timezone) (schema/maybe schema/Str) ; want it missing at first so we can default it on the client

          :digest-medium (schema/pred #(digest-mediums (keyword %)))
          :notification-medium (schema/pred #(mediums (keyword %)))
          :reminder-medium (schema/pred #(mediums (keyword %)))

          (schema/optional-key :last-token-at) lib-schema/ISO8601
          (schema/optional-key :qsg-checklist) QSGChecklist}
          lib-schema/slack-users
          lib-schema/google-users))

(def User "User resource as stored in the DB."
  (merge UserCommon {
    :status (schema/pred #(statuses (keyword %)))
    (schema/optional-key :slack-bots) (schema/maybe jwt/SlackBots)
    :created-at lib-schema/ISO8601
    :updated-at lib-schema/ISO8601}))

(def UserRep "A representation of the user, suitable for creating a JWToken."
  (merge UserCommon {
    :admin (schema/conditional sequential? [lib-schema/UniqueID] :else schema/Bool)
    (schema/optional-key :status) (schema/pred #(statuses (keyword %)))
    (schema/optional-key :slack-id) lib-schema/NonBlankStr
    (schema/optional-key :slack-token) lib-schema/NonBlankStr
    (schema/optional-key :slack-display-name) lib-schema/NonBlankStr
    (schema/optional-key :slack-bots) jwt/SlackBots
    (schema/optional-key :google-id) (schema/maybe schema/Str)
    (schema/optional-key :google-domain) (schema/maybe schema/Str)
    (schema/optional-key :google-token) (schema/maybe jwt/GoogleToken)
    (schema/optional-key :created-at) lib-schema/ISO8601
    (schema/optional-key :updated-at) lib-schema/ISO8601}))

;; ----- Metadata -----

(def reserved-properties
  "Properties of a resource that can't be specified during a create or update."
  #{:user-id :password :password-hash :created-at :udpated-at :links :slack-display-name})

(def ignored-properties
  "Properties of a resource that are ignored during an update."
  (clojure.set/union reserved-properties #{:teams :one-time-token :status :email}))

;; ----- Utility functions -----

(defn clean-props
  "Remove any reserved properties from the user."
  [user]
  (apply dissoc user reserved-properties))

(defn ignore-props
  "Remove any ignored properties from the user."
  [user]
  (apply dissoc user ignored-properties))

(defn- additional-keys? [additional-keys]
  (and (sequential? additional-keys)
       (every? #(or (string? %) (keyword? %)) additional-keys)))

;; ----- Password based authentication -----

(def ^:private crypto-algo :bcrypt+sha512)
(def ^:private trusted-algs #{:bcrypt+sha512}) ; if we change algos (above), add the additional algo to this list
(def ^:private crypto-algo-iterations 12)

(defn- password-hash
  "
  Create a crypto hash from the provided password as a string in the format:

  <algorithm used>$<salt used>$<password hash>
  "
  [password]
  (hashers/derive password {:alg crypto-algo :iterations crypto-algo-iterations}))

(defn password-match?
  "
  Return true if the provided password hashes to the provided password hash. The provided password hash is in
  the format:

  <algorithm used>$<salt used>$<password hash>
  "
  [password password-hash]
  (if (s/blank? password-hash)
    false
    (hashers/check password password-hash {:limit trusted-algs})))

(declare get-user-by-email)
(defn authenticate? [conn email password]
  (if-let [user (get-user-by-email conn email)]
    (password-match? password (:password-hash user))
    false))

;; ----- User CRUD -----

(def user-images
 ["/img/ML/happy_face_red.svg"
  "/img/ML/happy_face_green.svg"
  "/img/ML/happy_face_blue.svg"
  "/img/ML/happy_face_purple.svg"
  "/img/ML/happy_face_yellow.svg"])

(defn random-user-image []
  (first (shuffle (vec user-images))))

(schema/defn ^:always-validate ->user :- User
  "Take a minimal map describing a user and 'fill the blanks' with any missing properties."
  ([user-props password :- lib-schema/NonBlankStr]
    (-> (->user user-props)
      (assoc :password-hash (password-hash password)) ; add hashed password
      (assoc :one-time-token (str (java.util.UUID/randomUUID))))) ; add one-time-token for email verification 
      
  ([user-props]
  {:pre [(map? user-props)]}
  (let [ts (db-common/current-timestamp)]
    (-> user-props
        keywordize-keys
        clean-props
        (assoc :user-id (db-common/unique-id))
        (update :teams #(or % []))
        (update :email #(or % ""))
        (update :first-name #(or % ""))
        (update :last-name #(or % ""))
        (update :avatar-url #(or % (random-user-image)))
        (update :digest-medium #(or % :email)) ; lowest common denominator
        (update :notification-medium #(or % :email)) ; lowest common denominator
        (update :reminder-medium #(or % :email)) ; lowest common denominator
        (assoc :status :pending)
        (assoc :created-at ts)
        (assoc :updated-at ts)))))

(schema/defn ^:always-validate create-user!
  "Create a user in the system. Throws a runtime exception if user doesn't conform to the User schema."
  [conn user :- User]
  {:pre [(db-common/conn? conn)]}
  (let [email (:email user)
        email-domain (last (s/split email #"\@"))
        email-teams (map :team-id (team-res/list-teams-by-index conn :email-domains email-domain))
        existing-teams (vec (set (concat email-teams (:teams user))))
        new-team (when (empty? existing-teams)
                    (team-res/create-team! conn (team-res/->team {} (:user-id user))))
        teams (if new-team [(:team-id new-team)] existing-teams)
        user-with-teams (assoc user :teams teams)
        user-with-status (if new-team
                            (assoc user-with-teams :status :unverified) ; new team, so no need to pre-verify
                            user-with-teams)]
    (db-common/create-resource conn table-name user-with-status (db-common/current-timestamp))))

(schema/defn ^:always-validate get-user :- (schema/maybe User)
  "Given the user-id of the user, retrieve them from the database, or return nil if they don't exist."
  [conn user-id :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (db-common/read-resource conn table-name user-id))

(schema/defn ^:always-validate get-user-by-email :- (schema/maybe User)
  "Given the email address of the user, retrieve them from the database, or return nil if user doesn't exist."
  [conn email :- lib-schema/NonBlankStr]
  {:pre [(db-common/conn? conn)]}
  (let [loweremail (clojure.string/lower-case email)]
    (first (db-common/read-resources conn table-name "loweremails" loweremail))))

(schema/defn ^:always-validate get-user-by-token :- (schema/maybe User)
  "Given the one-time-use token of the user, retrieve them from the database, or return nil if user doesn't exist."
  [conn token :- lib-schema/NonBlankStr]
  {:pre [(db-common/conn? conn)]}
  (first (db-common/read-resources conn table-name "one-time-token" token)))

(schema/defn ^:always-validate get-user-by-slack-id :- (schema/maybe User)
  "Given the slack id of the user, retrieve them from the database, or return nil if user doesn't exist."
  [conn slack-team-id :- lib-schema/NonBlankStr slack-id :- lib-schema/NonBlankStr]
  {:pre [(db-common/conn? conn)]}
  (first (db-common/read-resources conn table-name "user-slack-team-id" [[slack-team-id, slack-id]])))

(schema/defn ^:always-validate update-user! :- User
  "
  Given an updated user property map, update the user and return the updated user on success.

  Throws a runtime exception if the merge of the prior user and the updated user property map doesn't conform
  to the User schema.
  
  NOTE: doesn't update teams, see: `add-team`, `remove-team`
  NOTE: doesn't update one-time token, see: `add-token`, `remove-token`
  NOTE: doesn't handle case of user-id change.
  NOTE: doesn't handle case of status change, see: `activate!`, `unverify!`
  "
  [conn :- lib-schema/Conn user-id :- lib-schema/UniqueID user]
  {:pre [(map? user)]}
  (if-let [original-user (get-user conn user-id)]
    (let [updated-password (:password user)
          hashed-password (when-not (s/blank? updated-password) (password-hash updated-password))
          updated-user (merge original-user (ignore-props user))
          final-user (if hashed-password (assoc updated-user :password-hash hashed-password) updated-user)]
      (schema/validate User final-user)
      (db-common/update-resource conn table-name primary-key original-user final-user))))

(schema/defn ^:always-validate activate!
  "Update the user's status to 'active'. Returns the updated user."
  [conn :- lib-schema/Conn user-id :- lib-schema/UniqueID]
  (if-let [original-user (get-user conn user-id)]
    (db-common/update-resource conn table-name primary-key original-user (assoc original-user :status :active))
    false))

(schema/defn ^:always-validate  delete-user!
  "Given the user-id of the user, delete it and return `true` on success."
  [conn :- lib-schema/Conn user-id :- lib-schema/UniqueID]
  (try
    ;; Remove admin roles
    (doseq [team-id (jwt/admin-of conn user-id)] (team-res/remove-admin conn team-id user-id))
    ;; Remove user
    (db-common/delete-resource conn table-name user-id)
    (catch java.lang.RuntimeException e))) ; it's OK if there is no user to delete

(schema/defn ^:always-validate add-token :- (schema/maybe User)
  "
  Given the user-id of the user, and a one-time use token, add the token to the user if they exist.
  Returns the updated user on success, nil on non-existence, and a RethinkDB error map on other errors.
  "
  ([conn user-id :- lib-schema/UniqueID] (add-token conn user-id (str (java.util.UUID/randomUUID))))

  ([conn :- lib-schema/Conn user-id :- lib-schema/UniqueID token :- lib-schema/UUIDStr]
  (if-let [user (get-user conn user-id)]
    (db-common/update-resource conn table-name primary-key user (assoc user :one-time-token token)))))

(schema/defn ^:always-validate remove-token :- (schema/maybe User)
  "
  Given the user-id of the user, and a one-time use token, remove the token from the user if they exist.
  Returns the updated user on success, nil on non-existence, and a RethinkDB error map on other errors.
  "
  [conn :- lib-schema/Conn user-id :- lib-schema/UniqueID]
  (if-let [user (get-user conn user-id)]
    (db-common/remove-property conn table-name user-id "one-time-token")))

;; ----- User's set operations -----

(schema/defn ^:always-validate add-team :- (schema/maybe User)
  "
  Given the user-id of the user, and the team-id of the team, add the user to the team if they both exist.
  Returns the updated user on success, nil on non-existence, and a RethinkDB error map on other errors.
  "
  [conn user-id :- lib-schema/UniqueID team-id :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (if-let* [user (get-user conn user-id)
            team (team-res/get-team conn team-id)]
    (db-common/add-to-set conn table-name user-id "teams" team-id)))

(schema/defn ^:always-validate remove-team :- (schema/maybe User)
  "
  Given the user-id of the user, and the team-id of the team, remove the user from the team if they exist.
  Returns the updated user on success, nil on non-existence, and a RethinkDB error map on other errors.
  "
  [conn user-id :- lib-schema/UniqueID team-id :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (if-let [user (get-user conn user-id)]
    (db-common/remove-from-set conn table-name user-id "teams" team-id)))

(defn admin-of [conn user-id] (jwt/admin-of conn user-id)) ; alias

;; ----- Collection of users -----

(defun list-users
  "
  Given an optional team-id, return a list of users.

  Additional fields can be optionally specified."
  ([conn] (list-users conn []))
  
  ([conn :guard db-common/conn? 
    additional-keys :guard additional-keys?]
  (db-common/read-resources conn table-name
    (concat additional-keys [:user-id :email :status :first-name :last-name :avatar-url :teams])))

  ([conn team-id] (list-users conn team-id []))

  ([conn :guard db-common/conn?
    team-id :guard #(schema/validate lib-schema/UniqueID %)
    additional-keys :guard additional-keys?]
  (db-common/read-resources-in-order conn table-name :teams team-id
    (concat additional-keys [:user-id :email :status :first-name :last-name :avatar-url :teams]))))

;; ----- Armageddon -----

(defn delete-all-users!
  "Use with caution! Failure can result in partial deletes. Returns `true` if successful."
  [conn]
  {:pre [(db-common/conn? conn)]}
  (db-common/delete-all-resources! conn table-name))