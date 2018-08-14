(ns oc.auth.app
  "Namespace for the web application which serves the REST API."
  (:gen-class)
  (:require
    [raven-clj.core :as sentry]
    [raven-clj.interfaces :as sentry-interfaces]
    [raven-clj.ring :as sentry-mw]
    [taoensso.timbre :as timbre]
    [ring.logger.timbre :refer (wrap-with-logger)]
    [liberator.dev :refer (wrap-trace)]
    [ring.middleware.params :refer (wrap-params)]
    [ring.middleware.reload :refer (wrap-reload)]
    [ring.middleware.cors :refer (wrap-cors)]
    [buddy.auth.middleware :refer (wrap-authentication)]
    [buddy.auth.backends :as backends]
    [compojure.core :as compojure :refer (GET)]
    [com.stuartsierra.component :as component]
    [oc.lib.sentry-appender :as sa]
    [oc.lib.api.common :as api-common]
    [oc.auth.components :as components]
    [oc.auth.config :as c]
    [oc.auth.api.entry-point :as entry-point-api]
    [oc.auth.api.slack :as slack-api]
    [oc.auth.api.google :as google-api]
    [oc.auth.api.users :as users-api]
    [oc.auth.api.teams :as teams-api]
    [oc.auth.async.slack-router :as slack-router]))

;; ----- Unhandled Exceptions -----

;; Send unhandled exceptions to log and Sentry
;; See https://stuartsierra.com/2015/05/27/clojure-uncaught-exceptions
(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (timbre/error ex "Uncaught exception on" (.getName thread) (.getMessage ex))
     (when c/dsn
       (sentry/capture c/dsn (-> {:message (.getMessage ex)}
                                 (assoc-in [:extra :exception-data] (ex-data ex))
                                 (sentry-interfaces/stacktrace ex)))))))

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
    "Running on port: " port "\n"
    "Database: " c/db-name "\n"
    "Database pool: " c/db-pool-size "\n"
    "AWS SQS email queue: " c/aws-sqs-email-queue "\n"
    "AWS SQS bot queue: " c/aws-sqs-bot-queue "\n"
    "AWS SQS slack queue: " c/aws-sqs-slack-router-auth-queue "\n"
    "AWS SNS notification topic ARN: " c/aws-sns-auth-topic-arn "\n"
    "Trace: " c/liberator-trace "\n"
    "Hot-reload: " c/hot-reload "\n"
    "Sentry: " c/dsn "\n\n"
    (when c/intro? "Ready to serve...\n"))))

;; Ring app definition
(defn app [sys]
  (cond-> (routes sys)
    c/prod?           api-common/wrap-500 ; important that this is first
    c/dsn             (sentry-mw/wrap-sentry c/dsn) ; important that this is second
    c/prod?           wrap-with-logger
    true              wrap-params
    c/liberator-trace (wrap-trace :header :ui)
    true              (wrap-cors #".*")
    true              (wrap-authentication (backends/basic {:realm "oc-auth"
                                                            :authfn (partial users-api/email-basic-auth sys)}))
    c/hot-reload      wrap-reload))

(defn start
  "Start a development server"
  [port]

  ;; Stuff logged at error level goes to Sentry
  (if c/dsn
    (timbre/merge-config!
      {:level (keyword c/log-level)
       :appenders {:sentry (sa/sentry-appender c/dsn)}})
    (timbre/merge-config! {:level (keyword c/log-level)}))

  ;; Start the system
  (-> {:handler-fn app
       :port port
       :sqs-queue c/aws-sqs-slack-router-auth-queue
       :slack-sqs-msg-handler slack-router/sqs-handler
       :sqs-creds {:access-key c/aws-access-key-id
                   :secret-key c/aws-secret-access-key}
       }
      components/auth-system
      component/start)

  ;; Echo config information
  (println (str "\n"
    (when c/intro? (str (slurp (clojure.java.io/resource "ascii_art.txt")) "\n"))
    "OpenCompany Auth Service\n"))
  (echo-config port))

(defn -main []
  (start c/auth-server-port))