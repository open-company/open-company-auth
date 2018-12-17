(ns oc.auth.api.teams
  "Liberator API for team resources."
  (:require [if-let.core :refer (if-let* when-let*)]
            [defun.core :refer (defun-)]
            [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (ANY OPTIONS POST DELETE)]
            [liberator.core :refer (defresource by-method)]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.lib.db.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.auth.config :as config]
            [oc.auth.lib.slack :as slack]
            [oc.auth.lib.sqs :as sqs]
            [oc.auth.resources.team :as team-res]
            [oc.auth.resources.slack-org :as slack-org-res]
            [oc.auth.resources.user :as user-res]
            [oc.auth.representations.media-types :as mt]
            [oc.auth.representations.team :as team-rep]
            [oc.auth.representations.user :as user-rep]
            [oc.auth.representations.slack-org :as slack-org-rep]))

;; ----- Validations -----

(defn malformed-email-domain?
  "Read in the body param from the request and make sure it's a non-blank string
  that corresponds to an email domain. Otherwise just indicate it's malformed."
  [ctx]
  (try
    (if-let* [domain (slurp (get-in ctx [:request :body]))
              valid? (lib-schema/valid-email-domain? domain)]
      [false {:data domain}]
      true)
    (catch Exception e
      (do (timbre/warn "Request body not processable as an email domain: " e)
        true))))

(defn allow-team-members
  "Return true if the JWToken user is a member of the specified team."
  [conn {user-id :user-id} team-id]
  (if-let [user (user-res/get-user conn user-id)]
    ((set (:teams user)) team-id)
    false))

(defn allow-team-admins
  "Return true if the JWToken user is an admin of the specified team."
  [conn {user-id :user-id} team-id]
  (if-let [team (team-res/get-team conn team-id)]
    ((set (:admins team)) user-id)
    false))

(defn- valid-team-update? [conn team-id team-props]
  (if-let [team (team-res/get-team conn team-id)]
    (let [updated-team (merge team (team-res/clean team-props))]
      (if (lib-schema/valid? team-res/Team updated-team)
        {:existing-team team :team-update updated-team}
        [false, {:team-update updated-team}])) ; invalid update
    true)) ; No team for this team-id, so this will fail existence check later

;; ----- Utility functions -----

(defn teams-for-user
  "Return a sequence of teams that the user, specified by their user-id, is a member of."
  [conn user-id]
  (when-let* [user (user-res/get-user conn user-id)
            teams (:teams user)]
    (team-res/list-teams-by-ids conn teams [:logo-url :admins :created-at :updated-at])))

;; ----- Actions -----

