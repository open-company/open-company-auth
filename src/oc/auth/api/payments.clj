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
            [oc.lib.user :as lib-user]
            [if-let.core :refer (if-let*)]
            [schema.core :as schema]))

;; ----- Validations -----

;; FIXME: starting to complect with team
(defn allow-team-admins
  "Return true if the JWToken user is an admin of the specified team."
  [conn {user-id :user-id} team-id]
  (if-let [team (team-res/get-team conn team-id)]
    ((set (:admins team)) user-id)
    false))

(defn- plan-id-from-body
  [ctx]
  (if-let* [plan-id (-> ctx :request :body slurp)
            valid? (schema/validate schema/Str plan-id)]
    [false {:plan-id plan-id}]
    true))

;; ----- Actions -----

(defn create-customer-with-creator-as-contact!
  [ctx conn team-id]
  (let [creator (:user ctx)
        contact {:email     (:email creator)
                 :full-name (lib-user/name-for creator)}]
    {:new-customer (payments-res/create-customer! conn team-id contact)}))

(defn create-new-subscription!
  [ctx conn team-id]
  (when-let [plan-id (:plan-id ctx)]
    {:new-subscription (payments-res/start-plan! conn team-id plan-id)}))

(defn change-subscription-plan!
  [ctx conn team-id]
  (when-let [plan-id (:plan-id ctx)]
    {:updated-subscription (payments-res/change-plan! conn team-id plan-id)}))

(defn cancel-subscription!
  [ctx conn team-id]
  {:canceled-subscription (payments-res/cancel-subscription! conn team-id)})

;; ----- Resources -----

(defresource customer [conn team-id]
  (api-common/open-company-authenticated-resource config/passphrase)

  :allowed-methods [:options :get :post :put :path :delete]

  ;; Media type client accepts
  :available-media-types [mt/payment-customer-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/payment-customer-media-type)

  ;; Media type client sends
  :known-content-type? (by-method {
    :options true
    :get true
    :post true
    :put true
    :patch true
    :delete true})

  ;; Authorization
  :allowed? (by-method {
    :options true
    :get (fn [ctx] (allow-team-admins conn (:user ctx) team-id))
    :post (fn [ctx] (allow-team-admins conn (:user ctx) team-id))
    :put (fn [ctx] (allow-team-admins conn (:user ctx) team-id))
    :patch (fn [ctx] (allow-team-admins conn (:user ctx) team-id))
    :delete (fn [ctx] (allow-team-admins conn (:user ctx) team-id))
    })

  ;; Validations
  :processable? (by-method {
    :options true
    :get true
    :post (fn [ctx] (nil? (:existing-customer ctx)))
    :put (fn [ctx] (nil? (-> ctx :existing-customer :subscription)))
    :patch (fn [ctx] (some? (-> ctx :existing-customer :subscription)))
    :delete (fn [ctx] (some? (-> ctx :existing-customer :subscription)))
    })

  :malformed? (by-method {
    :options false
    :get false
    :post false
    :put plan-id-from-body
    :patch plan-id-from-body
    :delete plan-id-from-body})

  ;; Existentialism
  :exists? (fn [ctx] (if-let [customer (payments-res/get-customer conn team-id)]
                        {:existing-customer customer}
                        false))

  ;; Actions
  :post! (fn [ctx] (create-customer-with-creator-as-contact! ctx conn team-id))
  :put! (fn [ctx] (create-new-subscription! ctx conn team-id))
  :patch! (fn [ctx] (change-subscription-plan! ctx conn team-id))
  :delete! (fn [ctx] (cancel-subscription! ctx conn team-id))

  ;; Responses
  :handle-ok (fn [ctx] (let [customer (payments-res/get-customer conn team-id)]
                          (payments-rep/render-customer team-id customer)))
  )

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
     (ANY "/teams/:team-id/customer" [team-id] (pool/with-pool [conn db-pool] (customer conn team-id)))
     )))
