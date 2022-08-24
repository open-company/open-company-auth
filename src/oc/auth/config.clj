(ns oc.auth.config
  "Namespace for the configuration parameters."
  (:require [environ.core :refer (env)]
            [clojure.string :as clj-str]
            [clojure.java.io :as j-io]))

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

;; ----- Logging (see https://github.com/ptaoussanis/timbre) -----

(defonce log-level (if-let [log-level (env :log-level)] (keyword log-level) :info))

;; ----- Sentry -----

(defonce dsn (or (env :open-company-sentry-auth) false))
(defonce sentry-release (or (env :release) ""))
(defonce sentry-deploy (or (env :deploy) ""))
(defonce sentry-debug  (boolean (or (bool (env :sentry-debug)) (#{:debug :trace} log-level))))
(defonce sentry-env (or (env :environment) "local"))
(defonce sentry-config {:dsn dsn
                        :debug sentry-debug
                        :release sentry-release
                        :deploy sentry-deploy
                        :environment sentry-env})

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
(defonce payments-server-url (or (env :payments-server-url) (str "http://" host ":3004")))

;; ----- AWS SQS -----

(defonce aws-access-key-id (env :aws-access-key-id))
(defonce aws-secret-access-key (env :aws-secret-access-key))

(defonce aws-sqs-bot-queue (env :aws-sqs-bot-queue))
(defonce aws-sqs-email-queue (env :aws-sqs-email-queue))
(defonce aws-sqs-slack-router-auth-queue (env :aws-sqs-slack-router-auth-queue))
(defonce aws-sqs-expo-queue (env :aws-sqs-expo-queue))
(defonce aws-sqs-payments-queue (env :aws-sqs-payments-queue))
(defonce aws-sqs-notify-queue (env :aws-sqs-notify-queue))

(defonce aws-sns-auth-topic-arn (env :aws-sns-auth-topic-arn))

;; ----- AWS Lambda -----

(defonce aws-lambda-expo-prefix (env :aws-lambda-expo-prefix))

;; ----- JWT -----

(defonce passphrase (env :open-company-auth-passphrase))

;; ----- Slack -----

;; Secrets
(defonce slack-client-id (env :open-company-slack-client-id))
(defonce slack-client-secret (env :open-company-slack-client-secret))
(defonce slack-verification-token (env :open-company-slack-verification-token))

(defn- scope->str [& granular-scope]
  (->> (apply concat granular-scope)
       distinct
       (clj-str/join ",")))

;; OAuth configuration
(defonce slack-oauth-url "https://slack.com/oauth/v2/authorize")

(defonce slack-user-scope (scope->str ["identity.avatar"  ;; User avatar
                                       "identity.basic"   ;; User basic infos (name, title, tz etc)
                                       "identity.email"   ;; User email
                                       "identity.team"])) ;; Team infos

(defonce slack-bot-share-scope ["channels:read"           ;; Read list of public channels
                                "groups:read"             ;; New: Read list of private channels
                                "im:read"                 ;; New: Read list of IM convos
                                "mpim:read"               ;; New: Read list of multi user IM convos
                                "chat:write"              ;; New: Send messages in all the above
                                "channels:join"])         ;; New: bot needs to join a channel to post in it

(defonce slack-bot-notifications-scope ["team:read"       ;; Read info of team (extended from identity.team)
                                       "users:read"       ;; Read list of users on the team
                                 "users:read.email"       ;; Read the email of every user on the team
                                         "im:write"])     ;; Send direct messages to every user

(defonce slack-bot-unfurl-scope ["links:read"             ;; Read links posted in Slack that are relevant to out app
                                 "links:write"])          ;; Rewrite those messages link infos

(defonce slack-bot-usage-scope ["im:history"])            ;; New: Used to reply to messages sent directly to the bot with usage

(defonce slack-bot-scope (scope->str ;; ["commands"]      ;; Staging test
                                     slack-bot-share-scope
                                     slack-bot-notifications-scope
                                     slack-bot-unfurl-scope
                                     slack-bot-usage-scope))

;; Bot/App uninstall reporting
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
                                  (slurp (j-io/resource "email-domain-blacklist.txt")) #"\n")))

;; ----- OpenCompany -----

(defonce payments-enabled? (and (bool (env :payments-enabled))
                                (not (clojure.string/blank? aws-sqs-payments-queue))))

;; ----- Digest -----

(defonce digest-times (set (map keyword (clojure.string/split (or (env :digest-times) "700") #","))))
(defonce premium-digest-times (set (map keyword (clojure.string/split (or (env :premium-digest-times) "700,1200,1700") #","))))
(defonce default-digest-time (keyword (or (env :default-digest-time) "700")))

;; ----- DynamoDB -----

(defonce dynamodb-end-point (or (env :dynamodb-end-point) "http://localhost:8000"))

(defonce dynamodb-table-prefix (or (env :dynamodb-table-prefix) "local"))

(defonce dynamodb-opts {:access-key (env :aws-access-key-id)
                        :secret-key (env :aws-secret-access-key)
                        :endpoint dynamodb-end-point})

(defonce invite-throttle-ttl-minutes (Integer/parseInt (or (env :invite-throttle-ttl-minutes) "60"))) ;; minutes

(defonce invite-throttle-max-count (Integer/parseInt (or (env :invite-throttle-max-count) "100"))) ;; 100 invites at most every invite-throttle-ttl-minutes