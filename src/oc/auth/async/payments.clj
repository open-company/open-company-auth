(ns oc.auth.async.payments
  "
  Async publish of team change reports to payments (Stripe)
  "
  (:require [clojure.core.async :as async :refer (<! >!!)]
            [taoensso.timbre :as timbre]
            [schema.core :as schema]
            [oc.auth.resources.payments :as pay-res]
            [oc.auth.resources.user :as user-res]
            [oc.auth.resources.team :as team-res]))

;; ----- core.async -----

(defonce payments-chan (async/chan 10000)) ; buffered channel

(defonce payments-go (atom true))

;; ----- Data schema -----

(def TeamReportTrigger
  {:customer-id (:id pay-res/Customer)
   :seats       (:quantity pay-res/Subscription)})

;; ----- Event handling -----

(defn- handle-payments-message
  [trigger]
  (timbre/trace "Message request:" trigger)
  (schema/validate TeamReportTrigger trigger)
  (pay-res/report-latest-team-size! (:customer-id trigger)
                                    (:seats trigger)))

;; ----- Event loop -----

(defn- payments-loop []
  (reset! payments-go true)
  (timbre/info "Starting payments loop...")
  (async/go (while @payments-go
    (timbre/debug "Payments loop waiting...")
    (let [message (<! payments-chan)]
      (timbre/debug "Processing message on payments channel...")
      (if (:stop message)
        (do (reset! payments-go false) (timbre/info "Payments loop stopped."))
        (async/thread
          (try
            (handle-payments-message message)
          (catch Exception e
            (timbre/error e)))))))))

;; ----- Payments triggering -----

(defn ->team-report-trigger
  [{:keys [customer-id seats] :as report}]
  (select-keys report [:customer-id :seats]))

(schema/defn ^:always-validate send-team-report-trigger! [trigger :- TeamReportTrigger]
  (>!! payments-chan trigger))

(defn report-team-seat-usage!
  [conn team-id]
  (when-let [customer-id (:stripe-customer-id (team-res/get-team conn team-id))] ;; Early on there's no customer yet
    (let [active?     #(#{"active" "unverified"} (:status %))
          team-users  (filter active? (user-res/list-users conn team-id))
          seat-count  (count team-users)
          trigger     (->team-report-trigger {:customer-id customer-id
                                            :seats       seat-count})]
      (timbre/info (format "Reporting %d seats used to payment service for team %s (%s)"
                         seat-count
                         team-id
                         customer-id))
      (send-team-report-trigger! trigger))))

(defn report-all-seat-usage!
  [conn team-ids]
  (doseq [tid team-ids]
    (report-team-seat-usage! conn tid)))

;; ----- Component start/stop -----

(defn start
  "Start the core.async event loop."
  []
  (payments-loop))

(defn stop
  "Stop the the core.async event loop."
  []
  (when @payments-go
    (timbre/info "Stopping payments loop...")
    (>!! payments-chan {:stop true})))
