(ns oc.auth.async.payments
  "Async publish of team change reports to payments service."
  (:require [clojure.core.async :as async :refer (<! >!!)]
            [clojure.string :as string]
            [cheshire.core :as json]
            [if-let.core :refer (when-let*)]
            [taoensso.timbre :as timbre]
            [schema.core :as schema]
            [oc.lib.sentry.core :as sentry]
            [oc.lib.schema :as lib-schema]
            [amazonica.aws.sqs :as sqs]
            [oc.auth.config :as c]
            [oc.auth.resources.team :as team-res]))

;; ----- core.async -----

(defonce payments-chan (async/chan 10000)) ; buffered channel

(defonce payments-go (atom true))

;; ----- Data schema -----

(def TeamReportTrigger
  {:customer-id lib-schema/NonBlankStr
   :team-id lib-schema/UniqueID})

;; ----- Event handling -----

(defn handle-payments-message
  [trigger]
  (timbre/info "Request to send" trigger "to" c/aws-sqs-payments-queue)
  (schema/validate TeamReportTrigger trigger)
  (sqs/send-message
    {:access-key c/aws-access-key-id
     :secret-key c/aws-secret-access-key}
      :queue-url c/aws-sqs-payments-queue
      :message-body (json/generate-string trigger {:pretty true}))
  (timbre/info "Request sent!"))

;; ----- Event loop -----

(defn- payments-loop []
  (reset! payments-go true)
  (timbre/info "Starting payments loop...")
  (async/go
    (while @payments-go
      (timbre/debug "Payments loop waiting...")
      (let [message (<! payments-chan)]
        (timbre/debug "Processing message on payments channel...")
        (if (:stop message)
          (do (reset! payments-go false) (timbre/info "Payments loop stopped."))
          (try
            (handle-payments-message message)
            (catch Exception e
              (timbre/warn e)
              (sentry/capture e))))))))

;; ----- Payments triggering -----

(defn ->team-report-trigger
  [report]
  (select-keys report [:customer-id :team-id]))

(schema/defn ^:always-validate send-team-report-trigger! [trigger :- TeamReportTrigger]
  (when-not (string/blank? c/aws-sqs-payments-queue)
    (>!! payments-chan trigger)))

(defn report-team-seat-usage!
  [conn team-id]
  (when-let* [_enabled? c/payments-enabled?
              team-data (team-res/get-team conn team-id)
              customer-id (:stripe-customer-id team-data)  ;; Early on there's no customer yet which avoid triggering a team size change in payments
              trigger (->team-report-trigger {:customer-id customer-id :team-id team-id})]
    (timbre/info (format "Reporting seats used to payment service for team %s (%s)"
                        team-id
                        customer-id))
    (send-team-report-trigger! trigger)))

(defn report-all-seat-usage!
  [conn team-ids]
  (doseq [tid team-ids]
    (report-team-seat-usage! conn tid)))

;; ----- Component start/stop -----

(defn start
  "Start the core.async event loop."
  []
  (when c/payments-enabled?
    (payments-loop)))

(defn stop
  "Stop the the core.async event loop."
  []
  (when (and c/payments-enabled? @payments-go)
    (timbre/info "Stopping payments loop...")
    (>!! payments-chan {:stop true})))