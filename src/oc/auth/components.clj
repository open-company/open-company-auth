(ns oc.auth.components
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [org.httpkit.server :as httpkit]
            [oc.lib.sentry.core :refer (map->SentryCapturer)]
            [oc.lib.db.pool :as pool]
            [oc.lib.sqs :as sqs]
            [oc.auth.async.expo :as expo]
            [oc.auth.async.slack-router :as slack-router]
            [oc.auth.async.notification :as notification]
            [oc.auth.async.slack-api-calls :as slack-api-calls]
            [oc.auth.async.payments :as payments]
            [oc.auth.config :as c]))

(defrecord HttpKit [options handler server]
  component/Lifecycle
  (start [component]
    (let [handler (get-in component [:handler :handler] handler)
          server  (httpkit/run-server handler options)]
      (assoc component :server server)))
  (stop [component]
    (if-not server
      component
      (do
        (server)
        (assoc component :server nil)))))

(defrecord RethinkPool [size regenerate-interval pool]
  component/Lifecycle
  (start [component]
    (timbre/info "[rehinkdb-pool] starting")
    (let [pool (pool/fixed-pool (partial pool/init-conn c/db-options) pool/close-conn
                                {:size size :regenerate-interval regenerate-interval})]
      (timbre/info "[rehinkdb-pool] started")
      (assoc component :pool pool)))
  (stop [component]
    (if pool
      (do
        (pool/shutdown-pool! pool)
        (assoc component :pool nil))
      component)))

(defrecord Handler [handler-fn]
  component/Lifecycle
  (start [component]
    (timbre/info "[handler] starting")
    (assoc component :handler (handler-fn component)))
  (stop [component]
    (assoc component :handler nil)))

(defrecord SlackRouter [slack-router-fn]
  component/Lifecycle

  (start [component]
    (timbre/info "[slack-router] starting...")
    (slack-router/start component)
    (timbre/info "[slack-router] started")
    (assoc component :slack-router true))

  (stop [{:keys [slack-router] :as component}]
    (if slack-router
      (do
        (timbre/info "[slack-router] stopping...")
        (slack-router/stop)
        (timbre/info "[slack-router] stopped")
        (assoc component :slack-router nil))
      component)))

(defrecord ExpoConsumer [expo-consumer-fn]
  component/Lifecycle

  (start [component]
    (timbre/info "[expo-consumer] starting...")
    (expo/start component)
    (timbre/info "[expo-consumer] started")
    (assoc component :expo-consumer true))

  (stop [{:keys [expo-consumer] :as component}]
    (if expo-consumer
      (do
        (timbre/info "[expo-consumer] stopping...")
        (expo/stop)
        (timbre/info "[expo-consumer] stopped")
        (assoc component :expo-consumer nil))
      component)))

(defrecord AsyncConsumers []
  component/Lifecycle

  (start [component]
    (timbre/info "[async-consumers] starting")
    (notification/start) ; core.async channel consumer for notification events
    (slack-api-calls/start component)
    (payments/start)
    (timbre/info "[async-consumers] started")
    (assoc component :async-consumers true))

  (stop [{:keys [async-consumers] :as component}]
    (if async-consumers
      (do
        (timbre/info "[async-consumers] stopping")
        (notification/stop) ; core.async channel consumer for notification events
        (slack-api-calls/stop)
        (payments/stop)
        (timbre/info "[async-consumers] stopped")
        (assoc component :async-consumers nil))
    component)))

(defn db-only-auth-system [_opts]
  (component/system-map
   :db-pool (map->RethinkPool {:size c/db-pool-size :regenerate-interval 5})))

(defn auth-system [{:keys [port handler-fn sqs-creds sqs-queue slack-sqs-msg-handler
                           expo-sqs-queue expo-sqs-msg-handler sentry]}]
  (component/system-map
   :sentry-capturer (map->SentryCapturer sentry)
   :db-pool (component/using
             (map->RethinkPool {:size c/db-pool-size :regenerate-interval 5})
             [:sentry-capturer])
   :slack-router (component/using
                  (map->SlackRouter {:slack-router-fn slack-sqs-msg-handler})
                  [:db-pool])
   :sqs (component/using
         (sqs/sqs-listener sqs-creds sqs-queue slack-sqs-msg-handler)
         [:sentry-capturer])
   :expo (component/using
          (map->ExpoConsumer {:expo-consumer-fn expo-sqs-msg-handler})
          [:db-pool])
   :expo-sqs (component/using
              (sqs/sqs-listener sqs-creds expo-sqs-queue expo-sqs-msg-handler)
              [:sentry-capturer])
   :async-consumers (component/using
                     (map->AsyncConsumers {})
                     [:db-pool])
   :handler (component/using
             (map->Handler {:handler-fn handler-fn})
             [:db-pool])
   :server  (component/using
             (map->HttpKit {:options {:port port}})
             [:handler])))