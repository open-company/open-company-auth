(ns open-company-auth.app
  (:require [defun :refer (defun-)]
            [clojure.java.io :as io]
            [taoensso.timbre :as timbre]
            [compojure.core :refer :all]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.cors :refer (wrap-cors)]
            [ring.util.response :refer [redirect]]
            [raven-clj.ring :as sentry-mw]
            [org.httpkit.server :refer (run-server)]
            [open-company-auth.config :as config]
            [open-company-auth.jwt :as jwt]
            [open-company-auth.ring :as ring]
            [open-company-auth.slack :as slack]))

(defonce ^:private test-response (ring/ring-response "OpenCompany auth server: OK" ring/html-mime-type 200))

(def ^:private test-token {:test "test" :bago "bago"})

(defn- jwt-debug-response
  "Helper to format a JWT debug response"
  [payload]
  (let [jwt-token (jwt/generate payload)
        response {
          :jwt-token jwt-token
          :jwt-verified (jwt/check-token jwt-token)
          :jwt-decoded (jwt/decode jwt-token)}]
    (ring/json-response response ring/json-mime-type 200)))

(defn- auth-settings-response
  [auth-settings]
  (ring/json-response auth-settings ring/json-mime-type 200))

(defun- redirect-to-ui
  "Send them back to the UI login page with a JWT token or a reason they don't have one."

  ([[false reason]] (redirect (str config/ui-server-url "/login?access=" reason)))

  ([[true jwt]] (redirect (str config/ui-server-url "/login?jwt=" jwt))))

(defun- oauth-callback
  ;; for testing purpose
  ([_callback _params :guard #(get % "test")] (ring/json-response {:test true :ok true} ring/json-mime-type 200))

  ([callback params] (redirect-to-ui (callback params))))

(defroutes auth-routes
  (GET "/" [] test-response)
  (GET "/auth-settings" [] (auth-settings-response slack/auth-settings))
  (GET "/slack-oauth" {params :params} (oauth-callback slack/oauth-callback params))
  (GET "/test-token" [] (jwt-debug-response test-token)))

(defn app []
  (cond-> #'auth-routes
   config/hot-reload wrap-reload
   true              wrap-params
   true              (wrap-cors #".*")
   config/dsn        (sentry-mw/wrap-sentry config/dsn)))

(defn start
  "Start a server"
  [port]
  (timbre/merge-config! config/log-config)
  (timbre/info "Starting OC-Auth")
  (run-server (app) {:port port :join? false})
    (println (str "\n" (slurp (io/resource "ascii_art.txt")) "\n"
      "OpenCompany Auth Server\n"
      "Running on port: " port "\n"
      "Hot-reload: " config/hot-reload "\n"
      "Sentry: " config/dsn "\n\n"
      "Ready to serve...\n")))

(defn -main
  "Main"
  []
  (start config/auth-server-port))