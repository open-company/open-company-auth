(ns oc.auth.async.expo
  "
  Async consumption of mobile push notification tickets from AWS SQS, produced
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
            [oc.lib.lambda.common :as lambda]
            [oc.auth.resources.user :as user-res]
            [oc.lib.db.pool :as pool]
            [oc.lib.sentry.core :as sentry]))

;; ----- core.async -----

(defonce expo-chan (async/chan 10000)) ; buffered channel

(defonce expo-go (atom true))

;; ----- Event handling -----

(defn- get-receipts
  [tickets]
  (-> (lambda/invoke-fn (str config/aws-lambda-expo-prefix "getPushNotificationReceipts")
                        {:tickets tickets})
      lambda/parse-response
      :receipts))

(defn- determine-bad-push-tokens
  [push-notifications tickets receipts]
  (let [not-ok?                       (fn [data] (not= "ok" (:status data)))
        ;; -- push notifications can fail when sent to Expo (determined by Expo tickets) --
        full-tickets                  (map merge push-notifications tickets)
        bad-tokens-from-tickets       (->> full-tickets (filter not-ok?) (map :pushToken) (into #{}))
        ;; -- push notifications can fail when sent to Apple/Google (determined by Expo receipts) --
        by-ticket-id                  (group-by (comp keyword :id) full-tickets)
        receipt-tuples                (mapcat seq receipts) ;; ([:id_0 {:status "ok"}] [:id_1 {:status "error"}] ...)
        bad-receipt-kv                (fn [[id data]] (when (not-ok? data)
                                                        id))
        bad-receipt-ids               (keep bad-receipt-kv receipt-tuples)
        bad-push-tokens-from-receipts (->> bad-receipt-ids
                                           (mapcat (comp #(map :pushToken %) by-ticket-id))
                                           (into #{}))]
    (into bad-tokens-from-tickets
          bad-push-tokens-from-receipts)))

(defn- remove-bad-push-tokens-from-users!
  [db-pool push-notifications bad-push-tokens]
  (let [bad-push-notifications (filter (comp bad-push-tokens :pushToken) push-notifications)
        push-notif-user-id     (comp :user-id :data)
        push-token->user-id    (zipmap (map :pushToken bad-push-notifications)
                                       (map push-notif-user-id bad-push-notifications))]
    (pool/with-pool [conn db-pool]
      (doseq [[push-token user-id] push-token->user-id]
        (let [user            (user-res/get-user conn user-id)
              old-push-tokens (set (:expo-push-tokens user))
              new-push-tokens (seq (disj old-push-tokens push-token))]
          (sentry/capture {:message "Removing bad push tokens from user"
                           :extra {:user-id user-id
                                   :push-token push-token
                                   :old-push-tokens old-push-tokens
                                   :new-push-tokens new-push-tokens}})
          (user-res/update-user! conn user-id {:expo-push-tokens new-push-tokens}))))))

(defn- handle-expo-message
  [db-pool msg]
  (let [{:keys [push-notifications tickets]} msg]
    (when (and (seq push-notifications) (seq tickets))
      (let [receipts (get-receipts tickets)
            bad-push-tokens (determine-bad-push-tokens push-notifications tickets receipts)]
        (timbre/info "Checked for bad push tokens" (merge msg {:receipts receipts}))
        (when (not-empty bad-push-tokens)
          (try
            (remove-bad-push-tokens-from-users! db-pool push-notifications bad-push-tokens)
            (catch Exception e
              (throw (ex-info (.getMessage e)
                              (merge msg {:receipts receipts
                                          :bad-push-tokens bad-push-tokens})))
              )))))))

(comment

  ;; Sample receipts
  ;; [{:06c938b1-aca5-47fc-8750-d23ea257bae8 {:status "error"}}]

  (let [{:keys [push-notifications tickets]}
        {:push-notifications [{:pushToken "TOKEN_A"}
                              {:pushToken "TOKEN_B"}
                              {:pushToken "TOKEN_C"}]
         :tickets            [{:status "ok" :id "ID_A"}
                              {:status "ok" :id "ID_B"}
                              {:status "error" :id "ID_C"}]}
        receipts [{:ID_A {:status "ok"}
                   :ID_B {:status "error"}}
                  {:ID_C {:status "ok"}}]]
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
    (catch Exception _
      (read-string msg))))

(defn sqs-handler
  "Handle an incoming SQS message to the auth service."
  [msg done-channel]
  (let [msg-body (read-message-body (:body msg))
        _error (if (:test-error msg-body) (/ 1 0) false)] ; a message testing Sentry error reporting
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