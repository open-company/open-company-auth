(ns oc.auth.api.slack
  "Liberator API for Slack callback to auth service."
  (:require [defun.core :refer (defun-)]
            [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes GET OPTIONS)]
            [ring.util.response :as response]
            [oc.lib.db.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.lib.jwt :as jwt]
            [oc.auth.lib.slack :as slack]
            [oc.auth.config :as config]
            [oc.auth.resources.team :as team-res]
            [oc.auth.resources.user :as user-res]
            [oc.auth.resources.slack-org :as slack-org-res]
            [oc.auth.representations.user :as user-rep]))

;; ----- Utility Functions -----

(defn- clean-slack-user
  "Remove properties from a Slack user that are not needed for a persisted user."
  [slack-user]
  (dissoc slack-user :bot :name :slack-id :slack-org-id :slack-token :slack-org-name :team-id :redirect))

(defn- clean-user
  "Remove properties from a user that are not needed for a JWToken."
  [user]
  (dissoc user :created-at :updated-at :status))

(defun- bot-for
  "
  Given a Slack org resource, return the bot properties suitable for use in a JWToken, or nil if there's no bot
  for the Slack org.

  Or, given a map of Slack orgs to their bots, and a sequence of Slack orgs, return a sequence of bots.
  "
  ;; Single Slack org case
  ([slack-org]
  (when (and (:bot-user-id slack-org) (:bot-token slack-org))
    ;; Extract and rename the keys for JWToken use
    (select-keys
      (clojure.set/rename-keys slack-org {:bot-user-id :id :bot-token :token})
      [:slack-org-id :id :token])))

  ;; Empty case, no more Slack orgs
  ([_bots _slack-orgs :guard empty? results :guard empty?] nil)
  ([_bots _slack-orgs :guard empty? results] (remove nil? results))

  ;; Many Slack orgs case, recursively get the bot for each org one by one
  ([bots slack-orgs results]
  (bot-for bots (rest slack-orgs) (conj results (get bots (first slack-orgs))))))

(defn bots-for
  "Given a user, return a map of configured bots for each of the user's teams, keyed by team-id."
  [conn {team-ids :teams}]
  (let [teams (team-res/list-teams-by-ids conn team-ids [:slack-orgs]) ; teams the user is a member of
        teams-with-slack (remove #(empty? (:slack-orgs %)) teams) ; teams with a Slack org
        slack-org-ids (distinct (flatten (map :slack-orgs teams-with-slack))) ; distinct Slack orgs
        slack-orgs (slack-org-res/list-slack-orgs-by-ids conn slack-org-ids [:bot-user-id :bot-token]) ; bot lookup
        bots (remove nil? (map bot-for slack-orgs)) ; remove slack orgs with no bots
        slack-org-to-bot (zipmap (map :slack-org-id bots) bots) ; map of slack org to its bot
        team-to-slack-orgs (zipmap (map :team-id teams-with-slack)
                                   (map :slack-orgs teams-with-slack)) ; map of team to its Slack org(s)
        team-to-bots (zipmap (keys team-to-slack-orgs)
                             (map #(bot-for slack-org-to-bot % []) (vals team-to-slack-orgs)))] ; map of team to bot(s)
    (into {} (remove (comp empty? second) team-to-bots)))) ; remove any team with no bots

;; ----- Actions -----

(defn- create-slack-org-for
  "Create a new Slack org for the specified Slack user."
  [conn {slack-org-id :slack-org-id :as slack-user}]
  (timbre/info "Creating new Slack org for:" slack-org-id)
  (slack-org-res/create-slack-org! conn 
    (slack-org-res/->slack-org (select-keys slack-user [:slack-org-id :slack-org-name :bot]))))

(defn- update-slack-org-for
  "Update the existing Slack org for the specified Slack user."
  [conn slack-user {slack-org-id :slack-org-id :as existing-slack-org}]
  (timbre/info "Updating Slack org:" slack-org-id)
  (let [updated-slack-org (merge existing-slack-org (select-keys slack-user [:slack-org-name :bot]))]
    (slack-org-res/update-slack-org! conn slack-org-id updated-slack-org)))

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
    (timbre/info "Updating user:" (:user-id updated-user))
    (user-res/update-user! conn (:user-id updated-user) updated-user))))

;; ----- Slack Request Handling Functions -----

(defn- redirect-to-web-ui
  "Send them back to a UI page with an access description ('team', 'bot' or 'failed') and a JWToken."
  ([redirect access] (redirect-to-web-ui redirect access nil))
  
  ([redirect access jwtoken]
  (let [page (or redirect "/login")
        jwt-param (if jwtoken (str "&jwt=" jwtoken) "")
        url (str config/ui-server-url page "?access=" (name access))]
    (timbre/info "Redirecting request to:" url)
    (response/redirect (str url jwt-param)))))

(defn- slack-callback
  "Redirect browser to web UI after callback from Slack."
  [conn params]
  (let [slack-response (slack/oauth-callback params) ; process the response from Slack
        team-id (:team-id slack-response) ; a team-id is present if the bot or Slack org is being added to existing team
        user-id (:user-id slack-response) ; a user-id is present if a Slack org is being added to an existing team
        redirect (:redirect slack-response)] ; where we redirect the browser back to
    (if-let [slack-user (when-not (:error slack-response) slack-response)]
      ;; got an auth'd user back from Slack
      (let [existing-user (if user-id
                            (user-res/get-user conn user-id)
                            (user-res/get-user-by-email conn (:email slack-user))) ; user already exists?
            new-user (when-not existing-user (user-res/->user (clean-slack-user slack-user))) ; create a new user map
            existing-team (when team-id (team-res/get-team conn team-id))
            existing-slack-org (slack-org-res/get-slack-org conn (:slack-org-id slack-user)) ; existing Slack org?
            slack-org (if existing-slack-org
                          (update-slack-org-for conn slack-user existing-slack-org) ; update the Slack org
                          (create-slack-org-for conn slack-user)) ; create new Slack org
            teams (if team-id
                    ;; The team this Slack org or Slack bot is being added to
                    (team-res/list-teams-by-ids conn [team-id])
                    ;; Do team(s) already exist for this Slack org?
                    (team-res/list-teams-by-index conn :slack-orgs (:slack-org-id slack-user)))
            new-team (when (and new-user (empty? teams))
                      (create-team-for conn slack-user (:user-id new-user))) ; create a new team
            user-teams (if (empty? teams) [new-team] teams)
            user (or existing-user (create-user-for conn new-user user-teams)) ; create new user
            jwt-user (user-rep/jwt-props-for (-> user
                                                (clean-user)
                                                (assoc :admin (user-res/admin-of conn (:user-id user)))
                                                (assoc :slack-id (:slack-id slack-user))
                                                (assoc :slack-token (:slack-token slack-user))) :slack)
            bot-only? (and existing-team ((set (:slack-orgs existing-team)) (:slack-org-id slack-org)))
            redirect-arg (if bot-only? :bot :team)]
        ;; Add the Slack org to the existing team if needed
        (when (and existing-team (not bot-only?))
          (team-res/add-slack-org conn team-id (:slack-org-id slack-org)))
        ;; All done, send them back to the OC Web UI with a JWToken
        (redirect-to-web-ui redirect redirect-arg
          (jwt/generate (assoc jwt-user :slack-bots (bots-for conn jwt-user)) config/passphrase)))

      ;; Error came back from Slack, send them back to the OC Web UI
      (redirect-to-web-ui redirect :failed))))

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
                                  (assoc :slack-token slack-token)
                                  (assoc :slack-bots (bots-for conn updated-user)))
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