(defun- handle-invite
  "Handle an invitation/re-invite request.

  This may involve one or more of the following:
  Creating the user
  Adding the user to the team
  Creating a one-time token for the invitee to auth with (email only)
  Sending an email with the token to invite them (email only)
  Sending a Slack message to invite them (Slack only)
  "

  ;; No team to invite to!
  ([_conn _sender nil _user _member? _admin? _invite] (timbre/warn "Invite request to non-existent team.") false)

  ;; An already active team member... who is inviting this person, yoh?
  ([_conn _sender team user :guard #(= :active (keyword (:status %))) true _admin? _invite]
  (timbre/warn "Invite request for existing active team member" (:user-id user) "of team" (:team-id team))
  user)
  
  ;; No user yet, email invite
  ([conn sender team nil member? admin? invite :guard :email]
  (let [team-id (:team-id team)
        email (:email invite)]
    (timbre/info "Creating user:" email "for team:" team-id)
    (if-let [new-user (user-res/create-user! conn
                        (user-res/->user (-> invite
                          (dissoc :admin :org-name :logo-url :logo-width :logo-height :note :slack-id :slack-org-id)
                          (assoc :one-time-token (str (java.util.UUID/randomUUID)))
                          (assoc :teams [team-id]))))]
      (handle-invite conn sender team new-user true admin? invite) ; recurse
      (do (timbre/error "Failed adding user:" email) false))))

  ;; No user yet, Slack invite
  ([conn sender team nil member? admin? invite :guard :slack-id]
  (let [team-id (:team-id team)
        slack-id (:slack-id invite)
        slack-org-id (:slack-org-id invite)]
    (timbre/info "Creating user:" slack-id "for team:" team-id)
    (if-let* [slack-org (slack-org-res/get-slack-org conn slack-org-id)
              bot-token (:bot-token slack-org)
              bot-user-id (:bot-user-id slack-org)
              slack-user (slack/get-user-info bot-token config/slack-bot-scope slack-id)
              oc-user (user-res/->user (-> slack-user
                                          (assoc :teams [team-id])
                                          (dissoc :slack-id :slack-org-id :logo-url :logo-width :logo-height :name)))
              new-user (user-res/create-user! conn oc-user)]
      (handle-invite conn sender team new-user true admin? (-> invite 
                                                              (assoc :bot-token bot-token)
                                                              (assoc :bot-user-id bot-user-id))) ; recurse
      (do (timbre/error "Failed adding user:" slack-id) false))))
  
  ;; User exists, but not a team member yet
  ([conn sender team user member? :guard not admin? invite]
  (let [team-id (:team-id team)
        user-id (:user-id user)
        status (keyword (:status user))]
    (timbre/info "Adding user:" user-id "to team:" team-id)
    (if-let [updated-user (user-res/add-team conn user-id team-id)]
      (if (= status :active)
        user ; TODO need to send a welcome to the team email
        (handle-invite conn sender team updated-user true admin? invite)) ; recurse
      (do (timbre/error "Failed adding team:" team-id "to user:" user-id) false))))

  ;; User exists, and is a team member, but not an admin, and admin was requested in the invite
  ([conn sender team user true _admin? :guard not invite :guard :admin]
  (let [team-id (:team-id team)
        user-id (:user-id user)]
    (timbre/info "Making user:" user-id "an admin of team:" team-id)
    (if-let [updated-team (team-res/add-admin conn team-id user-id)]
      (handle-invite conn sender team user true true invite) ; recurse
      (do (timbre/error "Failed making user:" user-id "an admin of team:" team-id) false))))

  ;; Non-active team member, needs a Slack invite/re-invite
  ([conn sender team user :guard #(not= :active (keyword (:status %))) true _admin? invite :guard :slack-id]
  (let [user-id (:user-id user)
        slack-id (:slack-id invite)
        slack-invite (-> invite
                      (dissoc :email)
                      (merge {:first-name (:first-name user)
                              :from-id (:slack-id sender)}))
        add-bot? (not (contains? slack-invite :bot-token))
        slack-org (when add-bot?
                    (slack-org-res/get-slack-org conn (:slack-org-id invite)))
        fixed-slack-invite (if add-bot?
                             (merge slack-invite {:bot-token (:bot-token slack-org) :bot-user-id (:bot-user-id slack-org)})
                             slack-invite)]
    (timbre/info "Sending Slack invitation to user:" user-id "at:" slack-id)
    (sqs/send! sqs/SlackInvite
      (sqs/->slack-invite fixed-slack-invite
        (:first-name sender))
      config/aws-sqs-bot-queue)
    user))

  ;; Non-active team member without a token, needs a token
  ([conn sender team user :guard #(and (not= :active (keyword (:status %)))
                                       (not (:one-time-token %))) true admin? invite]
  (let [user-id (:user-id user)]
    (timbre/info "Adding token to user:" user-id)
    (if-let [updated-user (user-res/add-token conn user-id)]
      (handle-invite conn sender team updated-user true admin? invite) ; recurse
      (do (timbre/error "Failed adding token to user:" user-id) false))))

  ;; Non-active team member with a token, needs an email invite/re-invite
  ([_conn sender _team user :guard #(not= :active (keyword (:status %))) true _admin? invite]
  (let [user-id (:user-id user)
        email (:email user)
        one-time-token (:one-time-token user)]
    (timbre/info "Sending email invitation to user:" user-id "at:" email)
    (sqs/send! sqs/EmailInvite
      (sqs/->email-invite
        (merge invite {:token one-time-token :first-name (:first-name user)})
        (:name sender)
        (:avatar-url sender)
        (:email sender))
      config/aws-sqs-email-queue)
    user)))

(defn- update-team [conn ctx team-id]
  (timbre/info "Updating team:" team-id)
  (if-let* [updated-team (:team-update ctx)
            update-result (team-res/update-team! conn team-id updated-team)]
    (do
      (timbre/info "Updated team:" team-id)
      {:updated-team update-result})

    (do (timbre/error "Failed updating team:" team-id) false)))


(defn- delete-team [conn team-id]
  (timbre/info "Deleting team:" team-id)
  (if (team-res/delete-team! conn team-id)
    (do (timbre/info "Deleted team:" team-id) true)
    (do (timbre/error "Failed deleting team:" team-id) false)))

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

(defresource team-list [conn]
  (api-common/jwt-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get]

  ;; Media type client accepts
  :available-media-types [mt/team-collection-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/team-collection-media-type)
  
  ;; Responses
  :handle-ok (fn [ctx] (let [user-id (-> ctx :user :user-id)]
                        (team-rep/render-team-list (teams-for-user conn user-id) user-id))))

;; A resource for operations on a particular team
(defresource team [conn team-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get :patch :delete]

  ;; Media type client accepts
  :available-media-types [mt/team-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/team-media-type)

  ;; Media type client sends
  :known-content-type? (by-method {
    :options true
    :get true
    :patch (fn [ctx] (api-common/known-content-type? ctx mt/team-media-type))
    :delete true})
  
  ;; Authorization
  :allowed? (by-method {
    :options true
    :get (fn [ctx] (allow-team-admins conn (:user ctx) team-id))
    :patch (fn [ctx] (allow-team-admins conn (:user ctx) team-id))
    :delete (fn [ctx] (allow-team-admins conn (:user ctx) team-id))})

  ;; Validations
  :processable? (by-method {
    :options true
    :get true
    :patch (fn [ctx] (valid-team-update? conn team-id (:data ctx)))
    :delete true})

  ;; Existentialism
  :exists? (fn [ctx] (if-let [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))]
                        {:existing-team team}
                        false))

  ;; Actions
  :patch! (fn [ctx] (update-team conn ctx team-id))
  :delete! (fn [_] (delete-team conn team-id))

  ;; Responses
  :handle-ok (fn [ctx] (let [team (or (:updated-team ctx) (:existing-team ctx))
                             admins (set (:admins team))
                             users (user-res/list-users conn team-id [:created-at :updated-at]) ; users in the team
                             user-admins (map #(if (admins (:user-id %)) (assoc % :admin? true) %) users)
                             user-reps (map #(user-rep/render-user-for-collection team-id %) user-admins)
                             team-users (assoc team :users user-reps)
                             slack-org-ids (:slack-orgs team)
                             slack-orgs (if (empty? slack-org-ids)
                                          []
                                          (slack-org-res/list-slack-orgs-by-ids conn slack-org-ids
                                            [:bot-user-id :bot-token]))]
                          (team-rep/render-team (assoc team-users :slack-orgs slack-orgs))))
  :handle-unprocessable-entity (fn [ctx]
    (api-common/unprocessable-entity-response (schema/check team-res/Team (:team-update ctx)))))


;; A resource for user invitations to a particular team
(defresource invite [conn team-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :post]

  ;; Media type client accepts
  :available-media-types [mt/user-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/user-media-type)

  ;; Media type client sends
  :known-content-type? (by-method {
    :options true
    :post (fn [ctx] (api-common/known-content-type? ctx mt/invite-media-type))})

  ;; Authorization
  :allowed? (by-method {
    :options true 
    :post (fn [ctx] (and (or (allow-team-admins conn (:user ctx) team-id)
                             (not (-> ctx :data :admin)))
                         (allow-team-members conn (:user ctx) team-id)))})

  ;; Existentialism
  :exists? (fn [ctx] (if-let [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))]
                        (let [email (-> ctx :data :email)
                              user (when email (user-res/get-user-by-email conn email))
                              member? (when (and team user) (if ((set (:teams user)) (:team-id team)) true false))
                              admin? (when (and team user) (if ((set (:admins team)) (:user-id user)) true false))]
                          {:existing-team team :existing-user user :member? member? :admin? admin?})
                        false)) ; No team by that ID

  ;; Validations
  :processable? (by-method {
    :options true
    :post (fn [ctx] (or (lib-schema/valid? team-res/EmailInviteRequest (:data ctx))
                        (lib-schema/valid? team-res/SlackInviteRequest (:data ctx))))})

  ;; Actions
  :post! (fn [ctx] {:updated-user 
                    (handle-invite
                      conn
                      (:user ctx) ; sender
                      (:existing-team ctx)
                      (:existing-user ctx) ; recipient
                      (:member? ctx)
                      (:admin? ctx)
                      (:data ctx))}) ; invitation

  ;; Responses
  :respond-with-entity? true
  :handle-created (fn [ctx] (if-let [updated-user (:updated-user ctx)]
                              (api-common/location-response (user-rep/url updated-user) 
                                                            (user-rep/render-user updated-user)
                                                            mt/user-media-type)
                              (api-common/missing-response))))

