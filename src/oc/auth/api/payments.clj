(ns oc.auth.api.payments
  (:require [compojure.core :as compojure :refer [ANY]]
            [liberator.core :refer [by-method defresource]]
            [ring.util.response :as response]
            [oc.auth.config :as config]
            [oc.auth.representations.media-types :as mt]
            [oc.auth.representations.payments :as payments-rep]
            [oc.auth.resources.payments :as payments-res]
            [oc.auth.async.payments :as payments-async]
            [oc.auth.resources.user :as user-res]
            [oc.auth.resources.team :as team-res]
            [oc.lib.api.common :as api-common]
            [oc.lib.db.pool :as pool]
            [cheshire.core :as json]
            [oc.lib.user :as lib-user]
            [if-let.core :refer (if-let*)]
            [schema.core :as schema]
            [clojure.edn :as edn]
            [ring.util.codec :as codec]
            [ring.util.request :as request]
            [clojure.walk :refer (keywordize-keys)]
            [taoensso.timbre :as timbre])
  (:import [java.util Base64]
           [java.net URL]))

;; ----- URLs ------

(def ^:private checkout-session-success-path "/payments/callbacks/checkout-session/success")
(def ^:private checkout-session-cancel-path "/payments/callbacks/checkout-session/cancel")

;; ----- Utilities -----

;; From https://github.com/ring-clojure/ring/blob/master/ring-core/src/ring/middleware/params.clj
(defn- parse-params [params encoding]
  (let [params (codec/form-decode params encoding)]
    (if (map? params)
      (keywordize-keys params)
      {})))

;; ----- Validations -----

;; FIXME: starting to complect with team
(defn- allow-team-admins
  "Return true if the JWToken user is an admin of the specified team."
  [conn {user-id :user-id} team-id]
  (if-let [team (team-res/get-team conn team-id)]
    ((set (:admins team)) user-id)
    false))

;; FIXME: starting to complect with team
(defn- allow-team-members
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

(defn- get-checkout-redirects-from-body
  [ctx]
  (if-let* [redirects (-> ctx :request :body slurp (json/parse-string true) (select-keys [:success-url :cancel-url]))
            valid? (schema/validate {:success-url schema/Str
                                     :cancel-url  schema/Str} redirects)]
    [false {:checkout-redirects redirects}]
    true))

;; ----- Actions -----

(defn- create-customer-with-admin-as-contact!
  [ctx conn team-id]
  (timbre/info "Creating customer for team" team-id)
  (let [;; FIXME: starting to complect with team
        team         (team-res/get-team conn team-id)
        first-admin  (->> team :admins first (user-res/get-user conn))
        contact      {:email     (:email first-admin)
                      :full-name (lib-user/name-for first-admin)}
        new-customer (payments-res/create-customer! conn team-id contact)]
    (payments-res/start-new-trial! conn team-id config/stripe-default-plan-id)
    (payments-async/report-team-seat-usage! conn team-id)
    (timbre/info "Customer created" (:id new-customer) "with email" (:email contact))
    {:new-customer new-customer}))

(defn- schedule-plan-change!
  [ctx conn team-id]
  (when-let [plan-id (:plan-id ctx)]
    (payments-res/schedule-new-subscription! conn team-id plan-id)
    (timbre/info "Scheduled plan change for team" team-id "to plan" plan-id)
    {:updated-customer (payments-res/get-customer conn team-id)}))

(defn- cancel-subscription!
  [ctx conn team-id]
  (payments-res/cancel-subscription! conn team-id)
  (timbre/info "Cancelled subscription for team" team-id)
  {:updated-customer (payments-res/get-customer conn team-id)})

(defn- wrap-redirect-urls
  [redirect-urls]
  (let [encode          #(.. (Base64/getUrlEncoder)
                             (encodeToString (-> % pr-str .getBytes)))
        wrap-url        (fn [path]
                          (str config/auth-server-url path "?sessionId={CHECKOUT_SESSION_ID}&state=" (encode redirect-urls)))
        wrapped-success (wrap-url checkout-session-success-path)
        wrapped-cancel  (wrap-url checkout-session-cancel-path)]
    {:success-url wrapped-success
     :cancel-url  wrapped-cancel}))

(defn- create-checkout-session!
  [ctx conn team-id]
  (timbre/info "Creating checkout session for team" team-id)
  (let [redirects    (:checkout-redirects ctx)
        callbacks    (wrap-redirect-urls redirects)
        session      (payments-res/create-checkout-session! conn team-id callbacks)]
    {:new-session session}))

;; ----- Resources -----

