(ns oc.auth.async.expo
  "
  Async consumption of mobile push notification tickets from AWS SNS, produced
  by the open-company-notify service. The Expo push notification service produces
  what are called 'tickets' to determine the status of previously sent push notifications.
  If a 'ticket' is found to have failed, the relevant user's push token should be removed
  from storage. This could be due to the user uninstalling the app for example. If tokens
  are not removed, and our service continues to attempt to push notifications to the user
  in vain, Apple will eventually block *all* push notifications (and possibly take even
  more serious action). So, this async consumer makes sure that we're cleaning up after
  ourselves.
  "
  (:require [clojure.core.async :as async :refer (<!! >!!)]
            [cheshire.core :as json]
            [taoensso.timbre :as timbre]
            [oc.lib.sqs :as sqs]
            [oc.auth.config :as config]))

;; ----- core.async -----

(defonce expo-chan (async/chan 10000)) ; buffered channel

(defonce expo-go (atom true))

;; ----- Event handling -----

(defn- handle-expo-message
  [db-pool msg]
  (timbre/info "Received Expo message: " msg)
  (when-let [tickets (:tickets msg)]
    (timbre/info "Performing Expo ticket analysis to determine if push tokens need purging")
    ))

;; ----- SQS handling -----

(defn- read-message-body
  "
  Try to parse as json, otherwise use read-string.
  "
  [msg]
  (try
    (json/parse-string msg true)
    (catch Exception e
      (read-string msg))))

(defn sqs-handler
  "Handle an incoming SQS message to the auth service."
  [msg done-channel]
  (let [msg-body (read-message-body (:body msg))
        error (if (:test-error msg-body) (/ 1 0) false)] ; a message testing Sentry error reporting
    (timbre/infof "Received message from SQS: %s\n" msg-body)
    (>!! expo-chan msg-body))
  (sqs/ack done-channel msg))


;; ----- Event loop -----

(defn- expo-loop
  [db-pool]
  (reset! expo-go true)
  (async/go
    (while @expo-go
      (timbre/debug "Expo consumer waiting...")
      (let [msg (<!! expo-chan)]
        (timbre/debug "Processing message on expo channel...")
        (if (:stop msg)
          (do (reset! expo-go false) (timbre/info "Expo consumer stopped."))
          (try
            (handle-expo-message db-pool msg)
            (timbre/trace "Processing complete.")
            (catch Exception e
              (timbre/error e))))))))

;; ----- Component start/stop -----

(defn start
  "Start the core.async event loop."
  [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (timbre/info "Starting Expo consumer...")
    (expo-loop db-pool)))

(defn stop
  "Stop the the core.async event loop."
  []
  (when @expo-go
    (timbre/info "Stopping Expo consumer...")
    (>!! expo-chan {:stop true})))
