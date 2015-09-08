(ns open-company-auth.config
  "Namespace for the configuration parameters."
  (:require [environ.core :refer (env)]))

;; ----- Web server config -----

(defonce hot-reload (or (env :hot-reload) false))
(defonce web-server-port (Integer/parseInt (or (env :port) "3003")))

;; ----- Sentry config -----

(defonce dsn (or (env :open-company-sentry-auth) false))

;; ----- Slack config -----

(defonce slack-client-id (env :open-company-slack-client-id))
(defonce slack-client-secret (env :open-company-slack-client-secret))

(defonce server-name (env :auth-server-name))
(defonce web-server-name (env :web-server-name))

;; ----- JWT -----

(defonce passphrase (env :open-company-auth-passphrase))