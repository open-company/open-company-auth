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

;; ----- Sentry -----

(defonce dsn (or (env :open-company-sentry-auth) false))

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

(defonce auth-server-url (or (env :auth-server-url) (str "http://localhost:" auth-server-port)))
(defonce ui-server-url (or (env :ui-server-url) "http://localhost:3559"))

;; ----- AWS SQS -----

(defonce aws-access-key-id (env :aws-access-key-id))
(defonce aws-secret-access-key (env :aws-secret-access-key))

(defonce aws-sqs-bot-queue (env :aws-sqs-bot-queue))
(defonce aws-sqs-email-queue (env :aws-sqs-email-queue))
(defonce aws-sqs-slack-router-auth-queue (env :aws-sqs-slack-router-auth-queue))

(defonce aws-sns-auth-topic-arn (env :aws-sns-auth-topic-arn))

;; ----- JWT -----

(defonce passphrase (env :open-company-auth-passphrase))

;; ----- Slack -----

(defonce slack-client-id (env :open-company-slack-client-id))
(defonce slack-client-secret (env :open-company-slack-client-secret))
(defonce slack-verification-token (env :open-company-slack-verification-token))
(defonce slack-user-scope "identity.avatar,identity.basic,identity.email,identity.team")
(defonce slack-comment-scope "users:read,users:read.email,team:read,channels:read,channels:history")
(defonce slack-unfurl-scope "links:read,links:write")
(defonce slack-bot-scope (str slack-comment-scope
                              ","
                              slack-unfurl-scope
                              ",commands,bot,chat:write:bot"))

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