;; A resource for the admins of a particular team
(defresource admin [conn team-id user-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :put :delete]

  ;; Media type client accepts
  :available-media-types [mt/admin-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/admin-media-type)

  ;; Media type client sends
  :malformed? false ; no check, this media type is blank
  
  ;; Auhorization
  :allowed? (by-method {
    :options true
    :put (fn [ctx] (allow-team-admins conn (:user ctx) team-id))
    :delete (fn [ctx] (allow-team-admins conn (:user ctx) team-id))})

  ;; Existentialism
  :put-to-existing? true
  :exists? (fn [ctx] (if-let* [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))
                               user (and (lib-schema/unique-id? user-id) (user-res/get-user conn user-id))
                               member? ((set (:teams user)) (:team-id team))]
                        {:existing-user user :admin? ((set (:admins team)) (:user-id user))}
                        false)) ; no team, no user, or user not a member of the team

  ;; Actions
  :put! (fn [ctx] (when (:existing-user ctx)
                    {:updated-team (team-res/add-admin conn team-id user-id)}))
  :delete! (fn [ctx] (when (:admin? ctx)
                      {:updated-team (team-res/remove-admin conn team-id user-id)}))

  ;; Responses
  :respond-with-entity? false
  :handle-created (fn [ctx] (when-not (:updated-team ctx) (api-common/missing-response)))
  :handle-no-content (fn [ctx] (when-not (:updated-team ctx) (api-common/missing-response))))

