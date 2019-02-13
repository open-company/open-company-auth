(ns oc.auth.api.users
  "Liberator API for user resources."
  (:require [clojure.string :as s]
            [if-let.core :refer (if-let* when-let*)]
            [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes ANY OPTIONS POST)]
            [liberator.core :refer (defresource by-method)]
            [liberator.representation :refer (ring-response)]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.lib.db.pool :as pool]
            [oc.lib.jwt :as jwt]
            [oc.lib.api.common :as api-common]
            [oc.auth.config :as config]
            [oc.auth.lib.sqs :as sqs]
            [oc.auth.lib.jwtoken :as jwtoken]
            [oc.auth.lib.google :as google]
            [oc.auth.api.slack :as slack-api]
            [oc.auth.async.notification :as notification]
            [oc.auth.resources.team :as team-res]
            [oc.auth.resources.user :as user-res]
            [oc.auth.representations.media-types :as mt]
            [oc.auth.representations.user :as user-rep]))

;; ----- Validations -----

(defn token-auth [conn headers]
  (if-let* [authorization (or (get headers "Authorization") (get headers "authorization"))
            token (last (s/split authorization #" "))]
    
    (if-let [user (and (lib-schema/valid? lib-schema/UUIDStr token) ; it's a valid UUID
                       (user-res/get-user-by-token conn token))] ; and a user has it as their token
      (let [user-id (:user-id user)]
        (timbre/info "Auth'd user:" user-id "by token:" token)
        (user-res/activate! conn user-id) ; mark user active
        (user-res/remove-token conn user-id) ; remove the used token
        {:email (:email user)})
    
      false) ; token is not a UUID, or no user for the token
    
    false)) ; no Authorization header or no token in the header

(defn email-basic-auth
  "HTTP Basic Auth function (email/pass) for ring middleware."
  [sys req auth-data]
  (when-let* [email (:username auth-data)
              password (:password auth-data)]
    (pool/with-pool [conn (-> sys :db-pool :pool)] 
      (if (user-res/authenticate? conn email password)
        (do 
          (timbre/info "Authed:" email)
          email)
        (do
          (timbre/info "Failed to auth:" email) 
          false)))))

(defn- allow-superuser-token [ctx]
  (if-let* [token (api-common/get-token (get-in ctx [:request :headers]))
            decoded-token (jwt/decode token)
            _true? (and (jwt/check-token token config/passphrase) ;; We signed the token
                        (:super-user (:claims decoded-token)))] ;; And granted super-user perm
    {:jwtoken decoded-token :user (:claims decoded-token)}
    false))

(defn- allow-user-and-team-admins [conn ctx accessed-user-id]
  (let [accessing-user-id (:user-id (:user ctx))]
    (or (contains? (:claims (:jwtoken ctx)) :super-user)
        (or
         ;; JWToken user-id matches URL user-id, user accessing themself
         (= accessing-user-id accessed-user-id)

         ;; check if the accessing user is an admin of any of the accessed user's teams
         (if-let* [accessed-user (user-res/get-user conn accessed-user-id)
                   teams (team-res/list-teams-by-ids conn
                                                     (:teams accessed-user)
                                                     [:admins])]
                  (some #((set (:admins %)) accessing-user-id) teams)
                  false)))))

(defn- update-user-qsg-checklist
  "Update the :qsg-checklist property by merging the new passed data with the old present data to avoid
  overriding all the properties on every patch."
  [old-user-map patch-data]
  (if (contains? patch-data :qsg-checklist)
    (update-in patch-data [:qsg-checklist] merge (:qsg-checklist patch-data))
    patch-data))

(defn- valid-user-update? [conn user-props user-id]
  (if-let [user (user-res/get-user conn user-id)]
    (let [current-password (:current-password user-props)
          new-password (:password user-props)
          updated-qsg-checklist (update-user-qsg-checklist user user-props)
          updated-user (merge user (user-res/ignore-props (dissoc updated-qsg-checklist :current-password)))]
      (if (and (lib-schema/valid? user-res/User updated-user)
               (or (nil? new-password) ; not attempting to change password
                   (and (empty? current-password) (not (nil? new-password))) ; attempting to set a new password but with no old password
                   (and (seq current-password) (user-res/password-match? current-password (:password-hash user))))) ; attempting to change the password with an old password set, checking that the old password match
        {:existing-user user :user-update (if new-password (assoc updated-user :password new-password) user-props)}
        [false, {:user-update updated-user}])) ; invalid update
    true)) ; No user for this user-id, so this will fail existence check later

(defn malformed-email?
  "Read in the body param from the request and make sure it's a non-blank string
  that corresponds to an email address. Otherwise just indicate it's malformed."
  [ctx]
  (try
    (if-let* [email (slurp (get-in ctx [:request :body]))
              valid? (lib-schema/valid-email-address? email)]
      [false {:data email}]
      true)
    (catch Exception e
      (do (timbre/warn "Request body not processable as an email address: " e)
        true))))

;; ----- Actions -----

(defn- can-resend-verificaiton-email? [conn user-id]
  (if-let* [user (user-res/get-user conn user-id)
            _is_not_active (not= (keyword (:status user)) :active)]
    true
    false))

(defn- send-verification-email [user & [resend?]]
  (timbre/info (str (if resend? "Re-sending" "Sending") " email verification request for:" (:user-id user) "(" (:email user) ")"))
  (sqs/send! sqs/TokenAuth
             (sqs/->token-auth {:type :verify
                                :email (:email user)
                                :token (:one-time-token user)})
             config/aws-sqs-email-queue
             ;; Delay only if user is unverified (active but with unverified email)
             ;; and we are not resending the verification email
             (if (and (not= (keyword (:status user)) :pending)
                      (not resend?))
                120
                0))
  (timbre/info (str (if resend? "Re-sent" "Sent") " email verification for:" (:user-id user) "(" (:email user) ")")))

(defn- resend-verification-email [conn ctx user-id]
  (timbre/info "Resending verificaiton email:" (:email (:existing-user ctx)))
  (let [user (:existing-user ctx)
        has-one-time-token? (not (s/blank? (:one-time-token user)))]
    (when-not has-one-time-token?
      (timbre/info "Adding one-time-token for:" user-id "(" (:email user) ")"))
    (let [new-one-time-token (when-not has-one-time-token?
                                (str (java.util.UUID/randomUUID)))]
      (when-not has-one-time-token?
        (timbre/info "Sending password reset request for:" user-id "(" (:email user) ")"))
      (let [updated-user (if has-one-time-token?
                            user
                            (user-res/add-token conn user-id new-one-time-token))]
        (send-verification-email updated-user true)
        {:updated-user updated-user}))))

(defn- create-user [conn {email :email password :password :as user-props}]
  (timbre/info "Creating user:" email)
  (if-let* [created-user (user-res/create-user! conn (user-res/->user user-props password))
            user-id (:user-id created-user)
            admin-teams (user-res/admin-of conn user-id)]
    (do
      (timbre/info "Created user:" email)
      (send-verification-email created-user)
      (timbre/info "Sending notification to SNS topic for:" user-id "(" email ")")
      (notification/send-trigger! (notification/->trigger created-user))
      {:new-user (assoc created-user :admin admin-teams)})
    
    (do
      (timbre/error "Failed creating user:" email) false)))

(defn- update-user [conn ctx user-id]
  (timbre/info "Updating user:" user-id)
  (if-let* [updated-user (:user-update ctx)
            update-result (user-res/update-user! conn user-id updated-user)]
    (do
      (timbre/info "Updated user:" user-id)
      {:updated-user update-result})

    (do (timbre/error "Failed updating user:" user-id) false)))

(defn- delete-user [conn user-id]
  (timbre/info "Deleting user:" user-id)
  (if (user-res/delete-user! conn user-id)
    (do (timbre/info "Deleted user:" user-id) true)
    (do (timbre/error "Failed deleting user:" user-id) false)))

(defn password-reset-request [conn email]
  (timbre/info "Password reset request for:" email)
  (if-let [user (user-res/get-user-by-email conn email)]

    (let [user-id (:user-id user)
          one-time-token (str (java.util.UUID/randomUUID))]
      (timbre/info "Adding one-time-token for:" user-id "(" email ")")
      (user-res/add-token conn user-id one-time-token)
      (timbre/info "Sending password reset request for:" user-id "(" email ")")
      (sqs/send! sqs/TokenAuth
                 (sqs/->token-auth {:type :reset :email email :token one-time-token})
                 config/aws-sqs-email-queue)
      (timbre/info "Sent password reset request for:" user-id "(" email ")"))

    (timbre/warn "Password reset request, no user for:" email)))

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

;; A resource for authenticating users by email/pass
(defresource user-auth [conn]

  :allowed-methods [:options :get]

  ;; Media type client accepts
  :available-media-types [jwt/media-type]
  :handle-not-acceptable (api-common/only-accept 406 jwt/media-type)

  ;; Authorization
  :authorized? (by-method {
    :options true
    :get (fn [ctx] (or (-> ctx :request :identity) ; Basic HTTP Auth
                       (token-auth conn (-> ctx :request :headers))))}) ; one time use token auth

  ;; Responses
  :handle-ok (fn [ctx] (when-let* [user (user-res/get-user-by-email conn (or
                                                                            (-> ctx :request :identity) ; Basic HTTP Auth
                                                                            (:email ctx))) ; one time use token auth
                                   admin-teams (user-res/admin-of conn (:user-id user))]
                        (if (= (keyword (:status user)) :pending)
                          ;; they need to verify their email, so no love
                          (api-common/blank-response)
                          ;; respond w/ JWToken and location
                          (user-rep/auth-response conn
                            (-> user
                              (assoc :admin admin-teams)
                              (assoc :slack-bots (jwt/bots-for conn user)))
                            :email)))))

;; A resource for creating users by email
(defresource user-create [conn]
  (api-common/open-company-anonymous-resource config/passphrase) ; verify validity of JWToken if it's provided

  :allowed-methods [:options :post]

  ;; Media type client accepts
  :available-media-types [jwt/media-type]
  :handle-not-acceptable (api-common/only-accept 406 jwt/media-type)

  ;; Media type client sends
  :known-content-type? (by-method {
    :options true
    :post (fn [ctx] (api-common/known-content-type? ctx mt/user-media-type))})

  ;; Validations
  :processable? (by-method {
    :options true
    :post (fn [ctx] (and (lib-schema/valid-email-address? (-> ctx :data :email))
                         (lib-schema/valid-password? (-> ctx :data :password))
                         (string? (-> ctx :data :first-name))
                         (string? (-> ctx :data :last-name))))})

  ;; Existentialism
  :exists? (fn [ctx] {:existing-user (user-res/get-user-by-email conn (-> ctx :data :email))})

  ;; Actions
  :post-to-existing? false
  :put-to-existing? true ; needed for a 409 conflict
  :conflict? :existing-user
  :post! (fn [ctx] (create-user conn (:data ctx))) 

  ;; Responses
  :handle-conflict (ring-response {:status 409})
  :handle-created (fn [ctx] (let [user (:new-user ctx)]
                              (if (= (keyword (:status user)) :pending)
                                ;; they need to verify their email, so no love
                                (api-common/blank-response)
                                ;; respond w/ JWToken and location
                                (user-rep/auth-response conn
                                  (assoc user :slack-bots (jwt/bots-for conn user))
                                  :email)))))


;; A resource for operations on a particular user
(defresource user [conn user-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get :post :patch :delete]

  ;; Media type client accepts
  :available-media-types [mt/user-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/user-media-type)

  :malformed? (by-method {
    :options false
    :get false
    :post false
    :patch (fn [ctx] (api-common/malformed-json? ctx))
    :delete false})
  
  ;; Media type client sends
  :known-content-type? (by-method {
                          :options true
                          :get true
                          :post true
                          :patch (fn [ctx] (api-common/known-content-type? ctx mt/user-media-type))
                          :delete true})

  :initialize-context (fn [ctx]
                        (or (allow-superuser-token ctx)
                            (api-common/read-token
                             (get-in ctx [:request :headers])
                             config/passphrase)))
  ;; Authorization
  :allowed? (by-method {
    :options true
    :get (fn [ctx] (allow-user-and-team-admins conn ctx user-id))
    :post (fn [ctx] (allow-user-and-team-admins conn ctx user-id))
    :patch (fn [ctx] (allow-user-and-team-admins conn ctx user-id))
    :delete (fn [ctx] (allow-user-and-team-admins conn ctx user-id))})

  ;; Validations
  :processable? (by-method {
    :get true
    :options true
    :post (fn [ctx] (can-resend-verificaiton-email? conn user-id))
    :patch (fn [ctx] (valid-user-update? conn (:data ctx) user-id))
    :delete true})

  ;; Existentialism
  :exists? (fn [ctx] (if-let [user (and (lib-schema/unique-id? user-id)
                                        (user-res/get-user conn user-id))]
                       ;; super-user gets slack bots property as well
                       (let [bots-user (if (-> ctx :jwtoken :claims :super-user)
                                          (assoc user :slack-bots (jwt/bots-for conn user))
                                          user)]
                         {:existing-user bots-user})
                       false))

  ;; Acctions
  :post! (fn [ctx] (resend-verification-email conn ctx user-id))
  :patch! (fn [ctx] (update-user conn ctx user-id))
  :delete! (fn [_] (delete-user conn user-id))

  ;; Responses
  :handle-ok (by-method {
    :get (fn [ctx] (user-rep/render-user (:existing-user ctx)))
    :post (fn [ctx] (api-common/blank-response))
    :patch (fn [ctx] (user-rep/render-user (:updated-user ctx)))})
  :handle-unprocessable-entity (fn [ctx]
    (api-common/unprocessable-entity-response (schema/check user-res/User (:user-update ctx)))))

;; A resource for refreshing JWTokens
(defresource token [conn]

  ;; Get the JWToken and ensure it checks, but don't check if it's expired (might be expired or old schema, and that's OK)
  :initialize-context (by-method {
    :get (fn [ctx]
           (let [token (api-common/get-token (get-in ctx [:request :headers]))
                 decoded-token (jwt/decode token)]
             ;; We signed the token
             (when (jwt/check-token token config/passphrase)
               (if (nil? (schema/check jwt/Claims (:claims decoded-token))) ; claims are valid
                 {:jwtoken token :user (:claims decoded-token)}
                 (let [claims (:claims decoded-token) ;; check if super user
                       user-id (:user-id claims)
                       slack-user-id (:slack-user-id claims)
                       slack-team-id (:slack-team-id claims)]
                   (when (:super-user claims)
                     (let [auth-source (if slack-user-id
                                         :slack
                                         :email)
                           user-data (if user-id
                                       (user-res/get-user conn user-id)
                                       (user-res/get-user-by-slack-id
                                            conn
                                            slack-team-id
                                            slack-user-id))]
                       (when user-data
                         (when-let* [admin-teams (user-res/admin-of
                                                  conn
                                                  (:user-id user-data))
                                     user (assoc user-data :admin admin-teams)
                                     jwt-user (user-rep/jwt-props-for user auth-source)
                                     utoken (jwtoken/generate conn jwt-user)]
                           (let [is-slack-user? (= auth-source :slack)
                                 slack-user (when is-slack-user?
                                              ((keyword slack-team-id) (:slack-users jwt-user)))
                                 slack-data-map (when is-slack-user?
                                                  {:slack-id (:id slack-user)
                                                   :slack-token (:token slack-user)})
                                 token-user (-> jwt-user
                                              (assoc :auth-source (name auth-source))
                                              (merge slack-data-map))]
                             (when token-user
                               ;; generated token
                               {:jwtoken utoken :user token-user})))))))))))})
  
  :allowed-methods [:options :get]

  ;; Media type client accepts
  :available-media-types [jwt/media-type]
  :handle-not-acceptable (api-common/only-accept 406 jwt/media-type)

  ;; Existentialism
  :exists? (fn [ctx] (if-let* [user-id (-> ctx :user :user-id)
                               user (user-res/get-user conn user-id)
                               admin-teams (user-res/admin-of conn user-id)]
                        {:existing-user (assoc user :admin admin-teams)}
                        false))

  ;; Responses
  :handle-not-found (api-common/unauthorized-response)
  :handle-ok (fn [ctx] (case (-> ctx :user :auth-source)

                        ;; Email token - respond w/ JWToken and location
                        "email" (let [user (:existing-user ctx)]
                                  (user-rep/auth-response conn
                                    (assoc user :slack-bots (jwt/bots-for conn user))
                                    :email))

                        ;; Slack token - defer to Slack API handler
                        "slack" (slack-api/refresh-token conn (:existing-user ctx)
                                                              (-> ctx :user :slack-id)
                                                              (-> ctx :user :slack-token))
                        "google" (let [user (:existing-user ctx)]
                                   (if (google/refresh-token conn user)
                                     (user-rep/auth-response
                                       conn
                                       (assoc user :slack-bots
                                              (jwt/bots-for conn user))
                                       :google)
                                     (api-common/unauthorized-response)))
                        ;; What token is this?
                        (api-common/unauthorized-response))))

;; A resource for requesting a password reset
(defresource password-reset [conn]
  (api-common/open-company-anonymous-resource config/passphrase) ; verify validity of JWToken if it's provided

  :allowed-methods [:options :post]

  ;; Media type client accepts
  :available-media-types ["*/*"]
  
  ;; Media type client sends
  :malformed? (by-method {
    :options false
    :post (fn [ctx] (malformed-email? ctx))
    :delete false})
  :known-content-type? (by-method {
    :options true
    :post (fn [ctx] (api-common/known-content-type? ctx "text/x-email"))})

  ;; Actions
  :post! (fn [ctx] (password-reset-request conn (:data ctx)))

  ;; Responses
  :handle-created (api-common/blank-response))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      ;; new email user creation
      (ANY "/users" [] (pool/with-pool [conn db-pool] (user-create conn)))
      (ANY "/users/" [] (pool/with-pool [conn db-pool] (user-create conn)))
      ;; email / token user authentication
      (ANY "/users/auth" [] (pool/with-pool [conn db-pool] (user-auth conn)))
      (ANY "/users/auth/" [] (pool/with-pool [conn db-pool] (user-auth conn)))
      ;; password reset request
      (ANY "/users/reset" [] (pool/with-pool [conn db-pool] (password-reset conn)))
      (ANY "/users/reset/" [] (pool/with-pool [conn db-pool] (password-reset conn)))
      ;; token refresh request
      (ANY "/users/refresh" [] (pool/with-pool [conn db-pool] (token conn)))
      (ANY "/users/refresh/" [] (pool/with-pool [conn db-pool] (token conn)))
      ;; user operations
      (ANY "/users/:user-id" [user-id] (pool/with-pool [conn db-pool] (user conn user-id)))
      ;; Resend verification email api
      (OPTIONS "/users/:user-id/verify" [user-id] (pool/with-pool [conn db-pool] (user conn user-id)))
      (POST "/users/:user-id/verify" [user-id] (pool/with-pool [conn db-pool] (user conn user-id))))))