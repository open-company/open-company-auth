(ns oc.auth.resources.slack-org
  "Slack org stored in RethinkDB."
  (:require [oc.lib.rethinkdb.common :as db-common]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]))

;; ----- RethinkDB metadata -----

(def table-name "slack_orgs")
(def primary-key :slack-id)

;; ----- Schema -----

(def SlackOrg {
  :slack-id lib-schema/NonBlankString
  :name lib-schema/NonBlankString
  (schema/maybe :bot-user-id) lib-schema/NonBlankString
  (schema/maybe :bot-access-token) lib-schema/NonBlankString
  :created-at lib-schema/ISO8601
  :updated-at lib-schema/ISO8601})

;; ----- Metadata -----

(def reserved-properties
  "Properties of a resource that can't be specified during a create and are ignored during an update."
  #{:created-at :udpated-at :links})

;; ----- Utility functions -----

(defn- clean
  "Remove any reserved properties from the user."
  [slack-org]
  (apply dissoc slack-org reserved-properties))

;; ----- Slack Org CRUD -----


;; ----- Armageddon -----

(defn delete-all-slack-orgs!
  "Use with caution! Failure can result in partial deletes. Returns `true` if successful."
  [conn]
  {:pre [(db-common/conn? conn)]}
  (db-common/delete-all-resources! conn table-name))