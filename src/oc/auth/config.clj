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

(defonce prod? (= "production" (env :env)))
(defonce intro? (not prod?))
(defonce short-server-name (or (env :short-server-name) "localhost"))

;; ----- Sentry -----

(defonce dsn (or (env :open-company-sentry-auth) false))
(defonce sentry-release (or (env :release) ""))
(defonce sentry-env (or (env :environment) "local"))
(defonce sentry-config {:dsn dsn
                        :release sentry-release
                        :environment sentry-env})

;; ----- Logging (see https://github.com/ptaoussanis/timbre) -----

(defonce log-level (or (env :log-level) :info))

;; ----- RethinkDB -----

(defonce migrations-dir "./src/oc/auth/db/migrations")
(defonce migration-template "./src/oc/auth/assets/migration.template.edn")

(defonce db-host (or (env :db-host) "localhost"))
(defonce db-port (or (env :db-port) 28015))
(defonce db-name (or (env :db-name) "open_company_auth"))
(defonce db-pool-size (or (env :db-pool-size) (- core-async-limit 21))) ; conservative with the core.async limit

(defonce db-map {:host db-host :port db-port :db db-name})
(defonce db-options (flatten (vec db-map))) ; k/v sequence as clj-rethinkdb wants it

;; ----- HTTP server -----

(defonce hot-reload (bool (or (env :hot-reload) false)))
(defonce auth-server-port (Integer/parseInt (or (env :port) "3003")))

;; ----- Liberator -----

;; see header response, or http://localhost:3000/x-liberator/requests/ for trace results
(defonce liberator-trace (bool (or (env :liberator-trace) false)))
(defonce pretty? (not prod?)) ; JSON response as pretty?

;; ----- URLs -----

(defonce host (or (env :local-dev-host) "localhost"))

(defonce auth-server-url (or (env :auth-server-url) (str "http://" host ":" auth-server-port)))
(defonce ui-server-url (or (env :ui-server-url) (str "http://" host ":3559")))
(defonce storage-server-url (or (env :storage-server-url) (str "http://" host ":3001")))
(defonce dashboard-url (or (env :oc-dashboard-endpoint) (str "http://" host ":4001")))

;; ----- AWS SQS -----

(defonce aws-access-key-id (env :aws-access-key-id))
(defonce aws-secret-access-key (env :aws-secret-access-key))

(defonce aws-sqs-bot-queue (env :aws-sqs-bot-queue))
(defonce aws-sqs-email-queue (env :aws-sqs-email-queue))
(defonce aws-sqs-slack-router-auth-queue (env :aws-sqs-slack-router-auth-queue))
(defonce aws-sqs-expo-queue (env :aws-sqs-expo-queue))

(defonce aws-sns-auth-topic-arn (env :aws-sns-auth-topic-arn))

;; ----- AWS Lambda -----

(defonce aws-lambda-expo-prefix (env :aws-lambda-expo-prefix))

;; ----- JWT -----

(defonce passphrase (env :open-company-auth-passphrase))

;; ----- Slack -----

(defonce slack-client-id (env :open-company-slack-client-id))
(defonce slack-client-secret (env :open-company-slack-client-secret))
(defonce slack-user-scope "identity.avatar,identity.basic,identity.email,identity.team")
(defonce slack-bot-share-scope "channels:read")
(defonce slack-bot-notifications-scope "team:read,users:read,users:read.email")
(defonce slack-bot-unfurl-scope "links:read,links:write")
(defonce slack-bot-scope (clojure.string/join "," ["bot," ;; "commands" need perm in prod app
                                                   slack-bot-share-scope
                                                   slack-bot-notifications-scope
                                                   slack-bot-unfurl-scope]))
(defonce slack-customer-support-webhook (env :open-company-slack-customer-support-webhook))

;; ----- Google Oauth -----

(defonce google-login-uri "https://accounts.google.com")
(defonce google
  {:success-uri "/google/lander"
   :oauth-token-uri (str auth-server-url "/google/oauth")
   :authorization-uri (str google-login-uri "/o/oauth2/auth")
   :access-token-uri (str google-login-uri "/o/oauth2/token")
   :redirect-uri (env :open-company-google-redirect-uri)
   :client-id (env :open-company-google-client-id)
   :client-secret (env :open-company-google-client-secret)
   :access-query-param :access_token
   ;; scopes https://developers.google.com/identity/protocols/googlescopes#oauth2v2
   :scope ["https://www.googleapis.com/auth/userinfo.email",
           "https://www.googleapis.com/auth/userinfo.profile"]
   :grant-type "authorization_code"
   :access-type "online"
   :approval_prompt ""})

;; ----- Email -----

(defonce email-domain-blacklist (rest (clojure.string/split
                                  (slurp (clojure.java.io/resource "email-domain-blacklist.txt")) #"\n")))

;; ----- Stripe -----

(defonce stripe-secret-key         (env :stripe-secret-key))
(defonce stripe-premium-product-id (env :stripe-premium-product-id))
(defonce stripe-default-plan-id    (env :stripe-default-plan-id))

;; ----- OpenCompany -----

(defonce payments-enabled? (bool (env :payments-enabled)))

;; ----- Digest -----

(defonce digest-times (set (map keyword (clojure.string/split (or (env :digest-times) "700,1200,1700") #","))))
