(ns oc.auth.api.teams
  "Liberator API for team resources."
  (:require [if-let.core :refer (if-let* when-let*)]
            [defun.core :refer (defun)]
            [clojure.set :as clj-set]
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
            [oc.auth.async.payments :as payments]
            [oc.auth.async.notify :as notify]
            [oc.auth.resources.team :as team-res]
            [oc.auth.resources.slack-org :as slack-org-res]
            [oc.auth.resources.user :as user-res]
            [oc.auth.resources.invite-throttle :as invite-throttle]
            [oc.auth.representations.media-types :as mt]
            [oc.auth.representations.team :as team-rep]
            [oc.auth.representations.user :as user-rep]
            [oc.auth.representations.slack-org :as slack-org-rep]
            [oc.auth.lib.recipient-validation :as recipient-validation]))

;; ----- Validations -----

(defn malformed-email-domain?
  "Read in the body param from the request and make sure it's valid JSON,
  get the email domain from it and make sure it's a non-blank string
  that corresponds to an email domain. Otherwise just indicate it's malformed."
  [ctx]
  (try
    (if-let* [json-payload (-> ctx (api-common/malformed-json? false) second :data)
              valid? (lib-schema/valid-email-domain? (:email-domain json-payload))]
      [false {:data json-payload}]
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
    (let [teams-data (team-res/list-teams-by-ids conn teams [:logo-url :admins :created-at :updated-at])]
      (map #(assoc % :invite-throttle (invite-throttle/get-or-create! (:user-id user) (:team-id %))) teams-data))))

;; ----- Actions -----

(defun handle-invite
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

  ;; Invite user, used by oc.support.util.email-invite-from-csv
  ([conn sender :guard :user-id team-id :guard string? invite-data :guard #(and (#{:viewer :admin :contributor} (:access %)) (lib-schema/valid-email-address? (:email %)))]
   (let [existing-team (team-res/get-team conn team-id)
         existing-user (user-res/get-user-by-email conn (:email invite-data))
         ->admin? (= (:access invite-data) :admin)
         member? (when (and existing-user existing-team)
                   (boolean ((set (:teams existing-user)) (:team-id existing-team))))
         admin? (when (and existing-user existing-team)
                  (boolean ((set (:admins existing-team)) (:user-id existing-user))))]
     (handle-invite conn sender existing-team existing-user member? admin? (assoc invite-data :admin ->admin?))))

  ;; User exists, and is a team member, but not an admin, and admin was requested in the invite
  ([conn sender team user true _admin? :guard not invite :guard :admin]
  (let [team-id (:team-id team)
        user-id (:user-id user)]
    (timbre/info "Making user:" user-id "an admin of team:" team-id)
    (if-let [updated-team (team-res/add-admin conn team-id user-id)]
      (handle-invite conn sender updated-team user true true invite) ; recurse
      (do (timbre/error "Failed making user:" user-id "an admin of team:" team-id) false))))

  ;; An already active team member... who is inviting this person, yoh?
  ([_conn _sender team user :guard #(= :active (keyword (:status %))) true _admin? _invite]
  (timbre/warn "Invite request for existing active team member" (:user-id user) "of team" (:team-id team))
  user)

  ;; No user yet, email invite
  ([conn sender team nil member? admin? invite :guard :email]
  (let [team-id (:team-id team)
        email (:email invite)
        new-user-data (-> invite
                          (dissoc :admin :org-name :org-slug :org-uuid :org-logo-url :team-id :note :slack-id :slack-org-id :user-type :access)
                          (assoc :one-time-token (str (java.util.UUID/randomUUID)))
                          (assoc :teams [team-id]))
        new-user-map (user-res/->user new-user-data)]
    (timbre/info "Creating user:" email "for team:" team-id)
    (if-let [new-user (user-res/create-user! conn new-user-map nil (user-res/nux-tags-for-user new-user-map invite))]
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
                                          (dissoc :slack-id :slack-org-id :org-name :org-slug :org-uuid :org-logo-url :name :user-type :access)))
              new-user (user-res/create-user! conn oc-user nil (user-res/nux-tags-for-user oc-user invite))]
      (handle-invite conn sender team new-user true admin? (-> invite
                                                             (assoc :bot-token bot-token)
                                                             (assoc :bot-user-id bot-user-id))) ; recurse
      (do (timbre/error "Failed adding user:" slack-id) false))))

  ;; User exists, but not a team member yet
  ([conn sender team user member? :guard not admin? invite]
  (let [team-id (:team-id team)
        user-id (:user-id user)
        org {:slug (:org-slug invite)
             :uuid (:org-uuid invite)
             :name (:org-name invite)
             :logo-url (:org-logo-url invite)
             :team-id team-id}]
    (timbre/info "Adding user:" user-id "to team:" team-id)
    (if-let [updated-user (user-res/add-team conn user-id team-id)]
      (do
        ;; Send a notification to the user to notify the team add and to force refresh his JWT.
        (notify/send-team! (notify/->team-add-trigger user sender org (:admin invite)))
        (handle-invite conn sender team updated-user true admin? invite)) ; recurse
      (do (timbre/error "Failed adding team:" team-id "to user:" user-id) false))))

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

(defn- remove-team-member [conn team-id {member-id :user-id :as member} sender payload-data admin?]
  (timbre/info "Removing user" member-id "from team" team-id)
  (let [updated-user (user-res/remove-team conn member-id team-id)
        org-data {:name (:org-name payload-data)
                  :uuid (:org-uuid payload-data)
                  :slug (:org-slug payload-data)
                  :team-id team-id}]
    ;; Send a notification to the user if he still has teams left
    (when (seq (:teams updated-user))
      (notify/send-team! (notify/->team-remove-trigger member sender org-data team-id admin?)))

    (when admin?
      (timbre/info "User is an admin, removing from admins of team" team-id)
      (team-res/remove-admin conn team-id member-id))
    ;; (when (empty? (:teams updated-user))
    ;;   (timbre/info "User has no other team left, deleting user" member-id)
    ;;   (user-res/delete-user! conn member-id))
    (when config/payments-enabled?
      (payments/report-team-seat-usage! conn team-id))
    (timbre/info "User" member-id "removed from team" team-id)
    {:updated-team (team-res/get-team conn team-id)}))

(defn- create-invite-link [conn team-id existing-team user]
  (timbre/info "Creating invite-link for team" team-id "requested by" (:user-id user))
  (if-let* [new-invite-link (str (java.util.UUID/randomUUID))
            updated-team (team-res/update-team! conn team-id (assoc existing-team :invite-token new-invite-link))]
    (do
      (timbre/info "Invite link for team" team-id "created:" new-invite-link)
      {:updated-team updated-team})
    (do
      (timbre/info "Failed creating invite link for team" team-id)
      false)))

(defn- delete-invite-link [conn team-id existing-team user]
  (timbre/info "Deleting invite-link for team" team-id "requested by" (:user-id user))
  (if-let [updated-team (team-res/update-team! conn team-id (assoc existing-team :invite-token nil))]
    (do
      (timbre/info "Invite link for team" team-id "deleted.")
      {:updated-team updated-team})
    (do
      (timbre/info "Failed deleting invite link for team" team-id)
      false)))

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

(defresource team-list [conn]
  (api-common/id-token-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get]

  ;; Media type client accepts
  :available-media-types [mt/team-collection-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/team-collection-media-type)

  ;; Responses
  :handle-ok (fn [ctx] (let [user (:user ctx)
                             teams (teams-for-user conn (:user-id user))]
                        (team-rep/render-team-list teams user))))

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
                        {:existing-team team
                         :invite-throttle (invite-throttle/get-or-create! (:user-id (:user ctx)) team-id)}
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
                                            [:bot-user-id :bot-token]))
                             full-user (user-res/get-user conn (:user-id (:user ctx)))
                             updated-invite-throttle (invite-throttle/update-token! (:user-id full-user) team-id)]
                          (team-rep/render-team (assoc team-users :slack-orgs slack-orgs) full-user updated-invite-throttle)))
  :handle-unprocessable-entity (fn [ctx]
    (api-common/unprocessable-entity-handler (merge ctx {:reason (schema/check team-res/Team (:team-update ctx))}))))


