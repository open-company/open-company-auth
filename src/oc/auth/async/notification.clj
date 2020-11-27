(ns oc.auth.async.notification
  "
  Async publish of notification events to AWS SNS.
  "
  (:require [clojure.core.async :as async :refer (<! >!!)]
            [taoensso.timbre :as timbre]
            [cheshire.core :as json]
            [amazonica.aws.sns :as sns]
            [clojure.string :as s]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.lib.time :as oc-time]
            [oc.auth.config :as config]))

;; ----- core.async -----

(defonce notification-chan (async/chan 10000)) ; buffered channel

(defonce notification-go (atom true))

;; ----- Data schema -----

(def Trigger
  "
  add - the content for a newly created user.
  "
  {:notification-type schema/Keyword
   :resource-type schema/Keyword
   :notification-at lib-schema/ISO8601
   :content {(schema/optional-key :new) schema/Any}
   schema/Keyword schema/Any})

;; ----- Event handling -----

(defn- handle-notification-message
  [trigger]
  (timbre/debug "Message request of:" (:notification-type trigger)
                "to topic:" config/aws-sns-auth-topic-arn)
  (timbre/trace "Message request:" trigger)
  (schema/validate Trigger trigger)
  (timbre/info "Sending request to topic:" config/aws-sns-auth-topic-arn)
  (sns/publish
    {:access-key config/aws-access-key-id
     :secret-key config/aws-secret-access-key}
     :topic-arn config/aws-sns-auth-topic-arn
     :subject (str (name (:notification-type trigger))
                   " on " (name (:resource-type trigger)))
     :message (json/generate-string trigger {:pretty true}))
  (timbre/info "Request sent to topic:" config/aws-sns-auth-topic-arn))

;; ----- Event loop -----

(defn- notification-loop []
  (reset! notification-go true)
  (timbre/info "Starting notification...")
  (async/go (while @notification-go
    (timbre/debug "Notification waiting...")
    (let [message (<! notification-chan)]
      (timbre/debug "Processing message on notification channel...")
      (if (:stop message)
        (do (reset! notification-go false) (timbre/info "Notification stopped."))
        (async/thread
          (try
            (handle-notification-message message)
          (catch Exception e
            (timbre/error e)))))))))

;; ----- Notification triggering -----

(schema/defn ^:always-validate ->trigger :- Trigger
  [user]
  (let [full-name (str (:first-name user) " " (:last-name user))
        name (if (s/blank? full-name)
               ;; email users don't have a name yet
               "New User" ;; use temporary name for SNS message schema
               full-name)
        fixed-user (assoc user :name name)]
    {:notification-type :add
     :resource-type :user
     :content {:new fixed-user}
     :notification-at (oc-time/current-timestamp)}))

(schema/defn ^:always-validate send-trigger! [trigger :- Trigger]
  (when-not (clojure.string/blank? config/aws-sns-auth-topic-arn)
    (>!! notification-chan trigger)))

;; ----- Component start/stop -----

(defn start
  "Start the core.async event loop."
  []
  (when-not (clojure.string/blank? config/aws-sns-auth-topic-arn) ;; do we care about getting SNS notifications?
    (notification-loop)))

(defn stop
  "Stop the the core.async event loop."
  []
  (when @notification-go
    (timbre/info "Stopping notification...")
    (>!! notification-chan {:stop true})))