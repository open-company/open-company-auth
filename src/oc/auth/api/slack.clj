(ns oc.auth.api.slack
  "Liberator API for Slack callback to auth service."
  (:require [defun.core :refer (defun-)]
            [if-let.core :refer (if-let* when-let*)]
            [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes GET OPTIONS)]
            [ring.util.response :as response]
            [oc.lib.db.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.lib.slack :as slack-lib]
            [oc.lib.jwt :as lib-jwt]
            [oc.auth.lib.jwtoken :as jwtoken]
            [oc.auth.lib.slack :as slack]
            [oc.auth.config :as config]
            [oc.auth.resources.team :as team-res]
            [oc.auth.resources.user :as user-res]
            [oc.auth.resources.slack-org :as slack-org-res]
            [oc.auth.representations.user :as user-rep]
            [oc.auth.representations.slack-auth :as slack-rep]))

;; ----- Utility Functions -----

(defn- clean-slack-user
  "Remove properties from a Slack user that are not needed for a persisted user."
  [slack-user]
  (dissoc slack-user :bot :name :slack-id :slack-org-id :slack-token :slack-org-name :team-id :logo-url :redirect :state-slack-org-id :error))

(defn- clean-user
  "Remove properties from a user that are not needed for a JWToken."
  [user]
  (dissoc user :created-at :updated-at :status))

(defn- email-for
  "Get the email for the Slack user with users.info if we don't already have it."
  [{slack-token :slack-token email :email slack-user-id :slack-id}]
  (let [response (when-not email (slack-lib/get-user-info slack-token slack-user-id))] ; get users.info if we need it
    (or email ; return it if we already had it
      (:email response))))

(defn- logo-url-for
  "Get the logo for the Slack org with team.info if we don't already have it."
  [{slack-token :slack-token logo-url :logo-url}]
  (let [response (when-not logo-url (slack-lib/get-team-info slack-token)) ; get team.info if we need it
        icon (:icon response)
        image-default (:image-default icon)]
    (or logo-url ; return it if we already had it
      (when-not image-default ; don't return the Slack default
        (or ; use the highest resolution we have
          (:image_230 icon)
          (:image_132 icon)
          (:image_88 icon)
          (:image_44 icon)
          (:image_34 icon)
          nil))))) ; give up

(defn- slack-domain-for
  "Get the Slack domain for the Slack org with team.info if we don't already have it."
  [{slack-token :slack-token domain :slack-domain :as slack-org}]
  (if domain
    slack-org ; we already have it
    (do
      (timbre/info "Retrieving Slack domain from team.info with:" slack-token)
      (let [response (slack-lib/get-team-info slack-token)
            slack-domain (:domain response)]
        (timbre/info "team.info response:" response)
        (if slack-domain ; it's possible we don't get a response to team.info due to permissions
          (assoc slack-org :slack-domain slack-domain)
          slack-org)))))

;; ----- Actions -----

(defn- create-slack-org-for
  "Create a new Slack org for the specified Slack user."
  [conn {slack-org-id :slack-org-id :as slack-user}]
  (timbre/info "Creating new Slack org for:" slack-org-id)
  (slack-org-res/create-slack-org! conn 
    (slack-org-res/->slack-org
      (select-keys (slack-domain-for slack-user) [:slack-org-id :slack-org-name :bot :slack-domain]))))

(defn- update-slack-org-for
  "Update the existing Slack org for the specified Slack user."
  [conn slack-user {slack-org-id :slack-org-id :as existing-slack-org}]
  (timbre/info "Updating Slack org:" slack-org-id)
  (let [updated-slack-org (merge (slack-domain-for existing-slack-org)
                            (select-keys slack-user [:slack-org-name :bot]))]
    (slack-org-res/update-slack-org! conn slack-org-id updated-slack-org)))

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
  [conn new-user teams]
  (timbre/info "Creating new user:" (:email new-user) (:first-name new-user) (:last-name new-user))
  (user-res/create-user! conn (-> new-user
                                (assoc :status :active)
                                (assoc :teams (map :team-id teams)))))

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
  ([redirect access] (redirect-to-web-ui redirect access nil :not-a-new-user)) ; nil = no jwtoken
  
  ([redirect access jwtoken last-token-at]
  (let [page (or redirect "/login")
        jwt-param (if jwtoken (str "&jwt=" jwtoken) "")
        url (str config/ui-server-url page "?access=" (name access) "&new=" (if last-token-at false true))]
    (timbre/info "Redirecting request to:" url)
    (response/redirect (str url jwt-param)))))

