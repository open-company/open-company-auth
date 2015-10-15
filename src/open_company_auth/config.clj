(ns open-company-auth.config
  "Namespace for the configuration parameters."
  (:require [environ.core :refer (env)]))

;; ----- Sentry -----

(defonce dsn (or (env :open-company-sentry-auth) false))

;; ----- Slack -----

(defonce slack-client-id (env :open-company-slack-client-id))
(defonce slack-client-secret (env :open-company-slack-client-secret))

;; ----- HTTP server -----

(defonce hot-reload (or (env :hot-reload) false))
(defonce auth-server-port (Integer/parseInt (or (env :port) "3003")))

;; ----- URLs -----

(defonce auth-server-url (or (env :auth-server-url) (str "http://localhost:" auth-server-port)))
(defonce ui-server-url (or (env :ui-server-url) "http://localhost:3449"))

;; ----- JWT -----

(defonce passphrase (env :open-company-auth-passphrase))