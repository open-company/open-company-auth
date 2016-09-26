(ns oc.auth.app
  (:require [clojure.java.io :as io]
            [raven-clj.core :as sentry]
            [raven-clj.interfaces :as sentry-interfaces]
            [raven-clj.ring :as sentry-mw]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as appenders]
            [compojure.core :as compojure :refer (GET)]
            [ring.middleware.params :refer (wrap-params)]
            [ring.middleware.reload :refer (wrap-reload)]
            [ring.middleware.cors :refer (wrap-cors)]
            [ring.util.response :refer (redirect)]
            [com.stuartsierra.component :as component]
            [oc.auth.components :as components]
            [oc.auth.config :as c]
            [oc.auth.store :as store]
            [oc.auth.jwt :as jwt]
            [oc.auth.ring :as ring]
            [oc.auth.slack :as slack]
            [oc.auth.email :as email]))

;; Send unhandled exceptions to Sentry
;; See https://stuartsierra.com/2015/05/27/clojure-uncaught-exceptions
(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (timbre/error ex "Uncaught exception on" (.getName thread) (.getMessage ex))
     (when c/dsn
       (sentry/capture c/dsn (-> {:message (.getMessage ex)}
                                 (assoc-in [:extra :exception-data] (ex-data ex))
                                 (sentry-interfaces/stacktrace ex)))))))

;; ----- Request Handling Functions -----

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
    (redirect (str c/ui-server-url "/login?jwt=" jwt-or-reason))
    (redirect (str c/ui-server-url "/login?access=" jwt-or-reason))))

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

;; ----- Request Routing -----

(defn auth-routes [sys]
  (compojure/routes
    (GET "/" [] test-response)
    (GET "/auth-settings" [] (auth-settings-response {:slack slack/auth-settings
                                                      :email email/auth-settings}))
    ;(GET "/auth-settings" [] (auth-settings-response slack/auth-settings))
    (GET "/slack-oauth" {params :params} (oauth-callback slack/oauth-callback params))
    (GET "/slack/refresh-token" req (refresh-slack-token req))
    (GET "/test-token" [] (jwt-debug-response test-token))))

;; ----- System Startup -----

;; Ring app definition
(defn app [sys]
  (cond-> (auth-routes sys)
    true          wrap-params
    true          (wrap-cors #".*")
    c/hot-reload  wrap-reload
    c/dsn         (sentry-mw/wrap-sentry c/dsn)))

;; Start components in production (nginx-clojure)
(when c/prod?
  (timbre/merge-config!
   {:appenders {:spit (appenders/spit-appender {:fname "/tmp/oc-auth.log"})}})
  (timbre/info "Starting production system without HTTP server")
  (def handler
    (-> (components/auth-system {:handler-fn app})
        (dissoc :server)
        component/start
        (get-in [:handler :handler])))
  (timbre/info "Started"))

(defn start
  "Start a development server"
  [port]

  (-> {:handler-fn app :port port}
    components/auth-system
    component/start)

  (println (str "\n" (slurp (io/resource "ascii_art.txt")) "\n"
                "OpenCompany Auth Server\n\n"
                "Running on port: " port "\n"
                "Database: " c/db-name "\n"
                "Database pool: " c/db-pool-size "\n"
                "Hot-reload: " c/hot-reload "\n"
                "Sentry: " c/dsn "\n"
                "Ready to serve...\n")))

(defn -main
  "Main"
  []
  (start c/auth-server-port))