(defn- slack-callback-step2
  "Second step of the slack OAuth. The user has granted permission to the user and team data.
   now we need check if he granted bot permission and redirect to the web UI with the right response."
  [conn {:keys [team-id user-id redirect state-slack-org-id error] :as slack-response}]
  (timbre/info "slack callback step 2" slack-response)
  (if (or error                             ; user denied bot auth
          (false? (first slack-response))   ; something went wrong with bot auth
          (not= state-slack-org-id (:slack-org-id slack-response))) ; user granted bot for a different team
    
    ;; User didn't grant bot permissions, need to go back to UI with only first step response
    (let [user (user-res/get-user conn user-id)
          slack-user-data (get-in user [:slack-users (keyword state-slack-org-id)])
          ;; Create a JWToken from the user for the response
          jwt-user (user-rep/jwt-props-for (-> user
                                              (clean-user)
                                              (assoc :admin (user-res/admin-of conn (:user-id user)))
                                              (assoc :slack-id (:id slack-user-data))
                                              (assoc :slack-token (:token slack-user-data))) :slack)]
      (redirect-to-web-ui redirect :team
              (jwtoken/generate conn (assoc jwt-user :slack-bots (lib-jwt/bots-for conn jwt-user)))
              (:last-token-at user)))
    
    ;; User granted bot permission, so need to add the new authed bot to the team and redirect to web UI.
    (let [user (user-res/get-user conn user-id) ; existing user

          ;; Get existing Slack org for this auth sequence and update it with the bot
          existing-slack-org (slack-org-res/get-slack-org conn state-slack-org-id) ; existing Slack org
          slack-org (update-slack-org-for conn slack-response existing-slack-org) ; update the Slack org
          
          ;; Get the relevant teams

          ;; teams already exists for this slack org
          teams (team-res/list-teams-by-index conn :slack-orgs (:slack-org-id slack-response))

          ;; Add additional teams to the existing user if their Slack org gives them access to more teams
          existing-team-ids (set (:teams user)) ; OC teams the user has acces to now
          relevant-team-ids (set (map :team-id teams)) ; OC teams the Slack org has access to
          additional-team-ids (clojure.set/difference relevant-team-ids existing-team-ids)
          updated-user (if (empty? additional-team-ids)
                            user ; no additional teams to add
                            (add-teams conn user additional-team-ids)) ; add additional teams to the user

          ; new Slack team
          new-slack-user {(keyword (:slack-org-id slack-response)) {:id (:slack-id slack-response)
                                                                    :slack-org-id (:slack-org-id slack-response)
                                                                    :token (:slack-token slack-response)}}

          ;; Add or update the Slack users list of the user
          updated-slack-user (user-res/update-user! conn
                                                    (:user-id user)
                                                    (update-in user [:slack-users] merge new-slack-user))

          ;; Create a JWToken from the user for the response
          jwt-user (user-rep/jwt-props-for (-> updated-slack-user
                                              (clean-user)
                                              (assoc :admin (user-res/admin-of conn (:user-id user)))
                                              (assoc :slack-id (:slack-id slack-response))
                                              (assoc :slack-token (:slack-token slack-response))) :slack)]
      ;; All done, send them back to the OC Web UI with a JWToken
      (redirect-to-web-ui redirect :team
        (jwtoken/generate conn (assoc jwt-user :slack-bots (lib-jwt/bots-for conn jwt-user)))
        (:last-token-at user)))))

