(ns oc.auth.async.notify
  "
  Send messages to Notify service SQS to alert users.
  "
  (:require [clojure.core.async :as async :refer (<! >!!)]
            [clojure.string :as string]
            [cheshire.core :as json]
            [oc.lib.time :as oc-time]
            [taoensso.timbre :as timbre]
            [schema.core :as schema]
            [oc.lib.sentry.core :as sentry]
            [oc.lib.schema :as lib-schema]
            [amazonica.aws.sqs :as sqs]
            [oc.auth.config :as c]))

;; ----- core.async -----

(defonce notification-chan (async/chan 10000)) ; buffered channel

(defonce notification-go (atom true))

;; ----- Data schema -----

(def TeamTrigger
  "Trigger to notify a user of a new team membership."
  {:notification-type (schema/enum :team-add :team-remove)
   :resource-type schema/Keyword
   :notification-at lib-schema/ISO8601
   schema/Keyword schema/Any
   (schema/optional-key :org) (schema/maybe {:uuid lib-schema/UniqueID
                                             :slug lib-schema/NonBlankStr
                                             :name lib-schema/NonBlankStr
                                             (schema/optional-key :logo-url) (schema/maybe schema/Str)
                                             :team-id lib-schema/UniqueID})
   :user lib-schema/Author
   :team-id lib-schema/UniqueID
   (schema/optional-key :invitee) lib-schema/Author
   (schema/optional-key :removed-user) lib-schema/Author
   (schema/optional-key :admin?) schema/Bool})

;; ----- Notification triggering -----

(schema/defn ^:always-validate ->team-add-trigger :- TeamTrigger
  [invitee author org admin?]
  {:notification-type :team-add
   :resource-type :team
   :invitee (lib-schema/author-for-user invitee)
   :user (lib-schema/author-for-user author)
   :org org
   :team-id (:team-id org)
   :admin? (boolean admin?)
   :notification-at (oc-time/current-timestamp)})

(schema/defn ^:always-validate ->team-remove-trigger :- TeamTrigger
  [removed-user author org team-id admin?]
  {:notification-type :team-remove
   :resource-type :team
   :removed-user (lib-schema/author-for-user removed-user)
   :org org
   :user (lib-schema/author-for-user author)
   :team-id team-id
   :admin? (boolean admin?)
   :notification-at (oc-time/current-timestamp)})

(schema/defn ^:always-validate send-team! [trigger :- TeamTrigger]
  (timbre/info "Sending team-add trigger to queue:" c/aws-sqs-notify-queue)
  (when-not (clojure.string/blank? c/aws-sqs-notify-queue)
    (>!! notification-chan trigger)))

;; ----- Event handling -----

(defn- handle-notification-message
  [trigger]
  (timbre/info "Request to send" trigger "to" c/aws-sqs-notify-queue)
  (schema/validate TeamTrigger trigger)
  (sqs/send-message
   {:access-key c/aws-access-key-id
    :secret-key c/aws-secret-access-key}
   :queue-url c/aws-sqs-notify-queue
   :message-body (json/generate-string trigger {:pretty true}))
  (timbre/info "Request sent!"))

;; ----- Event loop -----

(defn- notification-loop []
  (reset! notification-go true)
  (timbre/info "Starting notify loop...")
  (async/go
    (while @notification-go
      (timbre/debug "Notify loop waiting...")
      (let [message (<! notification-chan)]
        (timbre/debug "Processing message on notify channel...")
        (if (:stop message)
          (do (reset! notification-go false) (timbre/info "Notify loop stopped."))
          (try
            (handle-notification-message message)
            (catch Exception e
              (timbre/warn e)
              (sentry/capture e))))))))

;; ----- Component start/stop -----

(defn start
  "Start the core.async event loop."
  []
  (when-not (clojure.string/blank? c/aws-sqs-notify-queue) ;; do we care about getting SNS notifications?
    (notification-loop)))

(defn stop
  "Stop the the core.async event loop."
  []
  (when @notification-go
    (timbre/info "Stopping notification...")
    (>!! notification-chan {:stop true})))