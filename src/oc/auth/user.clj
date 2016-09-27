(ns oc.auth.user
  ""
  (:require [clojure.string :as s]
            [oc.lib.rethinkdb.common :as common]))

;; ----- RethinkDB metadata -----

(def table-name "users")
(def primary-key :user-id)

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

(defn delete-user
  "Given the user-id of the user, delete it and return `true` on success."
  [conn user-id]
  {:pre [(string? user-id)]}
  (try
    (common/delete-resource conn table-name user-id)
    (catch java.lang.RuntimeException e))) ; it's OK if there is no user to delete