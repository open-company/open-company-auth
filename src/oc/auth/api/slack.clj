(ns oc.auth.api.slack
  "Liberator API for Slack callback to auth service."
  (:require [defun.core :refer (defun-)]
            [if-let.core :refer (if-let* when-let*)]
            [clojure.set :as clj-set]
            [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (GET OPTIONS)]
            [ring.util.response :as response]
            [clojure.string :as s]
            [oc.lib.db.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.lib.slack :as slack-lib]
            [oc.lib.jwt :as lib-jwt]
            [oc.auth.lib.jwtoken :as jwtoken]
            [oc.auth.lib.slack :as slack]
            [oc.auth.lib.sqs :as sqs]
            [oc.lib.sentry.core :as sentry]
            [oc.auth.config :as config]
            [oc.auth.async.notification :as notification]
            [oc.auth.async.slack-api-calls :as slack-api-calls]
            [oc.auth.resources.team :as team-res]
            [oc.auth.resources.user :as user-res]
            [oc.auth.resources.slack-org :as slack-org-res]
            [oc.auth.representations.user :as user-rep]))

;; ----- Utility Functions -----

(defn- clean-slack-user
  "Remove properties from a Slack user that are not needed for a persisted user."
  [slack-user]
  (dissoc slack-user :bot :name :slack-id :slack-org-id :slack-token :slack-org-name :display-name
                     :team-id :logo-url :slack-domain :redirect :state-slack-org-id :error
                     :state :redirectURI))

(defn- clean-user
  "Remove properties from a user that are not needed for a JWToken."
  [user]
  (dissoc user :created-at :updated-at :activated-at :status))

(defn- email-for
  "Get the email for the Slack user with users.info if we don't already have it."
  [{slack-token :slack-token email :email slack-user-id :slack-id}]
  (let [response (when-not email (slack-lib/get-user-info slack-token slack-user-id))] ; get users.info if we need it
    (or email ; return it if we already had it
        (:email response))))

;; ----- Actions -----

(defn- create-slack-org-for
  "Create a new Slack org for the specified Slack user."
  [conn {slack-org-id :slack-org-id :as slack-user}]
  (timbre/info "Creating new Slack org for:" slack-org-id)
  (slack-org-res/create-slack-org! conn 
    (slack-org-res/->slack-org (select-keys slack-user [:slack-org-id :slack-org-name :logo-url :slack-domain :bot]))))

(defn- update-slack-org-for
  "Update the existing Slack org for the specified Slack user."
  [conn slack-user {slack-org-id :slack-org-id logo-url :logo-url slack-domain :slack-domain
    :as existing-slack-org}]
  (timbre/info "Updating Slack org:" slack-org-id)
  (let [updated-logo-url (or (:logo-url slack-user) logo-url)
        updated-slack-domain (or (:slack-domain slack-user) slack-domain)
        updated-slack-org (merge existing-slack-org (select-keys slack-user [:slack-org-name :bot]))
        updated-team-info (merge updated-slack-org {:logo-url updated-logo-url
                                                    :slack-domain updated-slack-domain})]
    (slack-org-res/update-slack-org! conn slack-org-id updated-team-info)))

(defn- create-team-for
  "Create a new team for the specified Slack user."
  [conn {slack-org-id :slack-org-id team-name :slack-org-name logo-url :logo-url} admin-id]
  (timbre/info "Creating new team for Slack org:" slack-org-id team-name)
  (when-let* [team-name {:name team-name}
              team-map (if logo-url (assoc team-name :logo-url logo-url) team-name)
              team (team-res/create-team! conn (team-res/->team team-map admin-id))]
    (team-res/add-slack-org conn (:team-id team) slack-org-id)))

(defn- create-user-for
  "Create a new user for the specified Slack user."
  [conn new-user teams slack-org]
  (timbre/info "Creating new user:" (:email new-user) (:first-name new-user) (:last-name new-user))
  (let [new-user-digest (if (contains? slack-org :bot-token)
                          (merge new-user {:digest-medium :slack
                                           :notification-medium :slack
                                           :reminder-medium :slack})
                          new-user)
        user (user-res/create-user! conn (-> new-user-digest
                                          (assoc :status :active)
                                          (assoc :teams (map :team-id teams))))]
    (notification/send-trigger! (notification/->trigger user))
    user))

