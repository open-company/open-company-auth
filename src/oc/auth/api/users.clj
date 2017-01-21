(ns oc.auth.api.users
  "Liberator API for user resources."
  (:require [if-let.core :refer (when-let*)]
            [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes OPTIONS GET PATCH POST DELETE)]
            [liberator.core :refer (defresource by-method)]
            [liberator.representation :refer (ring-response)]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.lib.rethinkdb.pool :as pool]
            [oc.lib.jwt :as jwt]
            [oc.lib.api.common :as api-common]
            [oc.auth.config :as config]
            [oc.auth.resources.user :as user-res]
            [oc.auth.representations.user :as user-rep]))

;; ----- Actions -----

(defn- create-user [conn {email :email password :password :as user-props}]
  (timbre/info "Creating user" email)
  (if-let [created-user (user-res/create-user! conn (user-res/->user user-props password))]
    (do (timbre/info "Created user" email)
      {:user created-user})
    (do (timbre/error "Failed creating user" email)
      false)))

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

(defn- update-user [conn {data :data} user-id]
  (if-let [updated-user (user-res/update-user! conn user-id data)]
    {:updated-user updated-user}
    false))

;; ----- Validations -----

(defn- allow-user-and-team-admins [conn {user :user} user-id]
  (or (= user-id (:user-id user)) ; JWToken user-id matches URL user-id, user accessing themself
    false)) ; TODO admin of a team the user is on

(defn- processable-patch-req? [conn {data :data} user-id]
  (if-let [user (user-res/get-user conn user-id)]
    (try
      (schema/validate user-res/User (merge user data))
      true
      (catch clojure.lang.ExceptionInfo e
        (timbre/error e "Validation failure of user PATCH request.")
        false))
    true)) ; No user for this user-id, so this will 404 after :exists? decision

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

;; A resource for authenticating users by email/pass
(defresource user-auth [conn]

  :allowed-methods [:options :get]

  :available-media-types [jwt/media-type]
  :handle-not-acceptable (api-common/only-accept 406 jwt/media-type)

  :authorized? (by-method {:options true
                           :get (fn [ctx] (-> ctx :request :identity))})

  :handle-ok (fn [ctx] (user-rep/auth-response (user-res/get-user-by-email conn (-> ctx :request :identity))
                          :email true))) ; respond w/ JWToken and location

;; A resource for creating users by email
(defresource user-create [conn]
  (api-common/open-company-anonymous-resource config/passphrase) ; verify validity and presence of required JWToken

    :allowed-methods [:options :post]

    :available-media-types [jwt/media-type]
    :handle-not-acceptable (api-common/only-accept 406 jwt/media-type)

    :known-content-type? (by-method {
      :options true
      :get (fn [ctx] (api-common/known-content-type? ctx user-rep/media-type))})

    :exists? (fn [ctx] {:existing-user (user-res/get-user-by-email conn (-> ctx :data :email))})

    :processable? (by-method {
      :options true
      :post (fn [ctx] (and (user-res/valid-email? (-> ctx :data :email))
                           (user-res/valid-password? (-> ctx :data :password))
                           (string? (-> ctx :data :first-name))
                           (string? (-> ctx :data :last-name))))})

    :post-to-existing? false
    :put-to-existing? true ; needed for a 409 conflict
    :conflict? (fn [ctx] (:existing-user ctx))
    :handle-conflict (ring-response {:status 409})
    
    :put! (fn [ctx] (create-user conn (:data ctx))) ; POST ends up handled here so we can have a 409 conflict
    :handle-created (fn [ctx] (user-rep/auth-response (:user ctx) :email true))) ; respond w/ JWToken and location

;; A resource for operations on a particular user
(defresource user [conn user-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get :patch :delete]

  :available-media-types [user-rep/media-type]
  :handle-not-acceptable (api-common/only-accept 406 user-rep/media-type)
  
  :known-content-type? (by-method {
                          :options true
                          :get true
                          :patch (fn [ctx] (api-common/known-content-type? ctx user-rep/media-type))
                          :delete true})

  :allowed? (fn [ctx] (allow-user-and-team-admins conn ctx user-id))

  :processable? (by-method {
    :get true
    :options true
    :patch (fn [ctx] (processable-patch-req? conn ctx user-id))
    :delete true})

  :exists? (fn [ctx] (if-let [user (and (lib-schema/unique-id? user-id) (user-res/get-user conn user-id))]
                        {:existing-user user}
                        false))

  :patch! (fn [ctx] (update-user conn ctx user-id))
  :delete! (fn [_] (user-res/delete-user! conn user-id))

  :handle-ok (by-method {
    :get (fn [ctx] (user-rep/render-user (:existing-user ctx)))
    :patch (fn [ctx] (user-rep/render-user (:updated-user ctx)))}))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      ;; new email user creation
      (OPTIONS "/users" [] (pool/with-pool [conn db-pool] (user-create conn)))
      (OPTIONS "/users/" [] (pool/with-pool [conn db-pool] (user-create conn)))
      (POST "/users" [] (pool/with-pool [conn db-pool] (user-create conn)))
      (POST "/users/" [] (pool/with-pool [conn db-pool] (user-create conn)))
      ;; email user authentication
      (OPTIONS "/users/auth" [] (pool/with-pool [conn db-pool] (user-auth conn)))
      (OPTIONS "/users/auth/" [] (pool/with-pool [conn db-pool] (user-auth conn)))
      (GET "/users/auth" [] (pool/with-pool [conn db-pool] (user-auth conn)))
      (GET "/users/auth/" [] (pool/with-pool [conn db-pool] (user-auth conn)))
      ;; password reset request
      ; (OPTIONS "/users/reset" [] (pool/with-pool [conn db-pool] (password-reset conn)))
      ; (OPTIONS "/users/reset/" [] (pool/with-pool [conn db-pool] (password-reset conn)))
      ; (POST "/users/reset" [] (pool/with-pool [conn db-pool] (password-reset conn)))
      ; (POST "/users/reset/" [] (pool/with-pool [conn db-pool] (password-reset conn))))
      ;; user operations
      (OPTIONS "/users/:user-id" [user-id] (pool/with-pool [conn db-pool] (user conn user-id)))
      (GET "/users/:user-id" [user-id] (pool/with-pool [conn db-pool] (user conn user-id)))
      (PATCH "/users/:user-id" [user-id] (pool/with-pool [conn db-pool] (user conn user-id)))
      (DELETE "/users/:user-id" [user-id] (pool/with-pool [conn db-pool] (user conn user-id))))))