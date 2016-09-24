(ns oc.auth.app
  (:require [clojure.java.io :as io]
            [environ.core :as e]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as appenders]
            [compojure.core :refer :all]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.cors :refer (wrap-cors)]
            [ring.util.response :refer [redirect]]
            [raven-clj.ring :as sentry-mw]
            [org.httpkit.server :refer (run-server)]
            [oc.auth.config :as config]
            [oc.auth.store :as store]
            [oc.auth.jwt :as jwt]
            [oc.auth.ring :as ring]
            [oc.auth.slack :as slack]
            [oc.auth.email :as email]))

(defonce ^:private test-response
  {:body    "OpenCompany auth server: OK"
   :headers ring/html-mime-type
   :status  200})

(def ^:private test-token {:test "test" :bago "bago"})

(defn- jwt-debug-response
  "Helper to format a JWT debug response"
  [payload]
  (let [jwt-token (jwt/generate payload)
        response {
          :jwt-token jwt-token
          :jwt-verified (jwt/check-token jwt-token)
          :jwt-decoded (jwt/decode jwt-token)}]
    (ring/json-response response 200)))

(defn- auth-settings-response
  [auth-settings]
  (ring/json-response auth-settings 200))

(defn- redirect-to-ui
  "Send them back to the UI login page with a JWT token or a reason they don't have one."
  [[success? jwt-or-reason]]
  (if success?
    (redirect (str config/ui-server-url "/login?jwt=" jwt-or-reason))
    (redirect (str config/ui-server-url "/login?access=" jwt-or-reason))))

(defn- oauth-callback [callback params]
  (if (get params "test")
    (ring/json-response {:test true :ok true} 200)
    (redirect-to-ui (callback params))))

(defn refresh-slack-token [req]
  (let [decoded (jwt/decode (jwt/read-token (:headers req)))
        uid     (-> decoded :claims :user-id)
        org-id  (-> decoded :claims :org-id)
        user-tkn (-> decoded :claims :user-token)]
    (timbre/info "Refreshing token" uid)
    (if (and user-tkn (slack/valid-access-token? user-tkn))
      (ring/json-response {:jwt (jwt/generate (merge (:claims decoded) (store/retrieve org-id)))} 200)
      (ring/error-response "could note confirm token" 400))))

(defroutes auth-routes
  (GET "/" [] test-response)
  (GET "/auth-settings" [] (auth-settings-response {:slack slack/auth-settings
                                                    :email email/auth-settings}))
  (GET "/slack-oauth" {params :params} (oauth-callback slack/oauth-callback params))
  (GET "/slack/refresh-token" req (refresh-slack-token req))
  (GET "/test-token" [] (jwt-debug-response test-token)))

(when (= "production" (e/env :env))
  (timbre/merge-config!
   {:appenders {:spit (appenders/spit-appender {:fname "/tmp/oc-auth.log"})}}))

(def app
  (cond-> #'auth-routes
    config/hot-reload wrap-reload
    true              wrap-params
    true              (wrap-cors #".*")
    config/dsn        (sentry-mw/wrap-sentry config/dsn)))

(defn start
  "Start a server"
  [port]
  (run-server app {:port port :join? false})
  (println (str "\n" (slurp (io/resource "ascii_art.txt")) "\n"
                "OpenCompany Auth Server\n\n"
                "Running on port: " port "\n"
                "Hot-reload: " config/hot-reload "\n"
                "Sentry: " config/dsn "\n"
                "AWS S3 bucket: " config/secrets-bucket "\n"
                "AWS S3 file: "  config/secrets-file "\n\n"
                "Ready to serve...\n")))

(defn -main
  "Main"
  []
  (start config/auth-server-port))