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
            [oc.auth.config :as config]
            [oc.lib.lambda.common :as lambda]))

;; ----- core.async -----

(defonce expo-chan (async/chan 10000)) ; buffered channel

(defonce expo-go (atom true))

;; ----- Event handling -----

(defn- get-receipts
  [tickets]
  (-> (lambda/invoke-fn "expo-push-notifications-dev-getPushNotificationReceipts"
                        {:tickets tickets})
      lambda/parse-response
      :receipts))

(defn- determine-bad-push-tokens
  [push-notifications tickets receipts]
  (let [full-tickets    (map merge push-notifications tickets)
        by-ticket-id    (group-by (comp keyword :id) full-tickets)
        receipt-tuples  (mapcat seq receipts)   ;; ([:receipt-x {:status "ok"}] [:receipt-y {:status "error"}])
        bad-receipt-kv  (fn [[id data]]
                          (when (not= "ok" (:status data))
                            id))
        bad-receipt-ids (keep bad-receipt-kv receipt-tuples)
        bad-push-tokens (mapcat (comp #(map :pushToken %) by-ticket-id) bad-receipt-ids)]
    (into #{} bad-push-tokens)))

(defn- handle-expo-message
  [db-pool msg]
  (let [{:keys [push-notifications tickets]} msg]
    (when (and (seq push-notifications) (seq tickets))
      (let [receipts (get-receipts tickets)
            bad-push-tokens (determine-bad-push-tokens push-notifications tickets receipts)]
        (timbre/info "Checked for bad push tokens" (merge msg {:receipts receipts}))
        (when (not-empty bad-push-tokens)
          (throw (ex-info "Found some bad push tokens! Dev attention required."
                          (merge msg {:receipts receipts}))))))))

(comment

  ;; Sample receipts
  ;; [{:06c938b1-aca5-47fc-8750-d23ea257bae8 {:status "error"}}]

  (let [{:keys [push-notifications tickets] :as msg}
        {:push-notifications [{:pushToken "ExponentPushToken[m7WFXDHNuI8PRZPCDXUeVI]"
                               :body      "Hey there, this is Clojure!" :data {}}]
         :tickets            [{:status "ok" :id "06c938b1-aca5-47fc-8750-d23ea257bae8"}]}
        ;; receipts        (get-ticket-receipts tickets)
        receipts [{:06c938b1-aca5-47fc-8750-d23ea257bae8 {:status "error"}
                   :another                              {:status "error"}
                   :third                                {:status "error"}}
                  {:receipt-x {:status "ok"}
                   :receipt-y {:status "error"}}]]
    (determine-bad-push-tokens push-notifications tickets receipts)
    )

  )

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
