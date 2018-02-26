(ns oc.auth.resources.maintenance
  "fns to maintain auth resources."
  (:require [schema.core :as schema]
            [taoensso.timbre :as timbre]
            [oc.lib.db.common :as db-common]
            [oc.lib.schema :as lib-schema]
            [oc.auth.resources.user :as user-res]
            [oc.auth.resources.slack-org :as slack-org-res]
            [oc.auth.resources.team :as team-res]))

(schema/defn ^:always-validate delete-team!
  "Use with caution! Failure can result in partial deletes. Returns `true` if successful."
  [conn team-id :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  ;; Enumerate the users for this team
  (when-let [team (team-res/get-team conn team-id)]
    (let [team-users (user-res/list-users conn team-id)]
      (doseq [team-user team-users]
        (if (< (count (:teams team-user)) 2)
          ;; User's only team, remove them
          (do
            (timbre/info "Deleting user:" (:user-id team-user) (:email team-user))
            (user-res/delete-user! conn (:user-id team-user)))
          ;; Just remove them from this team
          (do
            (timbre/info "Removing team from user:" (:user-id team-user) (:email team-user))
            (user-res/remove-team conn (:user-id team-user) team-id)))))
    ;; Enumerate the Slack orgs for this team
    (doseq [slack-org-id (:slack-orgs team)]
      (when (< (count (team-res/list-teams-by-index conn :slack-orgs slack-org-id)) 2)
        (timbre/info "Deleting Slack org:" slack-org-id)
        (slack-org-res/delete-slack-org! conn slack-org-id)))
    ;; Delete the team
    (timbre/info "Deleting team:" team-id)
    (team-res/delete-team! conn team-id)))

;; ----- Armageddon -----

(defn delete-all-teams!
  "Use with caution! Failure can result in partial deletes. Returns `true` if successful."
  [conn]
  {:pre [(db-common/conn? conn)]}
  ;; Delete all Slack orgs, users and teams
  (db-common/delete-all-resources! conn slack-org-res/table-name)
  (db-common/delete-all-resources! conn user-res/table-name)
  (db-common/delete-all-resources! conn team-res/table-name))