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

(def ignored-properties
  "Properties of a resource that are ignored during an update."
  (merge reserved-properties #{:slack-org-id}))

;; ----- Utility functions -----

(defn- clean
  "Remove any reserved properties from the user."
  [slack-org]
  {:pre [(map? slack-org)]}
  (apply dissoc slack-org reserved-properties))

(defn ignore-props
  "Remove any ignored properties from the user."
  [user]
  (apply dissoc user ignored-properties))

(defn- slack-org-from-slack-response
  "Manipulate Slack auth response fields into fields for Slack org storage."
  [slack-response]
  (let [slack-org (-> slack-response 
                    (clojure.set/rename-keys {:slack-org-name :name})
                    (dissoc :bot))]
    (if (:bot slack-response)
      (-> slack-org
        (assoc :bot-user-id (-> slack-response :bot :id))
        (assoc :bot-token (-> slack-response :bot :token)))
      slack-org)))

;; ----- Slack Org CRUD -----

(schema/defn ^:always-validate ->slack-org :- SlackOrg
  "
  Take a minimal map describing a Slack org, and 'fill the blanks' with any missing properties.
  "
  [slack-org-props]
  {:pre [(map? slack-org-props)]}
  (let [ts (db-common/current-timestamp)]
    (-> slack-org-props
      keywordize-keys
      clean
      (slack-org-from-slack-response)
      (assoc :created-at ts)
      (assoc :updated-at ts))))

(schema/defn ^:always-validate create-slack-org!
  "Create a Slack org in the system. Throws a runtime exception if the Slack org doesn't conform to the SlackOrg schema."
  [conn :- lib-schema/Conn slack-org :- SlackOrg]
  (db-common/create-resource conn table-name slack-org (db-common/current-timestamp)))

(schema/defn ^:always-validate get-slack-org :- (schema/maybe SlackOrg)
  "Given the slack-org-id of the Slack org, retrieve it, or return nil if it don't exist."
  [conn :- lib-schema/Conn slack-org-id :- lib-schema/NonBlankStr]
  (db-common/read-resource conn table-name slack-org-id))

(schema/defn ^:always-validate update-slack-org! :- SlackOrg
  "
  Given an updated Slack org property map, update the Slack org and return the updated Slack org on success.

  Throws a runtime exception if the merge of the prior Slack org and the updated Slack org property map doesn't conform
  to the SlackOrg schema.
  
  NOTE: doesn't handle case of slack-org-id change.
  "
  [conn slack-org-id :- lib-schema/NonBlankStr slack-org]
  {:pre [(db-common/conn? conn)
         (map? slack-org)]}
  (if-let [original-slack-org (get-slack-org conn slack-org-id)]
    (let [updated-slack-props (-> slack-org
                                  keywordize-keys
                                  clean
                                  (slack-org-from-slack-response))
          updated-slack-org (merge original-slack-org (ignore-props updated-slack-props))]
      (schema/validate SlackOrg updated-slack-org)
      (db-common/update-resource conn table-name primary-key original-slack-org updated-slack-org))))

(schema/defn ^:always-validate delete-slack-org!
  "Given the slack-org-id of the Slack org, delete it and return `true` on success."
  [conn :- lib-schema/Conn slack-org-id :- lib-schema/NonBlankStr]
  (try
    (db-common/delete-resource conn table-name slack-org-id)
    (catch java.lang.RuntimeException e))) ; it's OK if there is no Slack org to delete

;; ----- Collection of Slack orgs -----

(schema/defn ^:always-validate list-slack-orgs
  "List all Slack orgs, returning `slack-org-id` and `name`. Additional fields can be optionally specified."
  ([conn]
  (list-slack-orgs conn []))

  ([conn :- lib-schema/Conn additional-keys]
  {:pre [(sequential? additional-keys)
         (every? #(or (string? %) (keyword? %)) additional-keys)]}
  (db-common/read-resources conn table-name (concat [:slack-org-id :name] additional-keys))))

(schema/defn ^:always-validate list-slack-orgs-by-ids
  "
  Get Slack orgs by a sequence of slack-org-id's, returning `slack-org-id` and `name`. 
  
  Additional fields can be optionally specified.
  "
  ([conn slack-org-ids]
  (list-slack-orgs-by-ids conn slack-org-ids []))

  ([conn :- lib-schema/Conn slack-org-ids :- [lib-schema/NonBlankStr] additional-keys]
  {:pre [(sequential? additional-keys)
         (every? #(or (string? %) (keyword? %)) additional-keys)]}
  (if (empty? slack-org-ids)
    []
    (db-common/read-resources-by-primary-keys conn table-name slack-org-ids (concat [:slack-org-id :name] additional-keys)))))

;; ----- Armageddon -----

(schema/defn ^:always-validate delete-all-slack-orgs!
  "Use with caution! Failure can result in partial deletes. Returns `true` if successful."
  [conn :- lib-schema/Conn]
  (db-common/delete-all-resources! conn table-name))