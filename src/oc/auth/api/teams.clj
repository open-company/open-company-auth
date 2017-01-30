(ns oc.auth.api.teams
  "Liberator API for team resources."
  (:require [clojure.string :as s]
            [if-let.core :refer (if-let* when-let*)]
            [defun.core :refer (defun-)]
            [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes OPTIONS GET POST PUT PATCH DELETE)]
            [liberator.core :refer (defresource by-method)]
            [oc.lib.schema :as lib-schema]
            [oc.lib.rethinkdb.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.auth.config :as config]
            [oc.auth.lib.sqs :as sqs]
            [oc.auth.resources.team :as team-res]
            [oc.auth.resources.slack-org :as slack-org-res]
            [oc.auth.resources.user :as user-res]
            [oc.auth.representations.team :as team-rep]
            [oc.auth.representations.user :as user-rep]))

;; ----- Utility Functions -----

(defn- token-link [type token]
  (s/join "/" [config/ui-server-url (str (name type) "?token=" token)]))

;; ----- Actions -----

(defn teams-for-user
  "Return a sequence of teams that the user, specified by their user-id, is a member of."
  [conn user-id]
  (when-let* [user (user-res/get-user conn user-id)
            teams (:teams user)]
    (team-res/get-teams conn teams [:admins :created-at :updated-at])))