(defn- can-invite? [ctx conn]
  (let [check-user-status #(-> % :status keyword (= :active))]
    (if (-> ctx :user :status)
      (check-user-status (:user ctx))
      (check-user-status (user-res/get-user conn (:user-id (:user ctx)))))))

(defn- check-invite-throttle [token invite-throttle-data]
  (and (= token (:token invite-throttle-data))
       (< (:invite-count invite-throttle-data) config/invite-throttle-max-count)))

;; A resource for user invitations to a particular team
(defresource invite [conn team-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :initialize-context (fn [ctx] (let [jwt-auth (api-common/read-token (:request ctx) config/passphrase)
                                      invite-throttle-data (when (:user jwt-auth) (invite-throttle/retrieve (-> jwt-auth :user :user-id) team-id))]
                                  (merge jwt-auth {:invite-throttle invite-throttle-data})))

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
    :post (fn [ctx] (when (can-invite? ctx conn)
                      (if (-> ctx :data :admin)
                        (allow-team-admins conn (:user ctx) team-id)
                        (allow-team-members conn (:user ctx) team-id))))})

  ;; Existentialism
  :exists? (fn [ctx] (if-let [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))]
                        (let [email (-> ctx :data :email)
                              user (when email (user-res/get-user-by-email conn email))
                              member? (when (and team user) (if ((set (:teams user)) (:team-id team)) true false))
                              admin? (when (and team user) (if ((set (:admins team)) (:user-id user)) true false))]
                          {:existing-team team
                           :existing-user user
                           :member? member?
                           :admin? admin?})
                        false)) ; No team by that ID

  ;; Validations
  :processable? (by-method {
    :options true
    :post (fn [ctx]
            (cond ;; Email request not conform
              (not (lib-schema/valid? team-res/EmailInviteRequest (:data ctx)))
              [false {:reason "Invalid data, please correct the filled informations and try again." :override-default-error-message true}]
              ;; csrf token not valid
              (not (check-invite-throttle (-> ctx :data :csrf) (:invite-throttle ctx)))
              [false {:reason "Csrf token not valid, please refresh the page and try again." :override-default-error-message true}]
              ;; Slack request not conform
              (not (lib-schema/valid? team-res/SlackInviteRequest (:data ctx)))
              [false {:reason "Something went wrong checking your Slack informations, please try again." :override-default-error-message true}]
              ;; Not valid email address
              (not (recipient-validation/validate! (-> ctx :data :email)))
              [false {:reason "The email address you provided is not valid. Please try again." :override-default-error-message true}]
              :else
              true))})

  ;; Actions
  :post! (fn [ctx] {:updated-user
                    (handle-invite
                      conn
                      (:user ctx) ; sender
                      (:existing-team ctx)
                      (:existing-user ctx) ; recipient
                      (:member? ctx)
                      (:admin? ctx)
                      (dissoc (:data ctx) :csrf))}) ; invitation

  ;; Responses
  :respond-with-entity? true
  :handle-created (fn [ctx] (if-let [updated-user (:updated-user ctx)]
                              (do
                                (invite-throttle/increase-invite-count! (-> ctx :user :user-id) team-id)
                                (api-common/location-response (user-rep/url updated-user)
                                                            (user-rep/render-user updated-user)
                                                            mt/user-media-type))
                              (api-common/missing-response))))