;; A resource for the email domains of a particular team
(defresource email-domain [conn team-id domain]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :post :delete]

  ;; Media type client accepts
  :available-media-types [mt/email-domain-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/email-domain-media-type)

  ;; Media type client sends
  :malformed? (by-method {
    :options false
    :post (fn [ctx] (malformed-email-domain? ctx))
    :delete false})
  :known-content-type? (by-method {
    :options true
    :post (fn [ctx] (api-common/known-content-type? ctx mt/email-domain-media-type))
    :delete true})

  ;; Authorization
  :allowed? (by-method {
    :options true
    :post (fn [ctx] (allow-team-admins conn (:user ctx) team-id))
    :delete (fn [ctx] (allow-team-admins conn (:user ctx) team-id))})

  :processable? (fn [ctx] (team-res/allowed-email-domain? (:data ctx))) ; check for blacklisted email domain

  ;; Existentialism
  :exists? (by-method {
    :post (fn [ctx] (if-let [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))]
                        {:existing-team true :existing-domain ((set (:email-domains team)) (:data ctx))}
                        false))
    :delete (fn [ctx] (if-let* [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))
                                exists? ((set (:email-domains team)) domain)]
                        {:existing-team true :existing-domain true}
                        false))}) ; team or email domain doesn't exist

  ;; Actions
  :post! (fn [ctx] (when (and (:existing-team ctx) (not (:existing-domain ctx)))
                    {:updated-team (team-res/add-email-domain conn team-id (:data ctx))}))
  :delete! (fn [ctx] (when (:existing-domain ctx)
                    {:updated-team (team-res/remove-email-domain conn team-id domain)}))
  
  ;; Responses
  :respond-with-entity? false
  :handle-unprocessable-entity (fn [ctx] (api-common/text-response "Email domain not allowed." 409))
  :handle-created (fn [ctx] (if (or (:updated-team ctx) (:existing-domain ctx))
                              (api-common/blank-response)
                              (api-common/missing-response)))
  :handle-no-content (fn [ctx] (when-not (:updated-team ctx) (api-common/missing-response)))
  :handle-options (if domain
                    (api-common/options-response [:options :delete])
                    (api-common/options-response [:options :post])))