(defun- handle-invite
  "Handle an invitation/re-invite request.

  This may involve one or more of the following:
  Creating the user
  Adding the user to the team
  Creating a one-time token for the invitee to auth with
  Sending an email with the token to invite them"

  ;; No team to invite to!
  ([_conn _sender nil _user _member? _invite] (timbre/warn "Invite request to non-existent team.") false)

  ;; An already active team member... who is inviting this person, yoh?
  ([_conn _sender team-id user :guard #(= "active" (:status %)) true _invite]
  (timbre/warn "Invite request for existing active team member" (:user-id user) "of team" (:team-id team))
  true)
  
  ;; No user yet
  ([conn sender team nil member? invite]
  (let [team-id (:team-id team)
        email (:email invite)]
    (timbre/info "Creating user" email "for team" team-id)
    (if-let [new-user (user-res/create-user! conn
                        (user-res/->user (-> invite
                          (dissoc :admin :org-name :logo-url)
                          (assoc :one-time-token (str (java.util.UUID/randomUUID)))
                          (assoc :teams [team-id]))))]
      (handle-invite conn sender team new-user true invite) ; recurse
      (do (timbre/error "Add user" email "failed.") false))))
  
  ;; User exists, but not a team member yet
  ([conn sender team user member? :guard not invite]
  (let [team-id (:team-id team)
        user-id (:user-id user)
        status (:status user)]
    (timbre/info "Adding user" user-id "to team" team-id)
    (if-let [updated-user (user-res/add-team conn user-id team-id)]
      (if (= status "active")
        user ; TODO need to send a welcome to the team email
        (handle-invite conn sender team updated-user true invite)) ; recurse
      (do (timbre/error "Add team" team-id "to user" user-id "failed.") false))))

  ;; Non-active team member without a token, needs a token
  ([conn sender team user :guard #(and (not= "active" (:status %))
                                (not (:one-time-token %))) true invite]
  (let [user-id (:user-id user)]
    (timbre/info "Adding token to" user-id)
    (if-let [updated-user (user-res/add-token conn user-id)]
      (handle-invite conn sender team updated-user true invite)) ; recurse
      (do (timbre/error "Add token to user" user-id "failed.") false)))

  ;; Non-active team member with a token, needs an email invite
  ([_conn sender _team user :guard #(not= "active" (:status %)) true invite]
  (let [user-id (:user-id user)
        email (:email user)
        one-time-token (:one-time-token user)]
    (timbre/info "Sending email inviting to user" user-id "at" email)
    (sqs/send! sqs/EmailInvite
      (sqs/->invite
        (merge invite {:token-link (token-link :invite one-time-token)})
        (:name sender)
        (:email sender)))
    user)))

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

(defn allow-team-admins
  "Return true if the JWToken user is an admin of the specified team."
  [conn {user-id :user-id} team-id]
  (if-let [team (team-res/get-team conn team-id)]
    ((set (:admins team)) user-id)
    false))

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

(defresource team-list [conn]
  (api-common/authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get]

  ;; Media type client accepts
  :available-charsets [api-common/UTF8]
  :available-media-types [team-rep/collection-media-type]

  ;; Media type client sends
  :handle-not-acceptable (api-common/only-accept 406 team-rep/collection-media-type)

  ;; Responses
  :handle-ok (fn [ctx] (let [user-id (-> ctx :user :user-id)]
                        (team-rep/render-team-list (teams-for-user conn user-id) user-id))))

;; A resource for operations on a particular team
(defresource team [conn team-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get :delete] ; TODO PATCH

  ;; Media type client accepts
  :available-media-types [team-rep/media-type]
  :handle-not-acceptable (api-common/only-accept 406 team-rep/media-type)
  
  ;; Authorization
  :allowed? (fn [ctx] (allow-team-admins conn (:user ctx) team-id))

  :exists? (fn [ctx] (if-let [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))]
                        {:existing-team team}
                        false))

  ;; Actions
  :delete! (fn [_] (team-res/delete-team! conn team-id))

  ;; Responses
  :handle-ok (fn [ctx] (let [admins (set (-> ctx :existing-team :admins))
                             users (user-res/list-users conn team-id) ; users in the team
                             user-admins (map #(if (admins (:user-id %)) (assoc % :admin true) %) users)
                             user-reps (map #(user-rep/render-user-for-collection team-id %) user-admins)
                             team (assoc (:existing-team ctx) :users user-reps)
                             slack-org-ids (:slack-orgs team)
                             slack-orgs (if (empty? slack-org-ids) [] (slack-org-res/get-slack-orgs conn slack-org-ids [:bot-user-id :bot-token]))]
                          (team-rep/render-team (assoc team :slack-orgs slack-orgs)))))

;; A resource for user invitations to a particular team
(defresource invite [conn team-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :post]

  ;; Media type client accepts
  :available-media-types [user-rep/media-type]
  :handle-not-acceptable (api-common/only-accept 406 user-rep/media-type)

  ;; Media type client sends
  :known-content-type? (by-method {
    :options true
    :post (fn [ctx] (api-common/known-content-type? ctx team-rep/invite-media-type))})

  ;; Authorization
  :allowed? (fn [ctx] (allow-team-admins conn (:user ctx) team-id)) ; team admins only

  :exists? (fn [ctx] (if-let [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))]
                        (let [user (user-res/get-user-by-email conn (-> ctx :data :email))
                              member? (when (and team user) ((set (:teams user)) (:team-id team)))]
                          {:existing-team team :existing-user user :member? member?})
                        false)) ; No team by that ID

  ;; Validations
  :processable? (by-method {
    :options true
    :post (fn [ctx] (lib-schema/valid? team-res/Invite (:data ctx)))})

  ;; Actions
  :post! (fn [ctx] {:updated-user 
                    (handle-invite
                      conn
                      (:user ctx) ; sender
                      (:existing-team ctx)
                      (:existing-user ctx) ; recipient
                      (:member? ctx)
                      (:data ctx))}) ; invitation

  ;; Responses
  :respond-with-entity? true
  :handle-created (fn [ctx] (if-let [updated-user (:updated-user ctx)]
                              (api-common/json-response (user-rep/render-user updated-user)
                                                         201
                                                         {"Location" (user-rep/url updated-user)})
                              (api-common/missing-response))))

