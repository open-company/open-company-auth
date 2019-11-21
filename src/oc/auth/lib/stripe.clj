(ns oc.auth.lib.stripe
  (:require [oc.auth.config :as config])
  (:import [com.stripe Stripe]
           [com.stripe.model Customer Subscription SubscriptionItem Plan Invoice SetupIntent PaymentMethod]
           [com.stripe.model.checkout Session]
           [java.util Date]))

;; ----- Configuration -----

(set! (Stripe/apiKey) config/stripe-secret-key)

;; ----- Invoice -----

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

(defn get-upcoming-invoice
  [customer-id]
  (try
    (convert-invoice
     (Invoice/upcoming {"customer" customer-id}))
    (catch Exception e nil)))

(defn create-invoice!
  [customer-id & [opts]]
  (convert-invoice
   (Invoice/create (merge {"customer" customer-id} opts))))

;; ----- Plan -----

(defn- convert-tier
  [tier]
  {:flat-amount (.getFlatAmount tier)
   :unit-amount (.getUnitAmount tier)
   :up-to       (.getUpTo tier)})

(defn- convert-plan
  [plan]
  {:id       (.getId plan)
   :amount   (.getAmount plan)
   :nickname (.getNickname plan)
   :currency (.getCurrency plan)
   :active   (.getActive plan)
   :interval (.getInterval plan)
   :tiers    (mapv convert-tier (.getTiers plan))
   :public?  (-> plan .getMetadata (get "isPublic") (= "true"))})

(defn list-plans
  "Returns a list of available plans of the given product."
  [product-id]
  (->> (Plan/list {"product" product-id
                   "limit"   100})
       (.getData)
       (mapv convert-plan)))

(defn list-public-plans
  "Returns a list of available plans of the given product that have
  `isPublic=true` set in their metadata map."
  [product-id]
  (filter :public? (list-plans product-id)))

;; ----- SubscriptionItem -----

(defn- convert-subscription-item
  [sub-item]
  {:id (.getId sub-item)})

(defn get-subscription-item
  [item-id]
  (convert-subscription-item
   (SubscriptionItem/retrieve item-id)))

(defn update-subscription-item!
  "Updates the plan or quantity of an item on a current subscription.
  See https://stripe.com/docs/api/subscription_items/update?lang=java for options."
  [item-id opts]
  (let [sub-item (SubscriptionItem/retrieve item-id)]
    (convert-subscription-item
     (.update sub-item opts))))

(defn update-subscription-item-plan!
  [item-id new-plan-id]
  (update-subscription-item! item-id {"plan" new-plan-id}))

(defn delete-subscription-item!
  [item-id]
  (let [item (SubscriptionItem/retrieve item-id)]
    (convert-subscription-item
     (.delete item))))

;; ----- Subscription -----

(defn- convert-subscription
  [sub]
  (let [item (-> sub .getItems .getData first)]
    {:id                    (.getId sub)
     :status                (.getStatus sub)
     :quantity              (.getQuantity sub)
     :trial-start           (.getTrialStart sub)
     :trial-end             (.getTrialEnd sub)
     :current-period-start  (.getCurrentPeriodStart sub)
     :current-period-end    (.getCurrentPeriodEnd sub)
     :plan                  (convert-plan (.getPlan sub))
     :item                  (convert-subscription-item item)
     :cancel-at-period-end? (.getCancelAtPeriodEnd sub)
     }))

(defn get-subscription
  [sub-id]
  (convert-subscription
   (Subscription/retrieve sub-id)))

(defn trialing?
  [{:keys [status] :as sub}]
  (= status "trialing"))

(defn active?
  [{:keys [status] :as sub}]
  (= status "active"))

(defn canceled?
  [{:keys [status cancel-at-period-end?] :as sub}]
  (or (= status "canceled")
      cancel-at-period-end?))

(defn create-subscription!
  "Subscribes the customer to the given plan and returns the subscription.
  See https://stripe.com/docs/api/subscriptions/create?lang=java for options."
  [customer-id plan-id & [opts item-opts]]
  (let [items     [(merge {"plan" plan-id}
                          item-opts)]
        sub (merge {"customer" customer-id
                    "items"    items
                    "prorate"  false}
                   opts)]
    (convert-subscription
     (Subscription/create sub))))

(defn start-trial!
  [customer-id plan-id & [opts item-opts]]
  (let [trial-opts {"trial_from_plan" true}]
    (create-subscription! customer-id plan-id (merge opts trial-opts) item-opts)))

(defn update-subscription!
  [sub-id opts]
  (let [sub (Subscription/retrieve sub-id)]
    (convert-subscription
     (.update sub (merge {"prorate" false}
                         opts)))))

