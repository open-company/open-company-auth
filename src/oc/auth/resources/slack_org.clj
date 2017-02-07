(ns oc.auth.resources.slack-org
  "Slack org stored in RethinkDB."
  (:require [clojure.walk :refer (keywordize-keys)]
            [oc.lib.db.common :as db-common]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]))

;; ----- RethinkDB metadata -----

(def table-name "slack_orgs")
(def primary-key :slack-org-id)

;; ----- Schema -----

(def SlackOrg {
  :slack-org-id lib-schema/NonBlankStr
  :name lib-schema/NonBlankStr
  (schema/optional-key :bot-user-id) lib-schema/NonBlankStr
  (schema/optional-key :bot-token) lib-schema/NonBlankStr
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

(schema/defn ^:always-validate ->slack-org :- SlackOrg
  "
  Take a minimal map describing a Slack org, and 'fill the blanks' with any missing properties.
  "
  [slack-org-props]
  {:pre [(map? slack-org-props)]}
  (let [ts (db-common/current-timestamp)
        slack-org (-> slack-org-props
                    keywordize-keys
                    clean
                    (assoc :name (:slack-org-name slack-org-props))
                    (dissoc :slack-org-name)
                    (dissoc :bot)
                    (assoc :created-at ts)
                    (assoc :updated-at ts))]
    (if (:bot slack-org-props)
      (-> slack-org
        (assoc :bot-user-id (-> slack-org-props :bot :id))
        (assoc :bot-token (-> slack-org-props :bot :token)))
      slack-org)))

(schema/defn ^:always-validate create-slack-org!
  "Create a Slack org in the system. Throws a runtime exception if user doesn't conform to the Team schema."
  [conn slack-org :- SlackOrg]
  {:pre [(db-common/conn? conn)]}
  (db-common/create-resource conn table-name slack-org (db-common/current-timestamp)))

(schema/defn ^:always-validate get-slack-org :- (schema/maybe SlackOrg)
  "Given the slack-org-id of the Slack org, retrieve it, or return nil if it don't exist."
  [conn slack-org-id :- lib-schema/NonBlankStr]
  {:pre [(db-common/conn? conn)]}
  (db-common/read-resource conn table-name slack-org-id))

(defn delete-slack-org!
  "Given the slack-org-id of the Slack org, delete it and return `true` on success."
  [conn slack-org-id]
  {:pre [(db-common/conn? conn)
         (schema/validate lib-schema/NonBlankStr slack-org-id)]}
  (try
    (db-common/delete-resource conn table-name slack-org-id)
    (catch java.lang.RuntimeException e))) ; it's OK if there is no Slack org to delete

;; ----- Collection of Slack orgs -----

(defn list-slack-orgs
  "List all Slack orgs, returning `slack-org-id` and `name`. Additional fields can be optionally specified."
  ([conn]
  (list-slack-orgs conn []))

  ([conn additional-keys]
  {:pre [(db-common/conn? conn)
        (sequential? additional-keys)
        (every? #(or (string? %) (keyword? %)) additional-keys)]}
  (db-common/read-resources conn table-name (concat [:slack-org-id :name] additional-keys))))

(defn get-slack-orgs
  "
  Get Slack orgs by a sequence of slack-org-id's, returning `slack-org-id` and `name`. 
  
  Additional fields can be optionally specified.
  "
  ([conn slack-org-ids]
  (get-slack-orgs conn slack-org-ids []))

  ([conn slack-org-ids additional-keys]
  {:pre [(db-common/conn? conn)
         (schema/validate [lib-schema/NonBlankStr] slack-org-ids)
         (sequential? additional-keys)
        (every? #(or (string? %) (keyword? %)) additional-keys)]}
  (db-common/read-resources-by-primary-keys conn table-name slack-org-ids (concat [:slack-org-id :name] additional-keys))))

;; ----- Armageddon -----

(defn delete-all-slack-orgs!
  "Use with caution! Failure can result in partial deletes. Returns `true` if successful."
  [conn]
  {:pre [(db-common/conn? conn)]}
  (db-common/delete-all-resources! conn table-name))