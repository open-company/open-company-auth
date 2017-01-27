(ns oc.auth.api.slack
  "Liberator API for Slack callback to auth service."
  (:require [if-let.core :refer (if-let*)]
            [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes GET OPTIONS)]
            [ring.util.response :refer (redirect)]
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
  (dissoc slack-user :bot :name :slack-id :slack-org-id :slack-token :slack-org-name))

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
  (timbre/info "TODO: Refreshing user from Slack:" (:email slack-user) (:name slack-user))
  existing-user))

;; ----- Slack Request Handling Functions -----

(defn- redirect-to-web-ui
  "Send them back to the UI login page with a JWT token or a reason they don't have one."
  [success? jwt-or-reason]
  (let [param (if success? "jwt" "access")]
    (redirect (str config/ui-server-url "/login?" param "=" jwt-or-reason))))

(defn- slack-callback
  "Redirect browser to web UI after callback from Slack."
  [conn params]
  (if (get params "test")
    ;; Just a test
    (api-common/json-response {:test true :ok true} 200)
    ;; This is NOT a test
    (if-let [slack-user (slack/oauth-callback params)]
      ;; got an auth'd user back from Slack
      (let [slack-org (slack-org-res/get-slack-org conn (:slack-org-id slack-user))
            new-slack-org (when-not slack-org (create-slack-org-for conn slack-user))
            user (user-res/get-user-by-email conn (:email slack-user)) ; user already exists?
            new-user (when-not user (user-res/->user (clean-slack-user slack-user)))
            teams (team-res/get-teams-by-slack-org conn (:slack-org-id slack-user)) ; team(s) already exist?
            new-team (when (empty? teams) (create-team-for conn slack-user (or (:user-id user) (:user-id new-user))))
            user-teams (if (empty? teams) [new-team] teams)
            updated-user (if user
                            (update-user conn slack-user user user-teams)
                            (create-user-for conn new-user user-teams))
            jwtoken (jwt/generate (-> updated-user
                                    (clean-user)
                                    (assoc :auth-source :slack)
                                    (assoc :slack-id (:slack-id slack-user))
                                    (assoc :slack-token (:slack-token slack-user)))
                      config/passphrase)]
        (redirect-to-web-ui true jwtoken))

      ;; no user came back from Slack
      (redirect-to-web-ui false "failed"))))

(defn refresh-token
  "Handle request to refresh an expired Slack JWToken by checking if the access token is still valid with Slack."
  [conn {user-id :user-id :as user} slack-id slack-token]
  (timbre/info "Refresh token request for user" user-id "with slack id of" slack-id "and access token" slack-token)
  (if (slack/valid-access-token? slack-token)
    (do
      (timbre/info "Refreshing Slack user" slack-id)
      (try
        (let [slack-user (slack/get-user-info slack-token config/slack-user-scope slack-id)
              updated-user (update-user conn slack-user user)]
          ;; Respond w/ JWToken and location
          (user-rep/auth-response (-> updated-user
                                    (clean-user)
                                    (assoc :slack-id (:slack-id slack-user))
                                    (assoc :slack-token slack-token))
            :slack))
        (catch Exception e
          (timbre/error e)
          (timbre/warn "Unable to swap access token" slack-token "for user" user-id)
          (api-common/error-response "Could note confirm token." 400))))
    (do
      (timbre/warn "Invalid access token" slack-token "for user" user-id)
      (api-common/error-response "Could note confirm token." 400))))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      (OPTIONS "/slack/auth" {params :params} (pool/with-pool [conn db-pool] (slack-callback conn params)))
      (GET "/slack/auth" {params :params} (pool/with-pool [conn db-pool] (slack-callback conn params)))))) 