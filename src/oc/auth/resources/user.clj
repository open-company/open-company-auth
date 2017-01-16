(ns oc.auth.resources.user
  "User stored in RethinkDB."
  (:require [oc.lib.rethinkdb.common :as db-common]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]))

;; ----- RethinkDB metadata -----

(def table-name "users")
(def primary-key :user-id)

;; ----- Schema -----

(def User {
  :user-id lib-schema/UniqueID
  :teams [lib-schema/UniqueID]
  (schema/optional-key :one-time-tokens) [lib-schema/NonBlankString]
  :first-name schema/Str
  :last-name schema/Str
  :email (schema/maybe schema/Str)
  (schema/optional-key :avatar-url) (schema/maybe schema/Str)
  :created-at lib-schema/ISO8601
  :updated-at lib-schema/ISO8601})

;; ----- User CRUD -----

(defn get-user
  "Given the user-id of the user, retrieve it from the database, or return nil if it doesn't exist."
  [conn user-id]
  {:pre [(string? user-id)]}
  (db-common/read-resource conn table-name user-id))

(defn get-user-by-email
  "Given the email address of the user, retrieve them from the database, or return nil if user doesn't exist."
  [conn email]
  {:pre [(string? email)]}
  (first (db-common/read-resources conn table-name "email" email)))

(defn get-user-by-token
  "Given the one-time-use token of the user, retrieve them from the database, or return nil if user doesn't exist."
  [conn token]
  {:pre [(string? token)]}
  (first (db-common/read-resources conn table-name "one-time-tokens" token)))

(defn create-user!
  "Given a map of user properties, persist it to the database."
  [conn user-map]
  (db-common/create-resource conn table-name user-map (db-common/current-timestamp)))

(defn delete-user!
  "Given the user-id of the user, delete it and return `true` on success."
  [conn user-id]
  {:pre [(string? user-id)]}
  (try
    (db-common/delete-resource conn table-name user-id)
    (catch java.lang.RuntimeException e))) ; it's OK if there is no user to delete

(defn replace-user!
  "
  Update the user specified by the `user-id` by replacing the existing user's existing properties
  with those provided `user-map`.
  "
  [conn user-id user-map]
  (if-let [user (get-user conn user-id)]
    (db-common/update-resource conn table-name primary-key user user-map)))

(defn update-user
  "
  Update the user specified by the `user-id` by merging the provided `user-map` into the existing
  user's existing properties.
  "
  [conn user-id user-map]
  (if-let [user (get-user conn user-id)]
    (db-common/update-resource conn table-name primary-key user (merge user user-map))))

;; ----- Collection of users -----

(defn list-users
  "Given an org-id, return the users for the org."
  [conn org-id]
  (db-common/read-resources-in-order conn table-name :org-id org-id [:user-id :org-id :real-name :email :avatar :status]))

;; ----- Armageddon -----

(defn delete-all-users!
  "Use with caution! Failure can result in partial deletes. Returns `true` if successful."
  [conn]
  (db-common/delete-all-resources! conn table-name))