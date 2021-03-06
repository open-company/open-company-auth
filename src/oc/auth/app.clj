(ns oc.auth.app
  "Namespace for the web application which serves the REST API."
  (:gen-class)
  (:require [oc.lib.sentry.core :as sentry]
            [taoensso.timbre :as timbre]
            [clojure.string :as clj-str]
            [clojure.java.io :as j-io]
            [ring.logger.timbre :refer (wrap-with-logger)]
            [liberator.dev :refer (wrap-trace)]
            [ring.middleware.params :refer (wrap-params)]
            [ring.middleware.reload :refer (wrap-reload)]
            [ring.middleware.cookies :refer (wrap-cookies)]
            [ring.middleware.cors :refer (wrap-cors)]
            [buddy.auth.middleware :refer (wrap-authentication)]
            [buddy.auth.backends :as backends]
            [compojure.core :as compojure :refer (GET)]
            [com.stuartsierra.component :as component]
            [oc.lib.api.common :as api-common]
            [oc.auth.components :as components]
            [oc.auth.config :as c]
            [oc.auth.api.entry-point :as entry-point-api]
            [oc.auth.api.slack :as slack-api]
            [oc.auth.api.google :as google-api]
            [oc.auth.api.users :as users-api]
            [oc.auth.api.teams :as teams-api]
            [oc.auth.async.slack-router :as slack-router]
            [oc.auth.async.expo :as expo]))

;; ----- Request Routing -----

(defn routes [sys]
  (compojure/routes
    (GET "/ping" [] {:body "OpenCompany Auth Service: OK" :status 200}) ; Up-time monitor
    (GET "/---error-test---" [] (/ 1 0))
    (GET "/---500-test---" [] {:body "Testing bad things." :status 500})
    (entry-point-api/routes sys)
    (slack-api/routes sys)
    (google-api/routes sys)
    (users-api/routes sys)
    (teams-api/routes sys)))

;; ----- System Startup -----

(defn echo-config [port]
  (println (str "\n"
    "Production? " c/prod? "\n"
    "Running on port: " port "\n"
    "Database: " c/db-name "\n"
    "Database pool: " c/db-pool-size "\n"
    "AWS SQS email queue: " c/aws-sqs-email-queue "\n"
    "AWS SQS bot queue: " c/aws-sqs-bot-queue "\n"
    "AWS SQS slack queue: " c/aws-sqs-slack-router-auth-queue "\n"
    "AWS SQS expo queue: " c/aws-sqs-expo-queue "\n"
    "AWS SQS payments queue: " c/aws-sqs-payments-queue "\n"
    "AWS SQS notify queue: " c/aws-sqs-notify-queue "\n"
    "AWS SNS notification topic ARN: " c/aws-sns-auth-topic-arn "\n"
    "AWS LAMBDA expo prefix: " c/aws-lambda-expo-prefix "\n"
    "Slack customer support webhook: " c/slack-customer-support-webhook "\n"
    (when c/payments-enabled?
      (str "Payments server url: " c/payments-server-url "\n"))
    "Log level: " c/log-level "\n"
    "Trace: " c/liberator-trace "\n"
    "Hot-reload: " c/hot-reload "\n"
    "Sentry: " c/sentry-config "\n"
    "Payments?: " c/payments-enabled? "\n\n"
    (when c/intro? "Ready to serve...\n"))))

;; Ring app definition
(defn app [sys]
  (cond-> (routes sys)
    c/prod?           api-common/wrap-500 ; important that this is first
    ; important that this is second
    c/dsn             (sentry/wrap c/sentry-config)
    c/prod?           wrap-with-logger
    true              wrap-params
    c/liberator-trace (wrap-trace :header :ui)
    true              wrap-cookies
    true              (wrap-cors #".*")
    true              (wrap-authentication (backends/basic {:realm "oc-auth"
                                                            :authfn (partial users-api/email-basic-auth sys)}))
    c/hot-reload      wrap-reload))

(defn start
  "Start a development server"
  [port]

  ;; Stuff logged at error level goes to Sentry
  (timbre/merge-config! {:min-level (keyword c/log-level)})

  ;; Start the system
  (-> {:sentry c/sentry-config
       :handler-fn app
       :port port
       :sqs-queue c/aws-sqs-slack-router-auth-queue
       :slack-sqs-msg-handler slack-router/sqs-handler
       :expo-sqs-queue c/aws-sqs-expo-queue
       :expo-sqs-msg-handler expo/sqs-handler
       :sqs-creds {:access-key c/aws-access-key-id
                   :secret-key c/aws-secret-access-key}
       }
      components/auth-system
      component/start)

  ;; Echo config information
  (println (str "\n"
    (when c/intro? (str (slurp (j-io/resource "ascii_art.txt")) "\n"))
    "OpenCompany Auth Service\n"))
  (echo-config port))

(defn -main []
  (start c/auth-server-port))