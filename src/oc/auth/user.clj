(ns oc.auth.user
  "Users stored in RethinDB."
  (:require [oc.lib.rethinkdb.common :as common]
            [schema.core :as schema]))

;; ----- RethinkDB metadata -----

(def table-name "users")
(def primary-key :user-id)

;; ----- Schema -----

(def User {
   :user-id schema/Str
   :org-id schema/Str
   :name schema/Str
   :first-name schema/Str
   :last-name schema/Str
   :real-name schema/Str
   :avatar (schema/maybe schema/Str)
   (schema/optional-key :one-time-secret) schema/Str
})

;; ----- User CRUD -----

(defn get-user
  "Given the user-id of the user, retrieve it from the database, or return nil if it doesn't exist."
  [conn user-id]
  {:pre [(string? user-id)]}
  (common/read-resource conn table-name user-id))

(defn get-user-by-email
  "Given the slug of the company, retrieve it from the database, or return nil if it doesn't exist."
  [conn email]
  {:pre [(string? email)]}
  (first (common/read-resources conn table-name "email" email)))

(defn create-user!
  "Given a map of user properties, persist it to the database."
  [conn user-map]
  (common/create-resource conn table-name user-map (common/current-timestamp)))

(defn delete-user!
  "Given the user-id of the user, delete it and return `true` on success."
  [conn user-id]
  {:pre [(string? user-id)]}
  (try
    (common/delete-resource conn table-name user-id)
    (catch java.lang.RuntimeException e))) ; it's OK if there is no user to delete

(defn update-user
  [conn user-id user-map]
  (if-let [user (get-user conn user-id)]
    (common/update-resource conn table-name primary-key user (merge user user-map))))

;; ----- Collection of users -----

(defn list-users
  "Given an org-id, return the users for the org."
  [conn org-id]
  (common/read-resources-in-order conn table-name :org-id org-id [:user-id :org-id :real-name :email :avatar :status]))

;; ----- Armageddon -----

(defn delete-all-users!
  "Use with caution! Failure can result in partial deletes. Returns `true` if successful."
  [conn]
  (common/delete-all-resources! conn table-name))