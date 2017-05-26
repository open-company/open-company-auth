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

(def ^:private UserCommon {
  :user-id lib-schema/UniqueID
  :teams [lib-schema/UniqueID]
  (schema/optional-key :one-time-token) lib-schema/UUIDStr
  :email (schema/maybe lib-schema/EmailAddress)
  (schema/optional-key :password-hash) schema/Str
  :first-name schema/Str
  :last-name schema/Str
  (schema/optional-key :slack-users) (schema/maybe {lib-schema/NonBlankStr {:slack-org-id lib-schema/NonBlankStr
                                                                            :id lib-schema/NonBlankStr
                                                                            :token lib-schema/NonBlankStr}})
  :avatar-url (schema/maybe schema/Str)})

(def User "User resource as stored in the DB."
  (merge UserCommon {
    :status (schema/pred #(statuses (keyword %)))
    :created-at lib-schema/ISO8601
    :updated-at lib-schema/ISO8601}))

(def UserRep "A representation of the user, suitable for creating a JWToken."
  (merge UserCommon {
    :admin (schema/conditional sequential? [lib-schema/UniqueID] :else schema/Bool)
    (schema/optional-key :status) (schema/pred #(statuses (keyword %)))
    (schema/optional-key :slack-id) schema/Str
    (schema/optional-key :slack-token) schema/Str
    (schema/optional-key :slack-bots) jwt/SlackBots
    (schema/optional-key :created-at) lib-schema/ISO8601
    (schema/optional-key :updated-at) lib-schema/ISO8601}))

;; ----- Metadata -----

(def reserved-properties
  "Properties of a resource that can't be specified during a create or update."
  #{:user-id :password :password-hash :created-at :udpated-at :links})

(def ignored-properties
  "Properties of a resource that are ignored during an update."
  (merge reserved-properties #{:teams :one-time-token :status}))

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

(def ^:private crypto-algo "bcrypt+sha512$")

(defn- password-hash [password]
  (s/join "$" (rest (s/split (hashers/derive password {:alg :bcrypt+sha512}) #"\$"))))

(defn password-match? [password password-hash]
  (if (s/blank? password-hash)
    false
    (hashers/check password (str crypto-algo password-hash) {:alg :bcrypt+sha512})))

(declare get-user-by-email)
(defn authenticate? [conn email password]
  (if-let [user (get-user-by-email conn email)]
    (password-match? password (:password-hash user))
    false))

;; ----- User CRUD -----

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
        (update :avatar-url #(or % ""))
        (assoc :status "pending")
        (assoc :created-at ts)
        (assoc :updated-at ts)))))

(schema/defn ^:always-validate create-user!
  "Create a user in the system. Throws a runtime exception if user doesn't conform to the User schema."
  [conn user :- User]
  {:pre [(db-common/conn? conn)]}
  (let [email (:email user)
        email-domain (last (s/split email #"\@"))
        email-teams (map :team-id (team-res/list-teams-by-index conn :email-domains email-domain))
        existing-teams (concat email-teams (:teams user))
        new-team (when (empty? existing-teams)
                    (team-res/create-team! conn (team-res/->team {} (:user-id user))))
        teams (if new-team [(:team-id new-team)] existing-teams)
        user-with-teams (assoc user :teams teams)
        user-with-status (if new-team
                            (assoc user-with-teams :status "unverified") ; new team, so no need to pre-verify
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
  (first (db-common/read-resources conn table-name "email" email)))

(schema/defn ^:always-validate get-user-by-token :- (schema/maybe User)
  "Given the one-time-use token of the user, retrieve them from the database, or return nil if user doesn't exist."
  [conn token :- lib-schema/NonBlankStr]
  {:pre [(db-common/conn? conn)]}
  (first (db-common/read-resources conn table-name "one-time-token" token)))

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

(schema/defn ^:always-validate admin-of :- (schema/maybe [lib-schema/UniqueID])
  "Given the user-id of the user, return a sequence of team-ids for the teams the user is an admin of."
  [conn user-id :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (let [teams (team-res/list-teams-by-index conn :admins user-id)]
    (vec (map :team-id teams))))

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