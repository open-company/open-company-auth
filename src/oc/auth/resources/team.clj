(ns oc.auth.resources.team
  "Team stored in RethinkDB."
  (:require [clojure.walk :refer (keywordize-keys)]
            [if-let.core :refer (when-let*)]
            [oc.lib.rethinkdb.common :as db-common]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]))

;; ----- RethinkDB metadata -----

(def table-name "teams")
(def primary-key :team-id)

;; ----- Schema -----

(def Team {
  :team-id lib-schema/UniqueID
  :name schema/Str
  :admins [lib-schema/UniqueID]
  :email-domains [lib-schema/NonBlankString]
  :slack-orgs [lib-schema/NonBlankString]
  :created-at lib-schema/ISO8601
  :updated-at lib-schema/ISO8601})

;; ----- Metadata -----

(def reserved-properties
  "Properties of a resource that can't be specified during a create and are ignored during an update."
  #{:team-id :admins :email-domains :slack-orgs :created-at :udpated-at :links})

;; ----- Utility functions -----

(defn- clean
  "Remove any reserved properties from the user."
  [team]
  (apply dissoc team reserved-properties))

;; ----- Team CRUD -----

(schema/defn ^:always-validate ->team :- Team
  "
  Take a minimal map describing a team, the user-id of the initial admin, and an optional Slack org id
  and 'fill the blanks' with any missing properties.
  "
  ([team-props initial-admin] (->team team-props initial-admin nil))

  ([team-props initial-admin :- lib-schema/UniqueID slack-org :- (schema/maybe lib-schema/NonBlankString)] 
  {:pre [(map? team-props)]}
  (let [ts (db-common/current-timestamp)]
    (-> team-props
        keywordize-keys
        clean
        (assoc :team-id (db-common/unique-id))
        (update :name #(or % ""))
        (assoc :admins [initial-admin])
        (assoc :email-domains [])
        (update :slack-orgs #(or % []))
        (assoc :created-at ts)
        (assoc :updated-at ts)))))

(schema/defn ^:always-validate create-team!
  "Create a team in the system. Throws a runtime exception if user doesn't conform to the Team schema."
  [conn team :- Team]
  {:pre [(db-common/conn? conn)]}
  (db-common/create-resource conn table-name team (db-common/current-timestamp)))

(schema/defn ^:always-validate get-team :- (schema/maybe Team)
  "Given the team-id of the team, retrieve it from the database, or return nil if it don't exist."
  [conn team-id :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (db-common/read-resource conn table-name team-id))

(defn delete-team!
  "Given the team-id of the team, delete it and return `true` on success."
  [conn team-id]
  {:pre [(db-common/conn? conn)
         (schema/validate lib-schema/UniqueID team-id)]}
  ;; TODO remove team from users
  (try
    (db-common/delete-resource conn table-name team-id)
    (catch java.lang.RuntimeException e))) ; it's OK if there is no user to delete

;; ----- Team's set operations -----

(schema/defn ^:always-validate add-admin :- (schema/maybe Team)
  "
  Given the team-id of the team, and the user-id of the user, add the user as an admin of the team if it exists.
  Returns the updated team on success, nil on non-existence, and a RethinkDB error map on other errors.
  "
  [conn team-id :- lib-schema/UniqueID user-id :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (when-let* [team (get-team conn team-id)]
    (db-common/add-to-set conn table-name team-id "admins" user-id)))

(schema/defn ^:always-validate remove-admin :- (schema/maybe Team)
  "
  Given the team-id of the team, and the user-id of the user, remove the user as an admin of the team if it exists.
  Returns the updated team on success, nil on non-existence, and a RethinkDB error map on other errors.
  "
  [conn team-id :- lib-schema/UniqueID user-id :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (if-let [team (get-team conn team-id)]
    (db-common/remove-from-set conn table-name team-id "admins" user-id)))

(schema/defn ^:always-validate add-email-domain :- (schema/maybe Team)
  "
  Given the team-id of the team, and an email domain, add the email domain to the team if it exists.
  Returns the updated team on success, nil on non-existence, and a RethinkDB error map on other errors.
  "
  [conn team-id :- lib-schema/UniqueID email-domain :- lib-schema/NonBlankString]
  {:pre [(db-common/conn? conn)]}
  (if-let [team (get-team conn team-id)]
    (db-common/add-to-set conn table-name team-id "email-domains" email-domain)))

(schema/defn ^:always-validate remove-email-domain :- (schema/maybe Team)
  "
  Given the team-id of the team, and an email domain, remove the email domain from the team if it exists.
  Returns the updated team on success, nil on non-existence, and a RethinkDB error map on other errors.
  "
  [conn team-id :- lib-schema/UniqueID email-domain :- lib-schema/NonBlankString]
  {:pre [(db-common/conn? conn)]}
  (if-let [team (get-team conn team-id)]
    (db-common/remove-from-set conn table-name team-id "email-domains" email-domain)))

(schema/defn ^:always-validate add-slack-org :- (schema/maybe Team)
  "
  Given the team-id of the team, and a slack org, add the slack org to the team if it exists.
  Returns the updated team on success, nil on non-existence, and a RethinkDB error map on other errors.
  "
  [conn team-id :- lib-schema/UniqueID slack-org :- lib-schema/NonBlankString]
  {:pre [(db-common/conn? conn)]}
  (if-let [team (get-team conn team-id)]
    (db-common/add-to-set conn table-name team-id "slack-orgs" slack-org)))

(schema/defn ^:always-validate remove-slack-org :- (schema/maybe Team)
  "
  Given the team-id of the team, and a slack org, remove the slack org from the team if it exists.
  Returns the updated team on success, nil on non-existence, and a RethinkDB error map on other errors.
  "
  [conn team-id :- lib-schema/UniqueID slack-org :- lib-schema/NonBlankString]
  {:pre [(db-common/conn? conn)]}
  (if-let [team (get-team conn team-id)]
    (db-common/remove-from-set conn table-name team-id "slack-orgs" slack-org)))

;; ----- Collection of teams -----

(defn list-teams
  "List all teams, returning `team-id` and `name`. Additional fields can be optionally specified."
  ([conn]
  (list-teams conn []))

  ([conn additional-fields]
  {:pre [(db-common/conn? conn)
        (sequential? additional-fields)
        (every? #(or (string? %) (keyword? %)) additional-fields)]}
  (db-common/read-resources conn table-name (concat [:team-id :name] additional-fields))))

(defn get-teams
  "
  Get teams by a sequence of team-id's, returning `team-id` and `name`. 
  
  Additional fields can be optionally specified.
  "
  ([conn team-ids]
  (get-teams conn team-ids []))

  ([conn team-ids additional-fields]
  {:pre [(db-common/conn? conn)
         (schema/validate [lib-schema/UniqueID] team-ids)
         (sequential? additional-fields)
        (every? #(or (string? %) (keyword? %)) additional-fields)]}
  (db-common/read-resources-by-primary-keys conn table-name team-ids (concat [:team-id :name] additional-fields))))

(defn get-teams-by-slack-org
  "
  Get teams by a Slack org, returning `team-id` and `name`. 
  
  Additional fields can be optionally specified.
  "
  ([conn slack-org]
  (get-teams-by-slack-org conn slack-org []))

  ([conn slack-org additional-fields]
  {:pre [(db-common/conn? conn)
         (schema/validate lib-schema/NonBlankString slack-org)
         (sequential? additional-fields)
        (every? #(or (string? %) (keyword? %)) additional-fields)]}
  (db-common/read-resources conn table-name :slack-orgs slack-org (concat [:team-id :name] additional-fields))))

;; ----- Armageddon -----

(defn delete-all-teams!
  "Use with caution! Failure can result in partial deletes. Returns `true` if successful."
  [conn]
  {:pre [(db-common/conn? conn)]}
  (db-common/delete-all-resources! conn table-name))