(ns oc.auth.lib.stripe
  (:require [clojure.set :as cset]
            [oc.auth.config :as config])
  (:import [com.stripe Stripe]
           [com.stripe.model Customer Subscription SubscriptionItem Plan Invoice SetupIntent PaymentMethod]
           [com.stripe.model.checkout Session]
           [java.util Date]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration

(set! (Stripe/apiKey) config/stripe-secret-key)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation details

(defn- date->timestamp
  "Converts a java.util.Date object to a Stripe timestamp"
  [java-date]
  (quot (.getTime java-date) 1000))

(defn- stripe-now
  "Timestamp of the moment this function is called according to Stripe"
  []
  (date->timestamp (Date.)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity conversion

(defn- retrieve-customer-by-id
  "Queries Stripe to retrieve the Customer record with the given Stripe ID"
  [cid]
  (Customer/retrieve cid))

(defn- retrieve-upcoming-invoice
  [cid]
  (Invoice/upcoming {"customer" cid}))

(defn- retrieve-available-plans
  [product-id]
  (let [is-public? #(-> % .getMetadata (get "isPublic"))]
    (->> (Plan/list {"product" product-id})
         (.getData)
         (filter is-public?))))

(defn- convert-plan
  [plan]
  {:id       (.getId plan)
   :amount   (.getAmount plan)
   :nickname (.getNickname plan)
   :currency (.getCurrency plan)
   :active   (.getActive plan)
   :interval (.getInterval plan)})

(defn- convert-subscription-item
  [sub-item]
  {:id (.getId sub-item)})

(defn- convert-invoice-line-item
  [line-item]
  {:id          (.getId line-item)
   :amount      (.getAmount line-item)
   :currency    (.getCurrency line-item)
   :description (.getDescription line-item)
   :quantity    (.getQuantity line-item)})

(defn- convert-invoice
  [invoice]
  (let [line-items (-> invoice .getLines .getData)]
    {:amount-due           (.getAmountDue invoice)
     :currency             (.getCurrency invoice)
     :total                (.getTotal invoice)
     :subtotal             (.getSubtotal invoice)
     :next-payment-attempt (.getNextPaymentAttempt invoice)
     :line-items           (mapv convert-invoice-line-item line-items)
     }))

(defn- convert-subscription
  [sub]
  (let [item             (-> sub .getItems .getData first)
        usage-summary    (-> item .usageRecordSummaries .getData first)
        upcoming-invoice (retrieve-upcoming-invoice (.getCustomer sub))]
    {:id                   (.getId sub)
     :status               (.getStatus sub)
     :quantity             (.getQuantity sub)
     :trial-start          (.getTrialStart sub)
     :trial-end            (.getTrialEnd sub)
     :current-period-start (.getCurrentPeriodStart sub)
     :current-period-end   (.getCurrentPeriodEnd sub)
     :current-plan         (convert-plan (.getPlan sub))
     :item                 (convert-subscription-item item)
     :upcoming-invoice     (convert-invoice upcoming-invoice)
     }))

(defn- convert-customer
  [customer]
  (let [sub         (-> customer .getSubscriptions .getData first)
        avail-plans (retrieve-available-plans config/stripe-premium-product-id)]
    (cond-> {:id           (.getId customer)
             :email        (.getEmail customer)
             :full-name    (.getName customer)
             :available-plans (mapv convert-plan avail-plans)}
      sub (assoc :subscription (convert-subscription sub)))))

(defn- convert-checkout-session
  [session]
  {:sessionId (.getId session)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stripe API

(defn customer-info
  "Retrieves a summary of the given customer."
  [customer-id]
  (try
    (let [customer (retrieve-customer-by-id customer-id)]
      (convert-customer customer))
    (catch Exception e nil)))

(defn create-stripe-customer!
  "Creates a Customer object in the Stripe API and returns its Stripe representation.
  Takes optional metadata to be stored in the Stripe Customer record as a map of
  string-keys to string values. Be sure to store the returned :id for later recall."
  [{:keys [email full-name]} & [metadata]]
  (-> (Customer/create
       (cond->
           {"email"       email
            "name"        full-name
            "description" "Created by oc.auth.api.payments"}
         (some? metadata) (assoc "metadata" metadata)))
      convert-customer))

(defn subscribe-customer-to-plan!
  "Takes an optional `opts` map, which can include:
    - `:trial-period-days` number of trial days. if not included, will fallback to the default trial days of the plan"
  [customer-id plan-id & [opts]]
  (if (-> (customer-info customer-id) :subscription)
    (throw (ex-info "Customer already has a primary subscription, cannot subscribe again"
                    {:customer-id customer-id}))
    (let [{:keys [trial-period-days]} opts
          params  (cond-> {"customer" customer-id
                           "items" [{"plan" plan-id}]}
                    (some? trial-period-days) (assoc "trial_period_days" trial-period-days)
                    (nil? trial-period-days)  (assoc "trial_from_plan" true))]
      (-> (Subscription/create params)
          convert-subscription))))

(defn report-seats!
  [customer-id num-seats]
  (let [customer (customer-info customer-id)]
    (if-let [sub-item-id (-> customer :subscription :item :id)]
      (let [sub-item   (SubscriptionItem/retrieve sub-item-id)
            new-params {"quantity" num-seats}]
        (-> (.update sub-item new-params)
            convert-subscription-item))
      (throw (ex-info "Attempted to report seat usage on non-existent plan"
                      {:customer-id customer-id
                       :num-seats num-seats})))))

(defn change-plan!
  "Changes the customer's current plan to the new plan with the given ID"
  [customer-id new-plan-id]
  (let [customer (customer-info customer-id)]
    (if-let [sub-item-id (-> customer :subscription :item :id)]
      (if-not (= new-plan-id (-> customer :subscription :current-plan :id))
        (let [sub-item   (SubscriptionItem/retrieve sub-item-id)
              new-params {"plan"     new-plan-id
                          "quantity" (.getQuantity sub-item)}]
          (-> (.update sub-item new-params)
              convert-subscription-item))
        (throw (ex-info "Cannot change to the current plan"
                        {:customer-id customer-id
                         :new-plan-id new-plan-id})))
      (throw (ex-info "Attempted to change non-existent plan"
                      {:customer-id customer-id
                       :new-plan-id new-plan-id})))))

(defn cancel-subscription!
  "Flags the customer's current subscription for cancellation at the end of its billing cycle."
  [customer-id]
  (if-let [sub-id (-> customer-id customer-info :subscription :id)]
    (let [sub    (Subscription/retrieve sub-id)
          params {"cancel_at_period_end" true}]
      (-> (.update sub params)
          convert-subscription))
    (throw (ex-info "Cannot cancel non-existent plan"
                    {:customer-id customer-id}))))

(defn create-checkout-session!
  "Creates a Checkout Session: the context from which we obtain a customer's payment info.
  See https://stripe.com/docs/payments/checkout/collecting"
  [customer-id {:keys [success-url
                       cancel-url]}]
  (-> (Session/create {"payment_method_types" ["card"]
                       "mode"                 "setup"
                       "success_url"          success-url
                       "cancel_url"           cancel-url
                       "client_reference_id"  customer-id})
      convert-checkout-session))

(defn finish-checkout-session!
  "Callback for the session created with `create-checkout-session!`. Once customer
  has entered payment method info, this callback should be invoked in order to
  associate this data with the customer record in Stripe."
  [session-id]
  (let [session       (Session/retrieve session-id)
        customer-id   (.getClientReferenceId session)
        intent-id     (.getSetupIntent session)
        intent        (SetupIntent/retrieve intent-id)
        pay-method-id (.getPaymentMethod intent)
        pay-method    (PaymentMethod/retrieve pay-method-id)]
    (.attach pay-method {"customer" customer-id})
    (customer-info customer-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REPL testing

(comment

  (def my-id
    (-> {:email     "test@example.com"
         :full-name "Test Example"}
        create-stripe-customer!
        :id))

  (retrieve-customer-by-id my-id)

  (retrieve-upcoming-invoice my-id)

  (retrieve-available-plans config/stripe-premium-product-id)

  (customer-info my-id)

  (subscribe-customer-to-plan! my-id config/stripe-default-plan-id)

  (change-plan! my-id config/stripe-annual-plan-id)

  (cancel-subscription! my-id)

  (report-seats! my-id 29)

  (create-checkout-session! my-id {:success-url "https://staging-auth.carrot.io/team/123/customer/checkout-session"
                                   :cancel-url  "https://staging-auth.carrot.io/team/123/customer/checkout-session/cancel"})

  )
