(ns oc.auth.api.slack
  "Liberator API for Slack callback to auth service."
  (:require [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes GET OPTIONS)]
            [ring.util.response :as response]
            [oc.lib.rethinkdb.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.lib.jwt :as jwt]
            [oc.auth.lib.slack :as slack]
            [oc.auth.config :as config]
            [oc.auth.resources.team :as team-res]
            [oc.auth.resources.slack-org :as slack-org-res]
            [oc.auth.resources.user :as user-res]
            [oc.auth.representations.user :as user-rep]))

;; ----- Utility Functions -----

(defn- clean-slack-user
  "Remove properties from a Slack user that are not needed for a persisted user."
  [slack-user]
  (dissoc slack-user :bot :name :slack-id :slack-org-id :slack-token :slack-org-name :team-id))

(defn- clean-user
  "Remove properties from a user that are not needed for a JWToken."
  [user]
  (dissoc user :created-at :updated-at :status))

;; ----- Actions -----

(defn- create-slack-org-for
  "Create a new Slack org for the specified Slack user."
  [conn {slack-org-id :slack-org-id :as slack-user}]
  (timbre/info "Creating new Slack org for:" slack-org-id)
  (slack-org-res/create-slack-org! conn 
    (slack-org-res/->slack-org (select-keys slack-user [:slack-org-id :slack-org-name :bot]))))

(defn- create-team-for
  "Create a new team for the specified Slack user."
  [conn {slack-org-id :slack-org-id team-name :slack-org-name} admin-id]
  (timbre/info "Creating new team for Slack org:" slack-org-id team-name)
  (if-let [team (team-res/create-team! conn (team-res/->team {:name team-name} admin-id))]
    (team-res/add-slack-org conn (:team-id team) slack-org-id)))

(defn- create-user-for
  "Create a new user for the specified Slack user."
  [conn new-user teams]
  (timbre/info "Creating new user:" (:email new-user) (:first-name new-user) (:last-name new-user))
  (user-res/create-user! conn (-> new-user
                                (assoc :status :active)
                                (assoc :teams (map :team-id teams)))))

(defn- update-user
  "Update the existing user from their Slack user profile."
  ([conn slack-user existing-user] (update-user conn slack-user existing-user (:teams existing-user)))

  ([conn slack-user existing-user teams]
  (let [updated-user (merge existing-user (dissoc (clean-slack-user slack-user) :user-id))]
    (timbre/info "Updating user " (:user-id updated-user))
    (user-res/update-user! conn (:user-id updated-user) updated-user))))

;; ----- Slack Request Handling Functions -----

(defn- redirect-to-web-ui
  "Send them back to a UI page with a JWT token or a reason they don't have one."
  [team-id success? param-value]
  (let [page (if team-id (str "/" team-id "/settings/user-management") "/login")
        param (if (and (not team-id) success?) "jwt" "access")
        url (str config/ui-server-url page "?" param "=" param-value)]
    (response/redirect url)))

(defn- slack-callback
  "Redirect browser to web UI after callback from Slack."
  [conn params]
  (let [slack-response (slack/oauth-callback params) ; process the response from Slack
        team-id (:team-id slack-response) ; team-id in the response?
        slack-org-only? (when team-id true)] ; a team-id means we are just adding a Slack org to an existing team
    (if-let [slack-user (when-not (:error slack-response) slack-response)]
      ;; got an auth'd user back from Slack
      (let [existing-slack-org (slack-org-res/get-slack-org conn (:slack-org-id slack-user)) ; existing Slack org?
            slack-org (or existing-slack-org (create-slack-org-for conn slack-user)) ; create new Slack org
            user (when-not slack-org-only?
              (user-res/get-user-by-email conn (:email slack-user))) ; user already exists?
            new-user (when-not (or slack-org-only? user)
              (user-res/->user (clean-slack-user slack-user))) ; create a new user map
            teams (if team-id
                     ;; The team this Slack org is being added to
                     (team-res/get-teams conn [team-id])
                    ;; Do team(s) already exist for this Slack org?
                    (team-res/get-teams-by-index conn :slack-orgs (:slack-org-id slack-user)))
            new-team (when (and (not slack-org-only?)
                                (empty? teams))
                      (create-team-for conn slack-user (or (:user-id user) (:user-id new-user)))) ; create a new team
            user-teams (if (empty? teams) [new-team] teams)
            updated-user (when-not slack-org-only?
                            (if user
                              (update-user conn slack-user user user-teams) ; update user's teams
                              (create-user-for conn new-user user-teams))) ; create new user
            jwt-user (when-not slack-org-only? (user-rep/jwt-props-for
                                                  (-> updated-user
                                                    (clean-user)
                                                    (assoc :admin (user-res/admin-of conn (:user-id updated-user)))
                                                    (assoc :slack-id (:slack-id slack-user))
                                                    (assoc :slack-token (:slack-token slack-user))) :slack))
            redirect-arg (if slack-org-only? "true" (jwt/generate jwt-user config/passphrase))]
        ;; Add the Slack org to the existing team if needed
        (when (and team-id slack-org)
          (team-res/add-slack-org conn team-id (:slack-org-id slack-org)))
        ;; All done, send them back to the OC Web UI
        (redirect-to-web-ui team-id true redirect-arg))

      ;; Error came back from Slack, send them back to the OC Web UI
      (redirect-to-web-ui team-id false "failed"))))

(defn refresh-token
  "Handle request to refresh an expired Slack JWToken by checking if the access token is still valid with Slack."
  [conn {user-id :user-id :as user} slack-id slack-token]
  (timbre/info "Refresh token request for user" user-id "with slack id of" slack-id "and access token" slack-token)
  (if-let [slack-user (slack/valid-access-token? slack-token)]
    (do
      (timbre/info "Refreshing Slack user" slack-id)
      (let [updated-user (update-user conn slack-user (dissoc user :admin))]
        ;; Respond w/ JWToken and location
        (user-rep/auth-response (-> updated-user
                                  (clean-user)
                                  (assoc :admin (:admin user))
                                  (assoc :slack-id (:slack-id slack-user))
                                  (assoc :slack-token slack-token))
          :slack)))
    (do
      (timbre/warn "Invalid access token" slack-token "for user" user-id)
      (api-common/error-response "Could note confirm token." 400))))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      (OPTIONS "/slack/auth" {params :params} (pool/with-pool [conn db-pool] (slack-callback conn params)))
      (GET "/slack/auth" {params :params} (pool/with-pool [conn db-pool] (slack-callback conn params)))))) 