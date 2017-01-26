(ns oc.auth.config
  "Namespace for the configuration parameters."
  (:require [environ.core :refer (env)]))

(defn- bool
  "Handle the fact that we may have true/false strings, when we want booleans."
  [val]
  (boolean (Boolean/valueOf val)))

;; ----- System -----

(defonce processors (.availableProcessors (Runtime/getRuntime)))
(defonce core-async-limit (+ 42 (* 2 processors)))

(defonce intro? (bool (or (env :intro) false)))
(defonce prod? (= "production" (env :env)))

;; ----- Logging -----

(defonce log-level (or (env :log-level) :info))

;; ----- Liberator -----

;; see header response, or http://localhost:3000/x-liberator/requests/ for trace results
(defonce liberator-trace (bool (or (env :liberator-trace) false)))

;; ----- Sentry -----

(defonce dsn (or (env :open-company-sentry-auth) false))

;; ----- RethinkDB -----

(defonce migrations-dir "./src/oc/auth/db/migrations")
(defonce migration-template "./src/oc/auth/assets/migration.template.edn")

(defonce db-host (or (env :db-host) "localhost"))
(defonce db-port (or (env :db-port) 28015))
(defonce db-name (or (env :db-name) "open_company_auth"))
(defonce db-pool-size (or (env :db-pool-size) (- core-async-limit 21))) ; conservative with the core.async limit

(defonce db-map {:host db-host :port db-port :db db-name})
(defonce db-options (flatten (vec db-map))) ; k/v sequence as clj-rethinkdb wants it

;; ----- Slack -----

(defonce slack-client-id (env :open-company-slack-client-id))
(defonce slack-client-secret (env :open-company-slack-client-secret))
(defonce slack-scope "identity.basic,identity.email,identity.avatar,identity.team")

;; ----- HTTP server -----

(defonce hot-reload (bool (or (env :hot-reload) false)))
(defonce auth-server-port (Integer/parseInt (or (env :port) "3003")))

;; ----- URLs -----

(defonce auth-server-url (or (env :auth-server-url) (str "http://localhost:" auth-server-port)))
(defonce ui-server-url (or (env :ui-server-url) "http://localhost:3559"))

;; ----- AWS SQS -----

(defonce aws-access-key-id (env :aws-access-key-id))
(defonce aws-secret-access-key (env :aws-secret-access-key))

(defonce aws-sqs-email-queue (env :aws-sqs-email-queue))

;; ----- JWT -----

(defonce passphrase (env :open-company-auth-passphrase))

;; ----- AWS S3 Store -----

(defonce secrets-bucket (env :aws-secrets-bucket))
(defonce secrets-file
  (if-let [e (env :env)]
    (str "store-" e)
    "store"))