(defresource customer [conn team-id]
  (api-common/open-company-authenticated-resource config/passphrase)

  :allowed-methods [:options :get :post :delete]

  ;; Media type client accepts
  :available-media-types [mt/payment-customer-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/payment-customer-media-type)

  ;; Media type client sends
  :known-content-type? (by-method {
    :options true
    :get true
    :post true
    :delete true})

  ;; Authorization
  :allowed? (by-method {
    :options true
    :get (fn [ctx] (allow-team-members conn (:user ctx) team-id))
    :post (fn [ctx] (allow-team-admins conn (:user ctx) team-id))
    :delete (fn [ctx] (allow-team-admins conn (:user ctx) team-id))
    })

  :malformed? (by-method {
    :options false
    :get false
    :post plan-id-from-body
    :delete false})

  ;; Existentialism
  :exists? (fn [ctx] (if-let [customer (payments-res/get-customer conn team-id)]
                       {:existing-customer customer}
                       ;; on-demand creation
                       (create-customer-with-admin-as-contact! ctx conn team-id)))

  ;; Actions
  :post! (fn [ctx] (schedule-plan-change! ctx conn team-id))
  :delete! (fn [ctx] (cancel-subscription! ctx conn team-id))

  :respond-with-entity? true

  ;; Responses
  :handle-ok (fn [ctx] (let [customer (or (:new-customer ctx)
                                          (:updated-customer ctx)
                                          (:existing-customer ctx))]
                         (payments-rep/render-customer team-id customer)))

  :handle-created (fn [ctx] (let [customer (:updated-customer ctx)]
                              (payments-rep/render-customer team-id customer)))
  )

;; https://stripe.com/docs/payments/checkout/collecting
;; https://stripe.com/docs/api/checkout/sessions/create
(defresource checkout-session [conn team-id]
  (api-common/open-company-authenticated-resource config/passphrase)

  :allowed-methods [:options :post]

  ;; Media type client accepts
  :available-media-types [mt/payment-checkout-session-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/payment-checkout-session-media-type)

  ;; Media type client sends
  :known-content-type? (by-method {
    :options true
    :post true
    })

  ;; Authorization
  :allowed? (by-method {
    :options true
    :post (fn [ctx] (allow-team-admins conn (:user ctx) team-id))
    })

  ;; Validations
  :processable? (by-method {
    :options true
    :post true
    })

  :malformed? (by-method {
    :options false
    :post get-checkout-redirects-from-body
    })

  ;; Actions
  :post! (fn [ctx] (create-checkout-session! ctx conn team-id))

  ;; Responses
  :handle-created (fn [ctx] (let [session (:new-session ctx)]
                              (payments-rep/render-checkout-session session)))
  )

(defn checkout-session-success [conn req session-id state]
  (timbre/info "Checkout success redirect" session-id)
  (let [decoder   (Base64/getUrlDecoder)
        redirects (->> state (.decode decoder) (String.) edn/read-string)
        success-url (:success-url redirects)
        parsed-success-url (URL. success-url)
        encoding (or (request/character-encoding req)
                     "UTF-8")
        success-url-params (parse-params (.getQuery parsed-success-url) encoding)
        current-customer (payments-res/customer-from-session session-id)
        current-subscription (-> current-customer :subscriptions first)
        should-switch-plan? (and (seq (:update-plan success-url-params))
                                 (= (count (:subscriptions current-customer)) 1)
                                 (= (:status current-subscription) "trialing")
                                 (not= (-> current-subscription :plan :id) (:update-plan success-url-params))
                                 (some #(when (= (:id %) (:update-plan success-url-params)) %) (:available-plans current-customer)))]
    (timbre/info "Checkout session with switch plan" (:update-plan success-url-params) "will update?" should-switch-plan?)
    ;; In case they want to switch to a different plan
    ;; while they are still on trial (and so have only one subscription still)
    ;; and the new plan does exist
    (when should-switch-plan?
      (payments-res/update-subscription-item-plan! (-> current-subscription :item :id) (:update-plan success-url-params)))
    ;; finish checkout to actually charge the newly added card
    (payments-res/finish-checkout-session! conn session-id)
    ;; finish the trial period if still trialing
    (payments-res/end-trial-period! conn (:id current-customer))
    (timbre/info "Checkout success redirecting to web UI for" session-id)
    (response/redirect (:success-url redirects))))

(defn checkout-session-cancel [conn session-id state]
  (timbre/info "Checkout cancel redirect" session-id)
  (let [decoder   (Base64/getUrlDecoder)
        redirects (->> state (.decode decoder) (String.) edn/read-string)]
    (response/redirect (:cancel-url redirects))))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
     (ANY "/teams/:team-id/customer"
          [team-id]
          (pool/with-pool [conn db-pool] (customer conn team-id)))

     (ANY "/teams/:team-id/customer/subscribe"
          [team-id]
          (pool/with-pool [conn db-pool] (customer conn team-id)))

     (ANY "/teams/:team-id/customer/checkout-session"
          [team-id]
          (pool/with-pool [conn db-pool] (checkout-session conn team-id)))

     (ANY checkout-session-success-path
          [sessionId state :as req] ;; obtained from query params
          (pool/with-pool [conn db-pool] (checkout-session-success conn req sessionId state)))

     (ANY checkout-session-cancel-path
          [sessionId state] ;; obtained from query params
          (pool/with-pool [conn db-pool] (checkout-session-cancel conn sessionId state))))))
