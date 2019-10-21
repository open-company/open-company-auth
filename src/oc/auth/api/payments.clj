(ns oc.auth.api.payments
  (:require [compojure.core :as compojure :refer [ANY]]
            [liberator.core :refer [by-method defresource]]
            [oc.auth.config :as config]
            [oc.auth.representations.media-types :as mt]
            [oc.auth.representations.payments :as payments-rep]
            [oc.auth.resources.payments :as payments-res]
            [oc.auth.resources.user :as user-res]
            [oc.auth.resources.team :as team-res]
            [oc.auth.async.payments :as pasync]
            [oc.lib.api.common :as api-common]
            [oc.lib.db.pool :as pool]
            [cheshire.core :as json]
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

;; FIXME: starting to complect with team
(defn allow-team-members
  "Return true if the JWToken user is a member of the specified team."
  [conn {user-id :user-id} team-id]
  (if-let [user (user-res/get-user conn user-id)]
    ((set (:teams user)) team-id)
    false))

(defn- plan-id-from-body
  [ctx]
  (if-let* [plan-id (-> ctx :request :body slurp (json/parse-string true) :plan-id)
            valid? (schema/validate schema/Str plan-id)]
    [false {:plan-id plan-id}]
    true))

;; ----- Actions -----

(defn create-customer-with-creator-as-contact!
  [ctx conn team-id]
  (let [creator      (:user ctx)
        contact      {:email     (:email creator)
                      :full-name (lib-user/name-for creator)}
        new-customer {:new-customer (payments-res/create-customer! conn team-id contact)}]
    (payments-res/start-plan! conn team-id config/stripe-monthly-plan-id)
    (pasync/report-team-seat-usage! conn team-id)
    new-customer))

(defn create-new-subscription!
  [ctx conn team-id]
  (when-let [plan-id (:plan-id ctx)]
    {:updated-customer (payments-res/start-plan! conn team-id plan-id)}))

(defn change-subscription-plan!
  [ctx conn team-id]
  (when-let [plan-id (:plan-id ctx)]
    {:updated-customer (payments-res/change-plan! conn team-id plan-id)}))

(defn cancel-subscription!
  [ctx conn team-id]
  {:updated-customer (payments-res/cancel-subscription! conn team-id)})

;; ----- Resources -----

(defresource customer [conn team-id]
  (api-common/open-company-authenticated-resource config/passphrase)

  :allowed-methods [:options :get :put :patch :delete]

  ;; Media type client accepts
  :available-media-types [mt/payment-customer-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/payment-customer-media-type)

  ;; Media type client sends
  :known-content-type? (by-method {
    :options true
    :get true
    :put true
    :patch true
    :delete true})

  ;; Authorization
  :allowed? (by-method {
    :options true
    :get (fn [ctx] (allow-team-members conn (:user ctx) team-id))
    :put (fn [ctx] (allow-team-admins conn (:user ctx) team-id))
    :patch (fn [ctx] (allow-team-admins conn (:user ctx) team-id))
    :delete (fn [ctx] (allow-team-admins conn (:user ctx) team-id))
    })

  ;; Validations
  :processable? (by-method {
    :options true
    :get true
    :put (fn [ctx] (nil? (-> ctx :existing-customer :subscription)))
    :patch (fn [ctx] (some? (-> ctx :existing-customer :subscription)))
    :delete (fn [ctx] (some? (-> ctx :existing-customer :subscription)))
    })

  :malformed? (by-method {
    :options false
    :get false
    :put plan-id-from-body
    :patch plan-id-from-body
    :delete plan-id-from-body})

  ;; Existentialism
  :exists? (fn [ctx] (if-let [customer (payments-res/get-customer conn team-id)]
                       {:existing-customer customer}
                       ;; on-demand creation
                       (create-customer-with-creator-as-contact! ctx conn team-id)))

  ;; Actions
  :put! (fn [ctx] (create-new-subscription! ctx conn team-id))
  :patch! (fn [ctx] (change-subscription-plan! ctx conn team-id))
  :delete! (fn [ctx] (cancel-subscription! ctx conn team-id))

  ;; Responses
  :handle-ok (fn [ctx] (let [customer (or (:new-customer ctx)
                                          (:existing-customer ctx)
                                          (:updated-customer ctx))]
                         (payments-rep/render-customer team-id customer)))
  )

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
     (ANY "/teams/:team-id/customer" [team-id] (pool/with-pool [conn db-pool] (customer conn team-id)))
     )))