(defun- add-teams 
  "Recursive function to add team access to the user"

  ;; All done
  ([_conn existing-user _additional-teams :guard empty?] existing-user)
  
  ;; Add the team and recurse
  ([conn existing-user additional-teams]
    (let [user-id (:user-id existing-user)
          team-id (first additional-teams)]
      (timbre/info "Adding acces to team:" team-id "to user:" user-id)
      (add-teams conn (user-res/add-team conn user-id team-id) (rest additional-teams)))))

;; ----- Slack Request Handling Functions -----

(defn- redirect-to-web-ui
  "Send them back to a UI page with an access description ('team', 'bot' or 'failed') and a JWToken."
  ([redirect-origin redirect access]
    (redirect-to-web-ui redirect-origin redirect access nil :not-a-new-user)) ; nil = no jwtoken

  ([redirect-origin redirect access jwtoken last-token-at]
  (let [page (or redirect "/login")
        jwt-param (if jwtoken (str "&jwt=" jwtoken) "")
        param-concat (if (.contains page "?") "&" "?")
        url (str (or redirect-origin config/ui-server-url) page param-concat "access=" (name access) "&new=" (if last-token-at false true))]
    (timbre/info "Redirecting request to:" url)
    (response/redirect (str url jwt-param)))))

(defn- update-user-avatar-if-needed [old-user-avatar slack-user-avatar]
  (if (and (or (s/starts-with? old-user-avatar "/img/ML/")
               (s/blank? old-user-avatar))
           (not (s/blank? slack-user-avatar)))
    slack-user-avatar
    old-user-avatar))

