(ns oc.auth.lib.stripe
  (:require [clojure.set :as cset]
            [oc.auth.config :as config])
  (:import [com.stripe Stripe]
           [com.stripe.model Customer Subscription SubscriptionItem UsageRecord Plan]
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
;; Customer summary

(defn- retrieve-customer-by-id
  "Queries Stripe to retrieve the Customer record with the given Stripe ID"
  [cid]
  (Customer/retrieve cid))

(defn- convert-plan
  [plan]
  {:id       (.getId plan)
   :amount   (.getAmount plan)
   :nickname (.getNickname plan)
   :currency (.getCurrency plan)
   :active   (.getActive plan)
   :interval (.getInterval plan)})

(defn- convert-usage-summary
  [usage-summary]
  {:seats (.getTotalUsage usage-summary)})

(defn- convert-subscription-item
  [sub-item]
  {:id (.getId sub-item)})

(defn- convert-usage-record
  [usage-record]
  {:id        (.getId usage-record)
   :seats     (.getQuantity usage-record)
   :timestamp (.getTimestamp usage-record)})

(defn- convert-subscription
  [sub]
  (let [item          (-> sub .getItems .getData first)
        usage-summary (-> item .usageRecordSummaries .getData first)
        avail-plans   [(Plan/retrieve config/stripe-monthly-plan-id)
                       (Plan/retrieve config/stripe-annual-plan-id)]]
    {:id                   (.getId sub)
     :status               (.getStatus sub)
     :trial-start          (.getTrialStart sub)
     :trial-end            (.getTrialEnd sub)
     :current-period-start (.getCurrentPeriodStart sub)
     :current-period-end   (.getCurrentPeriodEnd sub)
     :current-plan         (convert-plan (.getPlan sub))
     :usage                (convert-usage-summary usage-summary)
     :available-plans      (mapv convert-plan avail-plans)
     :item                 (convert-subscription-item item)
     }))

(defn- convert-customer
  [customer]
  (let [sub (-> customer .getSubscriptions .getData first)]
    (cond-> {:id           (.getId customer)
             :email        (.getEmail customer)
             :full-name    (.getName customer)}
      sub (assoc :subscription (convert-subscription sub)))))

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
  "Reports to Stripe the latest number of seats being used"
  [customer-id num-seats]
  (if-let [sub-item-id (-> customer-id customer-info :subscription :item :id)]
    (let [params {"action"    "set"
                  "quantity"  num-seats
                  "timestamp" (stripe-now)}]
      (-> (UsageRecord/createOnSubscriptionItem sub-item-id params nil)
          convert-usage-record))
    (throw (ex-info "Cannot report metered usage on non-existent SubscriptionItem"
                    {:customer-id customer-id}))))

(defn change-plan!
  "Changes the customer's current plan to the new plan with the given ID"
  [customer-id new-plan-id]
  (let [customer (customer-info customer-id)]
    (if-let [sub-item-id (-> customer :subscription :item :id)]
      (if-not (= new-plan-id (-> customer :subscription :current-plan :id))
        (let [sub-item   (SubscriptionItem/retrieve sub-item-id)
              new-params {"plan" new-plan-id}]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REPL testing

(comment

  (def my-id
    (-> {:email     "test@example.com"
         :full-name "Test Example"}
        create-stripe-customer!
        :id))

  (customer-info my-id)

  (subscribe-customer-to-plan! my-id config/stripe-monthly-plan-id)

  (change-plan! my-id config/stripe-annual-plan-id)

  (cancel-subscription! my-id)

  (report-seats! my-id 29)

  )