;; A resource for the admins of a particular team
(defresource admin [conn team-id user-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :put :delete]

  ;; Media type client accepts
  :available-media-types [team-rep/admin-media-type]
  :handle-not-acceptable (api-common/only-accept 406 team-rep/admin-media-type)

  ;; Media type client sends
  :malformed? false ; no check, this media type is blank
  :known-content-type? (by-method {
    :options true
    :delete true
    :put (fn [ctx] (api-common/known-content-type? ctx team-rep/admin-media-type))})
  
  ;; Auhorization
  :allowed? (fn [ctx] (allow-team-admins conn (:user ctx) team-id))

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
  :available-media-types [team-rep/email-domain-media-type]
  :handle-not-acceptable (api-common/only-accept 406 team-rep/email-domain-media-type)

  ;; Media type client sends
  :malformed? (by-method {
    :options false
    :post (fn [ctx] (malformed-email-domain? ctx))
    :delete false})
  :known-content-type? (by-method {
    :options true
    :post (fn [ctx] (api-common/known-content-type? ctx team-rep/email-domain-media-type))
    :delete true})

  ;; Authorization
  :allowed? (fn [ctx] (allow-team-admins conn (:user ctx) team-id))

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
  :available-media-types [team-rep/slack-org-media-type]
  :handle-not-acceptable (api-common/only-accept 406 team-rep/slack-org-media-type)

  ;; Authorization
  :allowed? (fn [ctx] (allow-team-admins conn (:user ctx) team-id))

  :exists? (fn [ctx] (if-let* [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))
                               has-org? ((set (:slack-orgs team)) slack-org-id)]
                        {:has-org? true}
                        false))

  ;; Actions
  :delete! (fn [ctx] (when (:has-org? ctx) (team-res/remove-slack-org conn team-id slack-org-id)))

  ;; Responses
  :respond-with-entity? false
  :handle-no-content (fn [ctx] (when-not (:has-org? ctx) (api-common/missing-response))))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      ;; Team listing
      (OPTIONS "/teams" [] (pool/with-pool [conn db-pool] (team-list conn)))
      (OPTIONS "/teams/" [] (pool/with-pool [conn db-pool] (team-list conn)))
      (GET "/teams" [] (pool/with-pool [conn db-pool] (team-list conn)))
      (GET "/teams/" [] (pool/with-pool [conn db-pool] (team-list conn)))
      ;; Team operations
      (OPTIONS "/teams/:team-id" [team-id] (pool/with-pool [conn db-pool] (team conn team-id)))
      (GET "/teams/:team-id" [team-id] (pool/with-pool [conn db-pool] (team conn team-id)))
      (DELETE "/teams/:team-id" [team-id] (pool/with-pool [conn db-pool] (team conn team-id)))
      ;; Invite user to team
      (OPTIONS "/teams/:team-id/users" [team-id] (pool/with-pool [conn db-pool] (invite conn team-id)))
      (OPTIONS "/teams/:team-id/users/" [team-id] (pool/with-pool [conn db-pool] (invite conn team-id)))
      (POST "/teams/:team-id/users" [team-id] (pool/with-pool [conn db-pool] (invite conn team-id)))
      (POST "/teams/:team-id/users/" [team-id] (pool/with-pool [conn db-pool] (invite conn team-id)))
      ;; Team admin operations
      (OPTIONS "/teams/:team-id/admins/:user-id" [team-id user-id] (pool/with-pool [conn db-pool]
                                                                    (admin conn team-id user-id)))
      (PUT "/teams/:team-id/admins/:user-id" [team-id user-id] (pool/with-pool [conn db-pool]
                                                                    (admin conn team-id user-id)))
      (DELETE "/teams/:team-id/admins/:user-id" [team-id user-id] (pool/with-pool [conn db-pool]
                                                                    (admin conn team-id user-id)))
      ;; Email domain operations
      (OPTIONS "/teams/:team-id/email-domains/" [team-id] (pool/with-pool [conn db-pool]
                                                                    (email-domain conn team-id nil)))
      (OPTIONS "/teams/:team-id/email-domains" [team-id] (pool/with-pool [conn db-pool]
                                                                    (email-domain conn team-id nil)))
      (POST "/teams/:team-id/email-domains/" [team-id] (pool/with-pool [conn db-pool]
                                                                    (email-domain conn team-id nil)))
      (POST "/teams/:team-id/email-domains" [team-id] (pool/with-pool [conn db-pool]
                                                                    (email-domain conn team-id nil)))
      (OPTIONS "/teams/:team-id/email-domains/:domain" [team-id domain] (pool/with-pool [conn db-pool]
                                                                    (email-domain conn team-id domain)))
      (DELETE "/teams/:team-id/email-domains/:domain" [team-id domain] (pool/with-pool [conn db-pool]
                                                                    (email-domain conn team-id domain)))
      ;; Slack org operations
      (OPTIONS "/teams/:team-id/slack-orgs/:slack-org-id" [team-id slack-org-id] (pool/with-pool [conn db-pool]
                                                                    (slack-org conn team-id slack-org-id)))
      (DELETE "/teams/:team-id/slack-orgs/:slack-org-id" [team-id slack-org-id] (pool/with-pool [conn db-pool]
                                                                    (slack-org conn team-id slack-org-id))))))