;; A resource for the Slack orgs of a particular team
(defresource slack-org [conn team-id slack-org-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :delete]

  ;; Media type client accepts
  :available-media-types [mt/slack-org-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/slack-org-media-type)

  ;; Authorization
  :allowed? (by-method {
    :options true
    :delete (fn [ctx] (allow-team-admins conn (:user ctx) team-id))})

  ;; Existentialism
  :exists? (fn [ctx] (if-let* [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))
                               has-org? ((set (:slack-orgs team)) slack-org-id)]
                        {:has-org? true}
                        false))

  ;; Actions
  :delete! (fn [ctx]
             (when (:has-org? ctx)
               (team-res/remove-slack-org conn team-id slack-org-id)
               (slack-org-res/delete-slack-org! conn slack-org-id)))

  ;; Responses
  :respond-with-entity? false
  :handle-no-content (fn [ctx] (when-not (:has-org? ctx) (api-common/missing-response))))

;; A resource for roster of team users for a particular team
(defresource roster [conn team-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get]

  ;; Media type client accepts
  :available-media-types [mt/user-collection-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/user-collection-media-type)

  ;; Authorization
  :allowed? (by-method {
    :options true
    :get (fn [ctx] (allow-team-members conn (:user ctx) team-id))})

  ;; Existentialism
  :exists? (fn [ctx] (if-let* [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))]
                        {:existing-team team}
                        false))

  ;; Responses
  :handle-ok (fn [ctx]
    (let [team (:existing-team ctx)
          slack-orgs (slack-org-res/list-slack-orgs-by-ids conn (:slack-orgs team) [:bot-token :bot-user-id]) ; Slack orgs for team
          bot-tokens (map :bot-token (filter :bot-token slack-orgs)) ; Bot tokens of Slack orgs w/ a bot
          slack-bot-ids (map :bot-user-id (filter :bot-user-id slack-orgs)) ; Bot tokens of Slack orgs w/ a bot
          slack-users (mapcat #(slack/user-list %) bot-tokens) ; Slack roster of users
          oc-users (user-res/list-users conn team-id [:status :created-at :updated-at :slack-users]) ; OC roster of users
          oc-users-with-slack (map #(assoc % :slack-bot-ids slack-bot-ids) oc-users)
          slack-emails (set (map :email slack-users)) ; email of Slack users
          oc-emails (set (map :email oc-users)) ; email of OC users
          uninvited-slack-emails (clojure.set/difference slack-emails oc-emails) ; email of Slack users that aren't OC
          ;; Slack users not in OC (from their email)
          uninvited-slack-users (map (fn [email] (some #(when (= (:email %) email) %) slack-users)) uninvited-slack-emails)
          uninvited-slack-users-with-status (map #(assoc % :status :uninvited) uninvited-slack-users)
          ;; Find all users that are coming from Slack and add the missing data (like slack-org-id slack-id slack-display-name)
          oc-emails-from-slack (clojure.set/intersection slack-emails oc-emails)
          ;; Slack users in OC (add slack needed data)
          pending-users-from-slack (map (fn [email] (some #(when (and (= (:email %) email) (= (:status %) "pending")) %) oc-users-with-slack)) oc-emails-from-slack)
          pending-users-with-slack-data (map (fn [user] (merge (first (filterv #(= (:email %) (:email user)) slack-users)) user)) pending-users-from-slack)
          oc-users-not-from-slack (filterv (fn [user] (every? #(not= (:email %) (:email user)) pending-users-from-slack)) oc-users-with-slack)
          ;; OC users and univited Slack users (w/ status of uninvited) together gives us all the users
          all-users (remove empty? (concat oc-users-not-from-slack pending-users-with-slack-data uninvited-slack-users-with-status))]
      (user-rep/render-user-list team-id all-users))))

;; A resource for Slack channels for a particular team
(defresource channels [conn team-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get]

  ;; Media type client accepts
  :available-media-types [mt/slack-channel-collection-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/slack-channel-collection-media-type)

  ;; Authorization
  :allowed? (by-method {
    :options true
    :get (fn [ctx] (allow-team-members conn (:user ctx) team-id))})

  ;; Existentialism
  :exists? (fn [ctx] (if-let* [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))]
                        {:existing-team team}
                        false))

  ;; Responses
  :handle-ok (fn [ctx] (let [team (:existing-team ctx)
                             slack-org-ids (:slack-orgs team)
                             slack-orgs (slack-org-res/list-slack-orgs-by-ids conn slack-org-ids
                                            [:bot-user-id :bot-token])
                             channels (slack/channels-for slack-orgs)]
                          (slack-org-rep/render-channel-list team-id channels))))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      ;; Team listing
      (ANY "/teams" [] (pool/with-pool [conn db-pool] (team-list conn)))
      (ANY "/teams/" [] (pool/with-pool [conn db-pool] (team-list conn)))
      ;; Team operations
      (ANY "/teams/:team-id" [team-id] (pool/with-pool [conn db-pool] (team conn team-id)))
      (ANY "/teams/:team-id/" [team-id] (pool/with-pool [conn db-pool] (team conn team-id)))
      ;; Invite user to team
      (ANY "/teams/:team-id/users" [team-id] (pool/with-pool [conn db-pool] (invite conn team-id)))
      (ANY "/teams/:team-id/users/" [team-id] (pool/with-pool [conn db-pool] (invite conn team-id)))
      ;; Team admin operations
      (ANY "/teams/:team-id/admins/:user-id" [team-id user-id]
        (pool/with-pool [conn db-pool] (admin conn team-id user-id)))
      (ANY "/teams/:team-id/admins/:user-id/" [team-id user-id]
        (pool/with-pool [conn db-pool] (admin conn team-id user-id)))
      ;; Email domain operations
      (OPTIONS "/teams/:team-id/email-domains" [team-id]
        (pool/with-pool [conn db-pool] (email-domain conn team-id nil)))
      (OPTIONS "/teams/:team-id/email-domains/" [team-id]
        (pool/with-pool [conn db-pool] (email-domain conn team-id nil)))
      (POST "/teams/:team-id/email-domains" [team-id]
        (pool/with-pool [conn db-pool] (email-domain conn team-id nil)))
      (POST "/teams/:team-id/email-domains/" [team-id]
        (pool/with-pool [conn db-pool] (email-domain conn team-id nil)))
      (OPTIONS "/teams/:team-id/email-domains/:domain" [team-id domain]
        (pool/with-pool [conn db-pool] (email-domain conn team-id domain)))
      (OPTIONS "/teams/:team-id/email-domains/:domain/" [team-id domain]
        (pool/with-pool [conn db-pool] (email-domain conn team-id domain)))
      (DELETE "/teams/:team-id/email-domains/:domain" [team-id domain]
        (pool/with-pool [conn db-pool] (email-domain conn team-id domain)))
      (DELETE "/teams/:team-id/email-domains/:domain/" [team-id domain]
        (pool/with-pool [conn db-pool] (email-domain conn team-id domain)))
      ;; Slack org operations
      (ANY "/teams/:team-id/slack-orgs/:slack-org-id" [team-id slack-org-id]
        (pool/with-pool [conn db-pool] (slack-org conn team-id slack-org-id)))
      (ANY "/teams/:team-id/slack-orgs/:slack-org-id/" [team-id slack-org-id]
        (pool/with-pool [conn db-pool] (slack-org conn team-id slack-org-id)))
      ;; Team roster
      (ANY "/teams/:team-id/roster" [team-id] (pool/with-pool [conn db-pool] (roster conn team-id)))
      (ANY "/teams/:team-id/roster/" [team-id] (pool/with-pool [conn db-pool] (roster conn team-id)))
      ;; Team's Slack channels
      (ANY "/teams/:team-id/channels" [team-id] (pool/with-pool [conn db-pool] (channels conn team-id)))
      (ANY "/teams/:team-id/channels/" [team-id] (pool/with-pool [conn db-pool] (channels conn team-id))))))