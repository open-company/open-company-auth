(ns oc.auth.api.slack
  "Liberator API for Slack callback to auth service."
  (:require [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes GET OPTIONS)]
            [ring.util.response :refer (redirect)]
            [oc.lib.rethinkdb.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.lib.jwt :as jwt]
            [oc.auth.lib.slack :as slack]
            [oc.auth.config :as config]
            [oc.auth.resources.team :as team-res]
            [oc.auth.resources.user :as user-res]))

;; ----- Utility Functions -----

(defn- clean-slack-user [slack-user]
  (dissoc slack-user :bot :name :slack-org-id :user-token :slack-org-name))

;; ----- Actions -----

(defn- create-team-for [conn {slack-org-id :slack-org-id team-name :slack-org-name} admin-id]
  (timbre/info "Creating new team for Slack org:" slack-org-id team-name)
  ;; TODO capture bot
  (if-let [team (team-res/create-team! conn (team-res/->team {:name team-name} admin-id))]
    (team-res/add-slack-org conn (:team-id team) slack-org-id)))

(defn- create-user-for [conn new-user teams]
  (timbre/info "Creating new user:" (:email new-user) (:first-name new-user) (:last-name new-user))
  (user-res/create-user! conn (-> new-user
                                (assoc :status :active)
                                (assoc :teams (map :team-id teams)))))

(defn- update-user [conn slack-user existing-user teams]
  (timbre/info "TODO: Refreshing user from Slack:" (:email slack-user) (:name slack-user))
  existing-user)

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
      (let [user (user-res/get-user-by-email conn (:email slack-user)) ; user already exists?
            new-user (when-not user (user-res/->user (clean-slack-user slack-user)))
            teams (team-res/get-teams-by-slack-org conn (:slack-org-id slack-user)) ; team(s) already exist?
            new-team (when (empty? teams) (create-team-for conn slack-user (or (:user-id user) (:user-id new-user))))
            user-teams (if (empty? teams) [new-team] teams)
            updated-user (if user
                            (update-user conn slack-user user user-teams)
                            (create-user-for conn new-user user-teams))
            jwtoken (jwt/generate updated-user config/passphrase)]
        (redirect-to-web-ui true jwtoken))

      ;; no user came back from Slack
      (redirect-to-web-ui false "failed"))))

; (defn- refresh-slack-token
;   "Handle request to refresh an expired Slack JWToken by checking if the access token is still valid with Slack."
;   [req]
;   (if-let* [token    (jwt/read-token (:headers req))
;             decoded  (jwt/decode token)
;             user-id  (-> decoded :claims :user-id)
;             user-tkn (-> decoded :claims :user-token)
;             org-id   (-> decoded :claims :org-id)]
;     (do (timbre/info "Refresh token request for user" user-id "of org" org-id)
;       (if (slack/valid-access-token? user-tkn)
;         (do
;           (timbre/info "Refreshing token" user-id)
;           (ring/text-response (jwt/generate (merge (:claims decoded)
;                                                    (store/retrieve org-id)
;                                                    {:auth-source "slack"})) 200))
;         (do
;           (timbre/warn "Invalid access token for" user-id)            
;           (ring/error-response "Could note confirm token." 400))))
;     (do
;       (timbre/warn "Bad refresh token request")      
;       (ring/error-response "Could note confirm token." 400))))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      (OPTIONS "/slack/auth" {params :params} (pool/with-pool [conn db-pool] (slack-callback conn params)))
      (GET "/slack/auth" {params :params} (pool/with-pool [conn db-pool] (slack-callback conn params)))))) 