(defn- slack-callback-step
  "
  First step of slack oauth, if the user is authing and the team has no bot installed we redirect him to the
  bot add sequence directly, if not we redirect to the web UI with the auth response.
  "
  [conn {:keys [team-id user-id redirect-origin redirect error] :as response}]
  (timbre/infof "Slack callback user-id %s team-id %s redirect %s redirect-origin %s error %s" user-id team-id redirect redirect-origin error)
  (timbre/trace "Slack response" response)
  (if-let* [slack-response (when-not (or error (false? (first response))) response)
            email (email-for slack-response)
            slack-user* (assoc slack-response :email email)]
      ;; got an auth'd user back from Slack
      (let [_ (timbre/debugf "User email from Slack %s" email)
            slack-user (update slack-user* :display-name #(if (s/blank? %) "-" %))
            ;; Get the existing slack org if present
            existing-slack-org (slack-org-res/get-slack-org conn (:slack-org-id slack-user)) ; existing Slack org?
            _ (timbre/debugf "Existing Slack org %s" (:slack-org-id existing-slack-org))
            ;; Get existing teams for auth sequence
            target-team (when team-id (team-res/get-team conn team-id)) ; OC team that Slack is being added to
            _ (when target-team (timbre/infof "Slack auth with target-team: %s" target-team))
            slack-org (if existing-slack-org
                        (update-slack-org-for conn slack-user existing-slack-org) ; update the Slack org
                        (create-slack-org-for conn slack-user)) ; create new Slack org
            _ (timbre/debugf "Slack org %s" (:slack-org-id slack-org))
            adding-slack-bot? (and (not (-> existing-slack-org :bot-token seq)) ;; Bot token was not present in org data
                                   (-> slack-org :bot-token seq))       ;; But not it's there
            _ (timbre/debugf "Adding Slack bot? %s" adding-slack-bot?)
            user-from-user-id (when user-id (user-res/get-user conn user-id))
            _ (timbre/debugf "OC user from request ID: %s" (:user-id user-from-user-id))
            user-from-slack-id (user-res/get-user-by-slack-id conn (:slack-org-id slack-user) (:slack-id slack-user))
            _ (timbre/debugf "OC user from Slack: %s" (:user-id user-from-slack-id))
            user-from-email (when (and (not user-from-user-id)
                                       (not user-from-slack-id))
                              (user-res/get-user-by-email conn email))
            _ (timbre/debugf "OC user from email: %s" (:user-id user-from-email))

            do-not-assoc-slack-user (and user-id user-from-user-id
                                         (not user-from-slack-id)
                                         (not= (:email user-from-user-id) email))
            _ (timbre/debugf "Avoid Slack user link to OC user? %s" do-not-assoc-slack-user)
            ;; Get existing user by user ID, slack id or by email
            existing-user (or user-from-user-id
                              user-from-slack-id
                              user-from-email)
            _ (timbre/infof "Existing OC user: %s" (:user-id existing-user))

            _error (when (and user-id (not existing-user)) ; shouldn't have a Slack org being done by non-existent user
                     (timbre/errorf "No user found for user-id %s during auth of Slack org/user ID %s / %s" user-id (:slack-org-id slack-user) (:slack-id slack-user)))
            scope (slack/token-scope slack-response)
            updated-slack-user (if (:slack-token slack-user)
                                 (slack/get-user-info (:slack-token slack-user) scope (or (:slack-org-id existing-slack-org) (:slack-org-id slack-user)))
                                 slack-user)
            ;; Get user Slack profile
            user-profile (if (:bot-token slack-org)
                           (merge updated-slack-user (slack/user-profile (:bot-token slack-org) (:email slack-user)))
                           updated-slack-user)

            ;; Clean the Slack user properties to merge into the Carrot user if needed
            cleaned-user-props (clean-slack-user (merge slack-user user-profile))
            ;; Create a new user map if we don't have an existing user
            new-user (when-not existing-user (user-res/->user cleaned-user-props))
            _ (when-not existing-user (timbre/infof "New user crated from Slack org %s and Slack user %s = %s" (:slack-org-id slack-user) (:slack-id slack-user) (:user-id new-user)))

            ;; Get the relevant teams
            relevant-teams (if team-id ; if we're adding a Slack org to an existing OC team
                              ;; The team this Slack org or Slack bot is being added to
                              [target-team]
                              ;; Do team(s) already exist for this Slack org?
                              (team-res/list-teams-by-index conn :slack-orgs (:slack-org-id slack-user)))
            _ (when-not (empty? relevant-teams) (timbre/debugf "Relevant teams: %s" (mapv :team-id relevant-teams)))
            
            create-new-team? (and new-user (empty? relevant-teams))
            ;; Create a new team if we're creating a new user and have no team(s) already for this Slack org
            new-team (when create-new-team?
                       (create-team-for conn (assoc slack-user :logo-url (slack/logo-url-from-response slack-response)) (:user-id new-user)))
            _ (when create-new-team? (timbre/infof "New team crated from Slack org %s = %s" (:slack-org-id slack-user) (:team-id new-team)))
            
            ;; Final set of teams relevant to this Slack org
            teams (if new-team [new-team] relevant-teams)

            ;; Add additional teams to the existing user if their Slack org gives them access to more teams
            existing-team-ids (when existing-user (set (:teams existing-user))) ; OC teams the user has acces to now
            relevant-team-ids (when existing-user (set (map :team-id relevant-teams))) ; OC teams the Slack org has access to
            additional-team-ids (when existing-user (clojure.set/difference relevant-team-ids existing-team-ids))
            _ (when (and existing-user (seq additional-team-ids)) (timbre/infof "Adding user to teams %s" additional-team-ids))
            updated-user (when existing-user 
                            (if (empty? additional-team-ids)
                              existing-user ; no additional teams to add
                              (add-teams conn existing-user additional-team-ids))) ; add additional teams to the user

            ;; Final user
            user (if updated-user
                  ;; Complete the user data with the Slack user data only if user is still pending, ie first Slack onboard
                  (if (= (keyword (:status updated-user)) :pending)
                    (merge updated-user {:first-name (or (:first-name updated-user) (:first-name cleaned-user-props))
                                         :last-name (or (:last-name updated-user) (:last-name cleaned-user-props))
                                         :title (or (:title updated-user) (:title cleaned-user-props))
                                                     ;; Replace our default avatars with the Slack avatar only if
                                                     ;; user is still pending and has an avatar on Slack
                                         :avatar-url (update-user-avatar-if-needed (:avatar-url updated-user) (:avatar-url cleaned-user-props))
                                         :timezone (or (:timezone updated-user) (:timezone cleaned-user-props))})
                    (update-in updated-user [:avatar-url] update-user-avatar-if-needed (:avatar-url cleaned-user-props)))
                  (create-user-for conn new-user teams slack-org)) ; create new user if needed
            _ (when-not updated-user (timbre/infof "Created OC user %s" (:user-id user)))

            ; new Slack team
            new-slack-user {(keyword (:slack-org-id slack-user)) {:id (:slack-id slack-user)
                                                                  :slack-org-id (:slack-org-id slack-user)
                                                                  :display-name (:display-name user-profile)
                                                                  :token (:slack-token slack-user)}}
            ;; Determine where we redirect them to
            bot-only? (and target-team ((set (:slack-orgs target-team)) (:slack-org-id slack-org)) (not (s/includes? redirect "add=team")))
            redirect-arg (if bot-only? :bot :team)
            ;; Activate the user (Slack is a trusted email verifier) and upsert the Slack users to the list for the user
            slack-user-u (if do-not-assoc-slack-user
                           user
                           (update-in user [:slack-users] merge new-slack-user))
            slack-user-digest (if adding-slack-bot?
                                (merge {:digest-medium :slack
                                        :notification-medium :slack
                                        :reminder-medium :slack}
                                       slack-user-u)
                                slack-user-u)
            updated-slack-user (do (user-res/activate! conn (:user-id user)) ; no longer :pending (if they were)
                                   (user-res/update-user! conn
                                                          (:user-id user)
                                                          slack-user-digest))
             ;; Add the Slack org to the existing team if needed
            _maybe-associate-slack-org (when target-team
                                         (team-res/add-slack-org conn team-id (:slack-org-id slack-org)))
            ;; Create a JWToken from the user for the response
            jwt-user (user-rep/jwt-props-for (-> updated-slack-user
                                                 (clean-user)
                                                 (assoc :admin (user-res/admin-of conn (:user-id user)))
                                                 (assoc :premium-teams (user-res/premium-teams conn (:user-id user)))
                                                 (assoc :slack-id (:slack-id slack-user))
                                                 (assoc :slack-token (:slack-token slack-user))
                                                 (assoc :slack-display-name (:display-name user-profile))
                                                 (assoc :slack-bots (lib-jwt/bots-for conn user)))
                                             :slack)]
        (doseq [team teams]
          (slack-api-calls/gather-display-names (:team-id team) existing-slack-org))
        ;; If the bot has been added send welcome message.
        (when adding-slack-bot?
          (sqs/send! sqs/BotTrigger
                      (sqs/->slack-welcome
                      {:id (:slack-id slack-response)
                        :slack-org-id (:slack-org-id slack-org)
                        :token (:bot-token slack-org)
                        :bot-user-id (:bot-user-id slack-org)})
                      config/aws-sqs-bot-queue))
        (redirect-to-web-ui redirect-origin redirect redirect-arg
                            (jwtoken/generate conn jwt-user)
                            (:last-token-at user)))

      ;; Error came back from Slack, send them back to the OC Web UI
      (redirect-to-web-ui redirect-origin redirect :failed)))

(defn- slack-callback
  "Handle a callback from Slack, then redirect the user's browser back to the web UI."
  [conn params]
  (try
    (timbre/info "Slack callback")
    (let [slack-response (slack/oauth-callback params) ; process the response from Slack, get redirect and redirect-origin for error redirects
          _team-id (:team-id slack-response) ; a team-id is present if the bot or Slack org is being added to existing team
          _user-id (:user-id slack-response) ; a user-id is present if a Slack org is being added to an existing team
          _redirect (:redirect slack-response)] ; where we redirect the browser back to
      (slack-callback-step conn slack-response))
    (catch Exception e
      (timbre/info e)
      (sentry/capture e)
      (redirect-to-web-ui config/ui-server-url nil :failed))))

(defn refresh-token
  "Handle request to refresh an expired Slack JWToken by checking if the access token is still valid with Slack."
  [conn {user-id :user-id :as user} slack-id slack-token]
  (timbre/info "Refresh token request for user" user-id "with slack id of" slack-id "and access token" slack-token)
  (if (slack/valid-access-token? slack-token "team" slack-id)
    (do
      (timbre/info "Refreshing Slack user" slack-id)
      ;; Respond w/ JWToken and location
      (user-rep/auth-response conn (-> user
                                      (clean-user)
                                      (assoc :admin (:admin user))
                                      (assoc :premium-teams (user-res/premium-teams conn (:user-id user)))
                                      (assoc :slack-id slack-id)
                                      (assoc :slack-token slack-token)
                                      (assoc :slack-bots (lib-jwt/bots-for conn user)))
        :slack))
    (do
      (timbre/warn "Invalid access token" slack-token "for user" user-id)
      (api-common/error-response "Could note confirm token." 400))))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      (OPTIONS "/slack/auth" {params :params} (pool/with-pool [conn db-pool] (slack-callback conn params)))
      (GET "/slack/auth" {params :params} (pool/with-pool [conn db-pool] (slack-callback conn params)))))) 