;; A resource for the admins of a particular team
(defresource admin [conn team-id user-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :put :delete]

  ;; Media type client accepts
  :available-media-types [mt/admin-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/admin-media-type)

  ;; Media type client sends
  :known-content-type? true

  ;; Malformed body request?
  :malformed? false

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

(defresource member [conn team-id member-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :delete]

  ;; Media type client accepts
  :available-media-types [mt/user-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/user-media-type)

  ;; Malformed body request?
  :malformed? (by-method {:options false
                          :put false
                          :delete (fn [ctx] (api-common/malformed-json? ctx))})

  ;; Auhorization
  :allowed? (by-method {
    :options true
    :delete (fn [ctx] (allow-team-admins conn (:user ctx) team-id))})

  ;; Existentialism
  :exists? (fn [ctx] (if-let* [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))
                               user (and (lib-schema/unique-id? member-id) (user-res/get-user conn member-id))
                               member? ((set (:teams user)) (:team-id team))]
                        {:existing-user user :admin? ((set (:admins team)) member-id)}
                        false)) ; no team, no user, or user not a member of the team

  ;; Actions
  :delete! (fn [ctx] (remove-team-member conn team-id (:existing-user ctx) (:user ctx) (:data ctx) (:admin? ctx)))

  ;; Responses
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
    :post (fn [ctx] (when (can-invite? ctx conn) (allow-team-admins conn (:user ctx) team-id)))
    :delete (fn [ctx] (when (can-invite? ctx conn) (allow-team-admins conn (:user ctx) team-id)))})

  :processable? (fn [ctx] (team-res/allowed-email-domain? (:email-domain (:data ctx)))) ; check for blacklisted email domain

  ;; Existentialism
  :exists? (by-method {
    :post (fn [ctx] (if-let [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))]
                      {:existing-team team
                       :existing-domain ((set (:email-domains team)) (:email-domain (:data ctx)))}
                      false))
    :delete (fn [ctx] (if-let* [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))
                                exists? ((set (:email-domains team)) domain)]
                        {:existing-team team :existing-domain true}
                        false))}) ; team or email domain doesn't exist

  ;; Actions
  :post! (fn [ctx] (when (and (:existing-team ctx) (not (:existing-domain ctx)))
                      (if (:pre-flight (:data ctx))
                        {:pre-flight true :updated-team (:existing-team ctx)}
                        {:updated-team (team-res/add-email-domain conn team-id (:email-domain (:data ctx)))})))
  :delete! (fn [ctx] (when (:existing-domain ctx)
                      {:updated-team (team-res/remove-email-domain conn team-id domain)}))

  ;; Responses
  :respond-with-entity? false
  :handle-unprocessable-entity (fn [ctx]
    (api-common/unprocessable-entity-handler (merge ctx {:reason "Email domain not allowed." :status 409})))
  :handle-created (fn [ctx] (if (or (:updated-team ctx) (:pre-flight ctx) (:existing-domain ctx))
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
  (api-common/open-company-id-token-resource config/passphrase) ; verify validity and presence of required JWToken

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
          admins (set (:admins team))
          slack-orgs (slack-org-res/list-slack-orgs-by-ids conn (:slack-orgs team) [:bot-token :bot-user-id]) ; Slack orgs for team
          bot-tokens (map :bot-token (filter :bot-token slack-orgs)) ; Bot tokens of Slack orgs w/ a bot
          slack-bot-ids (map :bot-user-id (filter :bot-user-id slack-orgs)) ; Bot tokens of Slack orgs w/ a bot
          slack-users (slack/user-list bot-tokens) ; Slack roster of users
          oc-users (user-res/list-users conn team-id [:status :created-at :updated-at :slack-users :notification-medium :timezone :blurb :location :title :profiles]) ; OC roster of users
          oc-users-with-admin (map #(if (admins (:user-id %)) (assoc % :admin? true) %) oc-users)
          oc-users-with-slack (map #(assoc % :slack-bot-ids slack-bot-ids) oc-users-with-admin)
          slack-emails (set (map :email slack-users)) ; email of Slack users
          oc-emails (set (map :email oc-users-with-admin)) ; email of OC users
          uninvited-slack-emails (clj-set/difference slack-emails oc-emails) ; email of Slack users that aren't OC
          ;; Slack users not in OC (from their email)
          uninvited-slack-users (map (fn [email] (some #(when (= (:email %) email) %) slack-users)) uninvited-slack-emails)
          uninvited-slack-users-with-status (map #(assoc % :status :uninvited) uninvited-slack-users)
          ;; Find all users that are coming from Slack and add the missing data (like slack-org-id slack-id slack-display-name)
          oc-emails-from-slack (clj-set/intersection slack-emails oc-emails)
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
  :handle-ok (fn [ctx]
               (let [team (:existing-team ctx)
                     slack-org-ids (:slack-orgs team)
                     slack-orgs (slack-org-res/list-slack-orgs-by-ids conn slack-org-ids
                                                                      [:bot-user-id :bot-token])
                     bot-tokens (map :bot-token (filter :bot-token slack-orgs)) ; Bot tokens of Slack orgs w/ a bot
                     slack-users (slack/user-list bot-tokens) ; Slack roster of users
                     channels (slack/channels-for slack-orgs)]
                 (slack-org-rep/render-channel-list team-id channels slack-users))))

;; A resource for invite users using a team link
(defresource invite-link [conn team-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :post :delete]

  ;; Media type client accepts
  :available-media-types [mt/invite-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/invite-media-type)

  ;; Malformed?
  :malformed? false

  ;; Media type client sends
  :known-content-type? (by-method {
    :options true
    :post (fn [ctx] (api-common/known-content-type? ctx mt/invite-media-type))
    :delete (fn [ctx] (api-common/known-content-type? ctx mt/invite-media-type))})

  ;; Authorization
  :allowed? (by-method {
    :options true
    :post (fn [ctx] (when (can-invite? ctx conn) (allow-team-admins conn (:user ctx) team-id)))
    :delete (fn [ctx] (when (can-invite? ctx conn) (allow-team-admins conn (:user ctx) team-id)))})

  ;; Existentialism
  :exists? (fn [ctx]
              (if-let* [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))
                        _invite-link (:invite-token team)]
                {:existing-team team
                 :invite-throttle (invite-throttle/get-or-create! (:user-id (:user ctx)) team-id)}
                false)) ; No team by that ID or invite-token already exists

  ;; Validations
  :processable? true

  ;; Actions
  :post! (fn [ctx] (create-invite-link conn team-id (:existing-team ctx) (:user ctx))) ; create invite link

  :delete! (fn [ctx] (delete-invite-link conn team-id (:existing-team ctx) (:user ctx))) ; delete invite link

  ;; Responses
  :respond-with-entity? true
  :post-enacted? true
  :handle-created (fn [ctx]
                    (let [full-user (user-res/get-user conn (:user-id (:user ctx)))
                          updated-invite-throttle (:invite-throttle/updated-csrf (:user-id full-user) team-id)]
                      (team-rep/render-team (or (:updated-team ctx) (:existing-team ctx)) full-user updated-invite-throttle)))
  :handle-ok (fn [ctx]
               (let [full-user (user-res/get-user conn (:user-id (:user ctx)))
                     updated-invite-throttle (invite-throttle/update-token! (:user-id full-user) team-id)]
                 (team-rep/render-team (or (:updated-team ctx) (:existing-team ctx)) full-user updated-invite-throttle))))

;; A resource for roster of team users for a particular team
(defresource active-users [conn team-id]
  (api-common/open-company-id-token-resource config/passphrase) ; verify validity and presence of required JWToken

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
          admins (set (:admins team))
          oc-users (user-res/list-users conn team-id [:status :created-at :updated-at :slack-users :notification-medium :timezone :blurb :location :title :profiles]) ; OC roster of users
          active-users (filter #(#{"active" "unverified"} (:status %)) oc-users)
          oc-users-with-admin (map #(if (admins (:user-id %)) (assoc % :admin? true) %) active-users)]
      (user-rep/render-active-users-list team-id oc-users-with-admin))))

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
      ;; Remove user from team
      (ANY "/teams/:team-id/users/:member-id" [team-id member-id] (pool/with-pool [conn db-pool] (member conn team-id member-id)))
      (ANY "/teams/:team-id/users/:member-id/" [team-id member-id] (pool/with-pool [conn db-pool] (member conn team-id member-id)))
      ;; Team admin operations
      (ANY "/teams/:team-id/admins/:user-id" [team-id user-id]
        (pool/with-pool [conn db-pool] (admin conn team-id user-id)))
      (ANY "/teams/:team-id/admins/:user-id/" [team-id user-id]
        (pool/with-pool [conn db-pool] (admin conn team-id user-id)))
      ;; Invite team link
      (ANY "/teams/:team-id/invite-link" [team-id]
        (pool/with-pool [conn db-pool] (invite-link conn team-id)))
      (ANY "/teams/:team-id/invite-link/" [team-id]
        (pool/with-pool [conn db-pool] (invite-link conn team-id)))
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
      (ANY "/teams/:team-id/channels/" [team-id] (pool/with-pool [conn db-pool] (channels conn team-id)))
      ;; Active users
      (ANY "/teams/:team-id/active-users" [team-id] (pool/with-pool [conn db-pool] (active-users conn team-id)))
      (ANY "/teams/:team-id/active-users/" [team-id] (pool/with-pool [conn db-pool] (active-users conn team-id))))))