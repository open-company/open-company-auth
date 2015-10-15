(ns open-company-auth.app
  (:require [defun :refer (defun-)]
            [compojure.core :refer :all]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.cors :refer (wrap-cors)]
            [ring.util.response :refer [redirect]]
            [raven-clj.ring :refer (wrap-sentry)]
            [org.httpkit.server :refer (run-server)]
            [open-company-auth.config :as config]
            [open-company-auth.jwt :as jwt]
            [open-company-auth.lib.ring :as ring]
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

(defun- oauth-callback
  ;; for testing purpose
  ([_callback _params :guard #(get % "test")] (ring/json-response {:test true :ok true} ring/json-mime-type 200))

  ;; error, presumably user denied our app (in which case error value is "access denied")
  ([_callback _params :guard #(get % "error")]
    (redirect (str config/ui-server-url "/login"))) ; send them back to the login page

  ([callback params] (callback params)))

(defroutes auth-routes
  (GET "/" [] test-response)
  (GET "/auth-settings" [] (auth-settings-response slack/auth-settings))
  (GET "/slack-oauth" {params :params} (oauth-callback slack/oauth-callback params))
  (GET "/test-token" [] (jwt-debug-response test-token)))

(defonce ^:private hot-reload-routes
  ;; Reload changed files without server restart
  (if config/hot-reload
    (wrap-reload #'auth-routes)
    auth-routes))

(defonce ^:private cors-routes
  ;; Use CORS middleware to support in-browser JavaScript requests.
  (wrap-cors hot-reload-routes #".*"))

(defonce ^:private sentry-routes
  ;; Use sentry middleware to report runtime errors if we have a raven DSN.
  (if config/dsn
    (wrap-sentry cors-routes config/dsn)
    cors-routes))

(defonce app
  (wrap-params sentry-routes))

(defn start
  "Start a server"
  [port]
  (run-server app {:port port :join? false})
    (println (str "\n" (slurp (clojure.java.io/resource "./open_company_auth/assets/ascii_art.txt")) "\n"
      "Auth Server\n"
      "Running on port: " port "\n"
      "Hot-reload: " config/hot-reload "\n"
      "Sentry: " config/dsn "\n\n"
      "Ready to serve...\n")))

(defn -main
  "Main"
  []
  (start config/auth-server-port))