(defn- slack-callback-step1
  "
  First step of slack oauth, if the user is authing and the team has no bot installed we redirect him to the
  bot add sequence directly, if not we redirect to the web UI with the auth response.
  "
  [conn {:keys [team-id user-id redirect error] :as response}]
  (timbre/info "slack callback step 1:" response)
  (if-let* [slack-response (when-not (or error (false? (first response))) response)
            email (email-for slack-response)
            slack-user (assoc slack-response :email email)]
      
      ;; got an auth'd user back from Slack
      (let [
            ;; Get existing user by user ID or by email
            existing-user (if user-id
                            (user-res/get-user conn user-id)
                            (user-res/get-user-by-email conn email)) ; user already exists?
            _error (when (and user-id (not existing-user)) ; shouldn't have a Slack org being done by non-existent user
              (timbre/error "No user found for user-id" user-id "during Slack org add of:" slack-response))

            ;; Get existing teams for auth sequence
            target-team (when team-id (team-res/get-team conn team-id)) ; OC team that Slack is being added to

            ;; Get existing Slack org for this auth sequence, or create one if it's never been seen before
            existing-slack-org (slack-org-res/get-slack-org conn (:slack-org-id slack-user)) ; existing Slack org?
            slack-org (if existing-slack-org
                          (update-slack-org-for conn slack-user existing-slack-org) ; update the Slack org
                          (create-slack-org-for conn slack-user)) ; create new Slack org

            ;; Create a new user map if we don't have an existing user
            new-user (when-not existing-user (user-res/->user (clean-slack-user slack-user)))

            ;; Get the relevant teams
            relevant-teams (if team-id ; if we're adding a Slack org to an existing OC team
                              ;; The team this Slack org or Slack bot is being added to
                              [target-team]
                              ;; Do team(s) already exist for this Slack org?
                              (team-res/list-teams-by-index conn :slack-orgs (:slack-org-id slack-user)))
            
            ;; Create a new team if we're creating a new user and have no team(s) already for this Slack org
            new-team (when (and new-user (empty? relevant-teams))
                        (let [logo-url (logo-url-for slack-user)]
                          (create-team-for conn (assoc slack-user :logo-url logo-url) (:user-id new-user))))
            
            ;; Final set of teams relevant to this Slack org
            teams (if new-team [new-team] relevant-teams)

            ;; Add additional teams to the existing user if their Slack org gives them access to more teams
            existing-team-ids (when existing-user (set (:teams existing-user))) ; OC teams the user has acces to now
            relevant-team-ids (when existing-user (set (map :team-id relevant-teams))) ; OC teams the Slack org has access to
            additional-team-ids (when existing-user (clojure.set/difference relevant-team-ids existing-team-ids))
            updated-user (when existing-user 
                            (if (empty? additional-team-ids)
                              existing-user ; no additional teams to add
                              (add-teams conn existing-user additional-team-ids))) ; add additional teams to the user

            ;; Final user
            user (or updated-user (create-user-for conn new-user teams)) ; create new user if needed

            ; new Slack team
            new-slack-user {(keyword (:slack-org-id slack-user)) {:id (:slack-id slack-user)
                                                                  :slack-org-id (:slack-org-id slack-user)
                                                                  :token (:slack-token slack-user)}}

            ;; Add or update the Slack users list of the user
            updated-slack-user (user-res/update-user! conn
                                                      (:user-id user)
                                                      (-> user
                                                        (assoc :status :active) ; no longer :pending (if they were)
                                                        (update-in [:slack-users] merge new-slack-user)))
            
            ;; Create a JWToken from the user for the response
            jwt-user (user-rep/jwt-props-for (-> updated-slack-user
                                                (clean-user)
                                                (assoc :admin (user-res/admin-of conn (:user-id user)))
                                                (assoc :slack-id (:slack-id slack-user))
                                                (assoc :slack-token (:slack-token slack-user))) :slack)

            ;; Determine where we redirect them to
            bot-only? (and target-team ((set (:slack-orgs target-team)) (:slack-org-id slack-org)))
            redirect-arg (if bot-only? :bot :team)]
        ;; Add the Slack org to the existing team if needed
        (when (and target-team (not bot-only?))
          (team-res/add-slack-org conn team-id (:slack-org-id slack-org)))
        ;; When we are authing a user for a Slack team w/o the bot installed, we redirect to the 
        ;; bot access directly
        (if (and (not bot-only?) slack-org (not (contains? slack-org :bot-token)))
          (let [bot-team-id (if new-team (:team-id new-team) (:team-id (first relevant-teams)))
                bot-user-id (if new-user (:user-id new-user) (:user-id existing-user))]
            (response/redirect (:href (slack-rep/bot-link (str bot-team-id ":" bot-user-id ":" redirect ":" (:slack-org-id slack-org))))))
          ;; All done, send them back to the OC Web UI with a JWToken
          (redirect-to-web-ui redirect redirect-arg
            (jwtoken/generate conn (assoc jwt-user :slack-bots (lib-jwt/bots-for conn jwt-user)))
            (:last-token-at user))))

      ;; Error came back from Slack, send them back to the OC Web UI
      (redirect-to-web-ui redirect :failed)))

(defn- slack-callback
  "Handle a callback from Slack, then redirect the user's browser back to the web UI."
  [conn params]
  (timbre/info "Slack callback")
  (let [slack-response (slack/oauth-callback params) ; process the response from Slack
        team-id (:team-id slack-response) ; a team-id is present if the bot or Slack org is being added to existing team
        user-id (:user-id slack-response) ; a user-id is present if a Slack org is being added to an existing team
        redirect (:redirect slack-response) ; where we redirect the browser back to
        state-slack-org-id (:state-slack-org-id slack-response)]
    (if-not (nil? state-slack-org-id)
      (slack-callback-step2 conn slack-response)
      (slack-callback-step1 conn slack-response))))

(defn refresh-token
  "Handle request to refresh an expired Slack JWToken by checking if the access token is still valid with Slack."
  [conn {user-id :user-id :as user} slack-id slack-token]
  (timbre/info "Refresh token request for user" user-id "with slack id of" slack-id "and access token" slack-token)
  (if-let [slack-user (slack/valid-access-token? slack-token)]
    (do
      (timbre/info "Refreshing Slack user" slack-id)
      ;; Respond w/ JWToken and location
      (user-rep/auth-response conn (-> user
                                      (clean-user)
                                      (assoc :admin (:admin user))
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