(defn update-subscription-quantity!
  [sub-id quantity & [opts]]
  (update-subscription! sub-id (merge {"quantity" quantity}
                                      opts)))

(defn flag-sub-for-cancellation!
  "Flags the customer's current subscription for cancellation at the end of its billing cycle."
  [sub-id]
  (update-subscription! sub-id {"cancel_at_period_end" true}))

(defn cancel-sub-now!
  "Cancels a subscription immediately."
  [sub-id]
  (let [sub (Subscription/retrieve sub-id)]
    (.cancel sub {"invoice_now" false
                  "prorate"     false})))

;; ----- Payment Method -----

(defn- convert-payment-method
  [pay-method]
  (let [pm-id (.getId pay-method)
        card  (.getCard pay-method)]
    {:id       pm-id
     :created  (.getCreated pay-method)
     :card     {:brand     (.getBrand card)
                :exp-year  (.getExpYear card)
                :exp-month (.getExpMonth card)
                :last-4    (.getLast4 card)
                :country   (.getCountry card)}}))

(defn list-payment-methods
  [customer-id]
  (let [default-pm-id (some-> customer-id Customer/retrieve .getInvoiceSettings .getDefaultPaymentMethod)
        is-default?   #(= default-pm-id (:id %))]
    (->> (PaymentMethod/list {"customer" customer-id
                              "type"     "card"})
         .getData
         (mapv (comp #(assoc % :default? (is-default? %))
                     convert-payment-method)))))

;; ----- Customer -----

(defn- convert-customer
  [customer]
  (let [subs (some->> customer .getSubscriptions .getData (mapv convert-subscription))]
    {:id            (.getId customer)
     :email         (.getEmail customer)
     :full-name     (.getName customer)
     :subscriptions (sort-by :current-period-start subs)}))

(defn enrich-customer
  [customer]
  (let [customer-id      (:id customer)
        pay-methods      (list-payment-methods customer-id)
        avail-plans      (list-public-plans config/stripe-premium-product-id)
        upcoming-invoice (get-upcoming-invoice customer-id)]
    (cond-> customer
      pay-methods      (assoc :payment-methods  pay-methods)
      avail-plans      (assoc :available-plans  avail-plans)
      upcoming-invoice (assoc :upcoming-invoice upcoming-invoice))))

(defn get-customer
  "Retrieves a summary of the given customer ID."
  [customer-id]
  (convert-customer
   (Customer/retrieve customer-id)))

(defn create-customer!
  "Creates a Customer object in the Stripe API and returns it.
  See https://stripe.com/docs/api/customers/create?lang=java for options."
  [& [options]]
  (-> (Customer/create (or options {}))
      convert-customer
      enrich-customer))

(defn update-customer!
  [customer-id opts]
  (let [customer (Customer/retrieve customer-id)]
    (-> (.update customer opts)
        convert-customer
        enrich-customer)))

(defn set-customer-default-payment-method!
  [customer-id pm-id]
  (update-customer! customer-id {"invoice_settings"
                                 {"default_payment_method" pm-id}}))

(defn cancel-all-subscriptions!
  "Flags all of a customers active or trialing subscriptions for cancellation."
  [{:keys [subscriptions] :as customer}]
  (doseq [sub subscriptions]
    (when-not (canceled? sub)
      (flag-sub-for-cancellation! (:id sub)))))

(defn update-all-subscription-quantities!
  "Updates the subscription quantities of all active subscriptions. Immediately
  bills the customer the prorated amount for the subscription that is current
  (i.e. today falls inside of its period start/end dates)."
  [{:keys [subscriptions] :as customer} quantity]
  (let [first-sub (first subscriptions)]
    (update-subscription-quantity! (:id first-sub)
                                   quantity))
  (doseq [sub (rest subscriptions)]
    (update-subscription-quantity! (:id sub) quantity)))

(defn schedule-new-subscription!
  "Schedules a new subscription to begin at the end of the currently active one.
  Does so by flagging the current subscription for cancellation at the end of
  its cycle, and then creating a new subscription scheduled to begin at that
  cancellation date. Will never schedule more than one subscription in the
  future. If there is already a scheduled subscription, updates the plan of that
  subscription in place. This allows the customer to finish their current
  subsciption out, and then gracefully transition to a new one. Returns the
  newly created subscription.
  Customer must have payment method on file to schedule new subscriptions."
  [customer-id new-plan-id & [opts item-opts]]
  (let [customer  (-> customer-id get-customer enrich-customer)
        subs      (:subscriptions customer)
        final-sub (last subs)]
    (if-not (-> customer :payment-methods seq)
      (throw (ex-info "Customer must have payment method on file to schedule new subscriptions"
                      {:key ::need-payment-method}))
      (if-not final-sub
        ;; no subs yet, fallback to basic subscription creation
        (create-subscription! customer-id new-plan-id opts item-opts)
        ;; this is our one and only subscription, append the new one
        (let [final-anchor   (:current-period-end final-sub)
              new-sub-params (merge opts
                                    {"billing_cycle_anchor" final-anchor
                                     "items" [{"plan"     new-plan-id
                                               "quantity" (:quantity final-sub)}]})]
          (if (and (not (canceled? final-sub))
                   (= 1 (count subs)))
            (flag-sub-for-cancellation! (:id final-sub))
            (cancel-sub-now! (:id final-sub)))
          (create-subscription! customer-id new-plan-id new-sub-params item-opts))))))

(defn end-trial-period!
  "Ends the trial period on the given customer's primary subscription customer
  immediately for this plan. Returns the updated subscription, or the unchanged
  subscription if it's not currently trialing."
  [customer-id]
  (let [customer (-> customer-id get-customer enrich-customer)
        sub      (-> customer :subscriptions last)]
    (if-not (-> customer :payment-methods seq)
      (throw (ex-info "Customer must have payment method on file to end trial period"
                      {:key ::need-payment-method}))
      (if (trialing? sub)
        (update-subscription! (:id sub) {"trial_end" "now"})
        sub))))

;; ----- Checkout Session -----

(defn- convert-checkout-session
  [session]
  {:sessionId (.getId session)})

(defn create-checkout-session!
  "Creates a Checkout Session: the context from which we obtain a customer's payment info.
  Adds the passed `customer-id` as `client_reference_id` for ease of retrieval when the session
  is completed.
  See https://stripe.com/docs/api/checkout/sessions/create for options.
  See https://stripe.com/docs/payments/checkout/collecting for a general guide on Sessions."
  [customer-id success-url cancel-url & [opts]]
  (convert-checkout-session
   (Session/create (merge
                    {"payment_method_types" ["card"]
                     "mode"                 "setup"
                     "client_reference_id"  customer-id
                     "success_url"          success-url
                     "cancel_url"           cancel-url}
                    opts))))

(defn customer-from-session
  "Retrieve the customer from the sessionID"
  [session-id]
  (let [session  (Session/retrieve session-id)
        customer-id (.getClientReferenceId session)]
    (get-customer customer-id)))

(defn assoc-session-result-with-customer!
  "Callback for the session created with `create-checkout-session!`. Once customer
  has entered payment method info, this callback should be invoked in order to
  associate this data with the customer record in Stripe."
  [session-id]
  (let [session             (Session/retrieve session-id)
        customer-id         (.getClientReferenceId session)
        intent-id           (.getSetupIntent session)
        intent              (SetupIntent/retrieve intent-id)
        pay-method-id       (.getPaymentMethod intent)
        pay-method          (PaymentMethod/retrieve pay-method-id)
        attached-pay-method (.attach pay-method {"customer" customer-id})]
    (set-customer-default-payment-method! customer-id (.getId attached-pay-method))))

;; ----- REPL testing -----

(comment

  (def my-id
    (:id (create-customer! {"email" "test@example.com"
                            "name"  "Test Example"})))

  (-> my-id get-customer enrich-customer)

  ;; monthly
  (start-trial! my-id "plan_G2sU8JKdWUExVF")

  (update-all-subscription-quantities! (-> my-id get-customer enrich-customer) 10)

  (end-trial-period! my-id)

  ;; annual
  (schedule-new-subscription! my-id "plan_G2sgUtlXhbVOKu")

  (update-all-subscription-quantities! (-> my-id get-customer enrich-customer) 18)

  ;; monthly
  (schedule-new-subscription! my-id  "plan_G2sU8JKdWUExVF")


  (list-public-plans "prod_FzTa1EB3fhgK6J")

  (let [now            (stripe-now)
        one-month      (* 60 60 24 30)
        month-from-now (+ now one-month)]
    (create-subscription! my-id config/stripe-default-plan-id {"billing_cycle_anchor" month-from-now}))

  ;; ----- Stripe time utilities -----

  (defn- date->timestamp
    "Converts a java.util.Date object to a Stripe timestamp"
    [java-date]
    (quot (.getTime java-date) 1000))


  (defn- stripe-now
    "Timestamp of the moment this function is called according to Stripe"
    []
    (date->timestamp (Date.)))
)
