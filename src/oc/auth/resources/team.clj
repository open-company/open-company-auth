(ns oc.auth.resources.team
  "Team stored in RethinkDB."
  (:require [clojure.walk :refer (keywordize-keys)]
            [if-let.core :refer (when-let*)]
            [schema.core :as schema]
            [oc.lib.db.common :as db-common]
            [oc.lib.schema :as lib-schema]
            [oc.auth.config :as c]))

;; ----- RethinkDB metadata -----

(def table-name "teams")
(def primary-key :team-id)

;; ----- Schema -----

(def Team {
  :team-id lib-schema/UniqueID
  :name schema/Str
  :admins [lib-schema/UniqueID]
  :email-domains [lib-schema/EmailDomain]
  :slack-orgs [lib-schema/NonBlankStr]
  (schema/optional-key :logo-url) (schema/maybe schema/Str)
  :created-at lib-schema/ISO8601
  :updated-at lib-schema/ISO8601})

(def EmailInviteRequest {
  :email lib-schema/EmailAddress
  :admin schema/Bool
  (schema/optional-key :org-name) (schema/maybe schema/Str)
  (schema/optional-key :logo-url) (schema/maybe schema/Str)
  (schema/optional-key :logo-width) (schema/maybe schema/Int)
  (schema/optional-key :logo-height) (schema/maybe schema/Int)
  (schema/optional-key :first-name) (schema/maybe schema/Str)
  (schema/optional-key :last-name) (schema/maybe schema/Str)
  (schema/optional-key :note) (schema/maybe schema/Str)})

(def SlackInviteRequest {
  :slack-id lib-schema/NonBlankStr
  :slack-org-id lib-schema/NonBlankStr
  :admin schema/Bool
  (schema/optional-key :org-name) (schema/maybe schema/Str)
  (schema/optional-key :logo-url) (schema/maybe schema/Str)
  (schema/optional-key :logo-width) (schema/maybe schema/Int)
  (schema/optional-key :logo-height) (schema/maybe schema/Int)
  (schema/optional-key :first-name) (schema/maybe schema/Str)
  (schema/optional-key :last-name) (schema/maybe schema/Str)
  (schema/optional-key :avatar-url) (schema/maybe schema/Str)
  (schema/optional-key :note) (schema/maybe schema/Str)
  (schema/optional-key :email) (schema/maybe lib-schema/EmailAddress)})

;; ----- Metadata -----

