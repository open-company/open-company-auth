(ns oc.auth.api.payments
  (:require [compojure.core :as compojure :refer [ANY]]
            [liberator.core :refer [by-method defresource]]
            [oc.auth.config :as config]
            [oc.auth.representations.media-types :as mt]
            [oc.auth.representations.payments :as payments-rep]
            [oc.auth.resources.payments :as payments-res]
            [oc.auth.resources.team :as team-res]
            [oc.lib.api.common :as api-common]
            [oc.lib.db.pool :as pool]
            [oc.lib.user :as lib-user]))

;; ----- Validations -----

;; FIXME: starting to complect with team
(defn allow-team-admins
  "Return true if the JWToken user is an admin of the specified team."
  [conn {user-id :user-id} team-id]
  (if-let [team (team-res/get-team conn team-id)]
    ((set (:admins team)) user-id)
    false))

;; ----- Actions -----

(defn create-customer-with-creator-as-contact!
  [ctx conn team-id]
  (let [creator (:user ctx)
        contact {:email     (:email creator)
                 :full-name (lib-user/name-for creator)}]
    {:new-customer (payments-res/create-customer! conn team-id contact)}))

(defn create-new-subscription!
  [ctx conn team-id plan-id]
  {:new-subscription (payments-res/start-plan! conn team-id plan-id)})

(defn change-subscription-plan!
  [ctx conn team-id plan-id]
  {:updated-subscription (payments-res/change-plan! conn team-id plan-id)})

(defn cancel-subscription!
  [ctx conn team-id]
  {:canceled-subscription (payments-res/cancel-subscription! conn team-id)})

;; ----- Resources -----

(defresource customer [conn team-id]
  (api-common/open-company-authenticated-resource config/passphrase)

  :allowed-methods [:options :get :post]

  ;; Media type client accepts
  :available-media-types [mt/payment-customer-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/payment-customer-media-type)

  ;; Media type client sends
  :known-content-type? (by-method {
    :options true
    :get true
    :post true})

  ;; Authorization
  :allowed? (by-method {
    :options true
    :get (fn [ctx] (allow-team-admins conn (:user ctx) team-id))
    :post (fn [ctx] (allow-team-admins conn (:user ctx) team-id))})

  ;; Validations
  :processable? (by-method {
    :options true
    :get true
    :post (fn [_] (nil? (payments-res/get-customer conn team-id)))})

  ;; Existentialism
  :exists? (fn [ctx] (if-let [customer (payments-res/get-customer conn team-id)]
                        {:existing-customer customer}
                        false))

  ;; Actions
  :post! (fn [ctx] (create-customer-with-creator-as-contact! ctx conn team-id))

  ;; Responses
  :handle-ok (fn [ctx] (let [customer (or (:existing-customer ctx)
                                          (:new-customer ctx))]
                          (payments-rep/render-customer customer)))
  )

(defresource subscription [conn team-id plan-id]
  (api-common/open-company-authenticated-resource config/passphrase)

  :allowed-methods [:options :put :patch :delete]

  ;; Media type client accepts
  :available-media-types [mt/payment-subscription-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/payment-subscription-media-type)

  ;; Media type client sends
  :known-content-type? (by-method {
    :options true
    :get true
    :post true})

  ;; Authorization
  :allowed? (by-method {
    :options true
    :get (fn [ctx] (allow-team-admins conn (:user ctx) team-id))
    :post (fn [ctx] (allow-team-admins conn (:user ctx) team-id))})

  ;; Validations
  :processable? (by-method {
    :options true
    :put true
    :patch true
    :delete true})

  ;; Actions
  :put! (fn [ctx] (create-new-subscription! ctx conn team-id plan-id))
  :path! (fn [ctx] (change-subscription-plan! ctx conn team-id plan-id))
  :delete! (fn [ctx] (cancel-subscription! ctx conn team-id))


  ;; Responses
  :handle-ok (fn [ctx] (let [subscription (or (:new-subscription ctx)
                                              (:updated-subscription ctx)
                                              (:canceled-subscription ctx))]
                         (payments-rep/render-subscription subscription)))
  )

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
     (ANY "/teams/:team-id/customer" [team-id] (pool/with-pool [conn db-pool] (customer conn team-id)))
     (ANY "/teams/:team-id/customer/:plan-id" [team-id plan-id] (pool/with-pool [conn db-pool] (subscription conn team-id plan-id)))
     )))
