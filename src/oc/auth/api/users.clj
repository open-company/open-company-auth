(ns oc.auth.api.users
  "Liberator API for users resource."
  (:require [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes OPTIONS GET POST)]
            [liberator.core :refer (defresource by-method)]
            [liberator.representation :refer (ring-response)]
            [oc.lib.rethinkdb.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.auth.representations.user :as user-rep]
            [oc.auth.resources.user :as user-res]))

;; ----- Actions -----

(defn- create-user [conn {email :email password :password :as user-props}]
  (timbre/info "Creating user" email)
  (if-let [created-user (user-res/create-user! conn (user-res/->user user-props password))]
    (do (timbre/info "Created user" email)
      {:user created-user})
    (do (timbre/error "Failed creating user" email)
      false)))

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

(defresource user-create [conn]
  api-common/open-company-anonymous-resource

    :available-media-types [user-rep/media-type]
    :known-content-type? (fn [ctx] (api-common/known-content-type? ctx user-rep/media-type))
    :allowed-methods [:options :post]

    :processable? (by-method {
      :options true
      :post (fn [ctx] (and (user-res/valid-email? (-> ctx :data :email))
                           (user-res/valid-password? (-> ctx :data :password))
                           (string? (-> ctx :data :first-name))
                           (string? (-> ctx :data :last-name))))})

    :post-to-existing? false
    :put-to-existing? true ; needed for a 409 conflict
    :exists? (fn [ctx] {:existing-user (user-res/get-user-by-email conn (-> ctx :data :email))})
    :conflict? (fn [ctx] (:existing-user ctx))
    :handle-conflict (ring-response {:status 409})
    
    :put! (fn [ctx] (create-user conn (:data ctx))) ; POST ends up handled here so we can have a 409 conflict
    :handle-created (fn [ctx] (user-rep/auth-response (:user ctx) :email true))) ; respond w/ JWToken and location

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      ;; new email user creation
      (OPTIONS "/users" [] (pool/with-pool [conn db-pool] (user-create conn)))
      (OPTIONS "/users/" [] (pool/with-pool [conn db-pool] (user-create conn)))
      (POST "/users" [] (pool/with-pool [conn db-pool] (user-create conn)))
      (POST "/users/" [] (pool/with-pool [conn db-pool] (user-create conn)))
      ;; password reset request
      ; (OPTIONS "/users/reset" [] (pool/with-pool [conn db-pool] (password-reset conn)))
      ; (OPTIONS "/users/reset/" [] (pool/with-pool [conn db-pool] (password-reset conn)))
      ; (POST "/users/reset" [] (pool/with-pool [conn db-pool] (password-reset conn)))
      ; (POST "/users/reset/" [] (pool/with-pool [conn db-pool] (password-reset conn))))
    )))