(def reserved-properties
  "Properties of a resource that can't be specified during a create and are ignored during an update."
  #{:team-id :admins :email-domains :slack-orgs :created-at :udpated-at :links})

;; ----- Utility functions -----

(defn clean
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

  ([team-props initial-admin :- lib-schema/UniqueID slack-org :- (schema/maybe lib-schema/NonBlankStr)] 
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
  "Create a team in the system. Throws a runtime exception if the team doesn't conform to the Team schema."
  [conn team :- Team]
  {:pre [(db-common/conn? conn)]}
  (db-common/create-resource conn table-name team (db-common/current-timestamp)))

(schema/defn ^:always-validate get-team :- (schema/maybe Team)
  "Given the team-id of the team, retrieve it, or return nil if it don't exist."
  [conn team-id :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (db-common/read-resource conn table-name team-id))

(schema/defn ^:always-validate update-team! :- Team
  "
  Given an updated team property map, update the team and return the updated team on success.

  Throws a runtime exception if the merge of the prior team and the updated team property map doesn't conform
  to the Team schema.
  
  NOTE: doesn't update admins, see: `add-admin`, `remove-admin`
  NOTE: doesn't update email domains, see: `add-email-domain`, `remove-email-domain`
  NOTE: doesn't update Slack orgs, see: `add-slack-org`, `remove-slack-org`
  NOTE: doesn't handle case of team-id change.
  "
  [conn team-id :- lib-schema/UniqueID team]
  {:pre [(db-common/conn? conn)
         (map? team)]}
  (if-let [original-team (get-team conn team-id)]
    (let [updated-team (merge original-team (clean team))]
      (schema/validate Team updated-team)
      (db-common/update-resource conn table-name primary-key original-team updated-team))))

(schema/defn ^:always-validate delete-team!
  "Given the team-id of the team, delete it and return `true` on success."
  [conn team-id :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (try
    (db-common/delete-resource conn table-name team-id)
    (catch java.lang.RuntimeException e))) ; it's OK if there is no team to delete

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

(defn allowed-email-domain? [email-domain]
  (not-any? #(= % email-domain) c/email-domain-blacklist))

(schema/defn ^:always-validate add-email-domain :- (schema/maybe Team)
  "
  Given the team-id of the team, and an email domain, add the email domain to the team if it exists.
  Returns the updated team on success, nil on non-existence, and a RethinkDB error map on other errors.
  "
  [conn team-id :- lib-schema/UniqueID email-domain :- lib-schema/NonBlankStr]
  {:pre [(db-common/conn? conn)
         (allowed-email-domain? email-domain)]}
  (if-let [team (get-team conn team-id)]
    (db-common/add-to-set conn table-name team-id "email-domains" email-domain)))

(schema/defn ^:always-validate remove-email-domain :- (schema/maybe Team)
  "
  Given the team-id of the team, and an email domain, remove the email domain from the team if it exists.
  Returns the updated team on success, nil on non-existence, and a RethinkDB error map on other errors.
  "
  [conn team-id :- lib-schema/UniqueID email-domain :- lib-schema/NonBlankStr]
  {:pre [(db-common/conn? conn)]}
  (if-let [team (get-team conn team-id)]
    (db-common/remove-from-set conn table-name team-id "email-domains" email-domain)))

(schema/defn ^:always-validate add-slack-org :- (schema/maybe Team)
  "
  Given the team-id of the team, and a slack org, add the slack org to the team if it exists.
  Returns the updated team on success, nil on non-existence, and a RethinkDB error map on other errors.
  "
  [conn team-id :- lib-schema/UniqueID slack-org :- lib-schema/NonBlankStr]
  {:pre [(db-common/conn? conn)]}
  (if-let [team (get-team conn team-id)]
    (db-common/add-to-set conn table-name team-id "slack-orgs" slack-org)))

(schema/defn ^:always-validate remove-slack-org :- (schema/maybe Team)
  "
  Given the team-id of the team, and a slack org, remove the slack org from the team if it exists.
  Returns the updated team on success, nil on non-existence, and a RethinkDB error map on other errors.
  "
  [conn team-id :- lib-schema/UniqueID slack-org :- lib-schema/NonBlankStr]
  {:pre [(db-common/conn? conn)]}
  (if-let [team (get-team conn team-id)]
    (db-common/remove-from-set conn table-name team-id "slack-orgs" slack-org)))

;; ----- Collection of teams -----

(defn list-teams
  "List all teams, returning `team-id` and `name`. Additional fields can be optionally specified."
  ([conn]
  (list-teams conn []))

  ([conn additional-keys]
  {:pre [(db-common/conn? conn)
        (sequential? additional-keys)
        (every? #(or (string? %) (keyword? %)) additional-keys)]}
  (db-common/read-resources conn table-name (concat [:team-id :name] additional-keys))))

(defn list-teams-by-ids
  "
  Get teams by a sequence of team-id's, returning `team-id` and `name`. 
  
  Additional fields can be optionally specified.
  "
  ([conn team-ids]
  (list-teams-by-ids conn team-ids []))

  ([conn team-ids additional-keys]
  {:pre [(db-common/conn? conn)
         (schema/validate [lib-schema/UniqueID] team-ids)
         (sequential? additional-keys)
         (every? #(or (string? %) (keyword? %)) additional-keys)]}
  (if (empty? team-ids)
    []
    (db-common/read-resources-by-primary-keys conn table-name team-ids (concat [:team-id :name] additional-keys)))))

(defn list-teams-by-index
  "
  Given the name of a secondary index and a value, retrieve all matching teams
  as a sequence of maps with team-id, and names.
  
  Secondary indexes:
  :slack-orgs
  :admins
  :email-domains

  Note: if additional-keys are supplied, they will be included in the map, and only teams
  containing those keys will be returned.
  "
  ([conn index-key index-value]
  (list-teams-by-index conn index-key index-value []))

  ([conn index-key index-value additional-keys]
  {:pre [(db-common/conn? conn)
         (or (keyword? index-key) (string? index-key))
         (sequential? additional-keys)
         (every? #(or (string? %) (keyword? %)) additional-keys)]}

  (->> (into [primary-key :name] additional-keys)
    (db-common/read-resources conn table-name index-key index-value)
    vec)))

;; ----- Armageddon -----

(defn delete-all-teams!
  "Use with caution! Failure can result in partial deletes. Returns `true` if successful."
  [conn]
  {:pre [(db-common/conn? conn)]}
  (db-common/delete-all-resources! conn table-name))