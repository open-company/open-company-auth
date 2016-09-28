(ns oc.auth.app
  (:require [clojure.java.io :as io]
            [if-let.core :refer (if-let*)]
            [raven-clj.core :as sentry]
            [raven-clj.interfaces :as sentry-interfaces]
            [raven-clj.ring :as sentry-mw]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as appenders]
            [compojure.core :as compojure :refer (GET)]
            [ring.middleware.params :refer (wrap-params)]
            [ring.middleware.reload :refer (wrap-reload)]
            [ring.middleware.cors :refer (wrap-cors)]
            [buddy.auth.middleware :refer (wrap-authentication)]
            [buddy.auth.backends :as backends]
            [ring.util.response :refer (redirect)]
            [com.stuartsierra.component :as component]
            [oc.lib.rethinkdb.pool :as pool]
            [oc.auth.components :as components]
            [oc.auth.config :as c]
            [oc.auth.store :as store]
            [oc.auth.jwt :as jwt]
            [oc.auth.ring :as ring]
            [oc.auth.slack :as slack]
            [oc.auth.email :as email]
            [oc.auth.user :as user]))

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

;; ----- Response Functions -----

(def ^:private test-token {:test "test" :bago "bago"})

(defonce ^:private test-response (ring/text-response  "OpenCompany auth server: OK" 200))

(defonce ^:private unauth-response
  (ring/text-response "" 401))

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

(defn- email-auth-response
  "Return a JWToken for the email auth'd user, or a 401 Unauthorized."
  [sys req]
  (if-let* [email (:identity req) ; email/pass sucessfully auth'd
            db-pool (-> sys :db-pool :pool)]
    (pool/with-pool [conn db-pool]
      (if-let* [user (user/get-user-by-email conn email) ; user from DB for JWToken
                clean-user (dissoc user :created-at :updated-at :password-hash)
                sourced-user (assoc clean-user :auth-source "email")]
        ; respond with JWToken
        (ring/text-response (jwt/generate sourced-user) 200)
        unauth-response)) ; couldn't get the auth'd user (unexpected)
    unauth-response)) ; email/pass didn't auth (expected)

;; ----- Request Handling Functions -----

(defn- auth-settings [req]
  (if-let* [token (jwt/read-token (:headers req))
            decoded (jwt/decode token)
            auth-source (-> decoded :claims :auth-source)]
    ;; auth'd, give settings specific to their authentication source
    (if (= auth-source "email")
      (auth-settings-response {:refresh-url (:refresh-url email/auth-settings)})
      (auth-settings-response {:refresh-url (:refresh-url slack/auth-settings)}))
    ;; not auth'd, give them both settings
    (auth-settings-response 
      {:slack slack/auth-settings
       :email email/auth-settings})))

(defn- oauth-callback [callback params]
  (if (get params "test")
    (ring/json-response {:test true :ok true} 200)
    (redirect-to-ui (callback params))))

(defn- refresh-slack-token [req]
  (if-let* [token    (jwt/read-token (:headers req))
            decoded  (jwt/decode token)
            uid      (-> decoded :claims :user-id)
            org-id   (-> decoded :claims :org-id)
            user-tkn (-> decoded :claims :user-token)]
    (do (timbre/info "Refreshing token" uid)
      (if (and user-tkn (slack/valid-access-token? user-tkn))
        (ring/text-response (jwt/generate (merge (:claims decoded)
                                                 (store/retrieve org-id)
                                                 {:auth-source "slack"})) 200)
        (ring/error-response "could note confirm token" 400)))
    (ring/error-response "could note confirm token" 400)))

(defn- refresh-email-token [sys req]
  (if-let* [token    (jwt/read-token (:headers req))
            decoded  (jwt/decode token)
            uid      (-> decoded :claims :user-id)
            org-id   (-> decoded :claims :org-id)
            db-pool (-> sys :db-pool :pool)]
    (do (timbre/info "Refreshing token" uid)
      (pool/with-pool [conn db-pool]
        (let [user (user/get-user conn uid)]
          (if (and user (= org-id (:org-id user))) ; user still present in the DB and still member of the org
            (ring/text-response (jwt/generate (merge (:claims decoded)
                                                   {:auth-source "email"})) 200)
            (ring/error-response "could note confirm token" 400)))))
    (ring/error-response "could note confirm token" 400)))

(defn- email-auth [sys req auth-data]
  (let [db-pool (-> sys :db-pool :pool)
        email (:username auth-data)
        password (:password auth-data)]
    (pool/with-pool [conn db-pool] 
      (if (email/authenticate? conn email password)
        (do 
          (timbre/info "Authed:" email)
          email)
        (do
          (timbre/info "Failed to auth:" email) 
          false)))))

;; ----- Request Routing -----

(defn- auth-routes [sys]
  (compojure/routes
    (GET "/" [] test-response)
    (GET "/auth-settings" req (auth-settings req))
    (GET "/slack-oauth" {params :params} (oauth-callback slack/oauth-callback params))
    (GET "/slack/refresh-token" req (refresh-slack-token req))
    (GET "/email-auth" req (email-auth-response sys req))
    (GET "/email/refresh-token" req (refresh-email-token sys req))
    (GET "/test-token" [] (jwt-debug-response test-token))))

;; ----- System Startup -----

;; Ring app definition
(defn app [sys]
  (cond-> (auth-routes sys)
    true          wrap-params
    true          (wrap-cors #".*")
    true          (wrap-authentication (backends/basic {:realm "oc-auth"
                                                        :authfn (partial email-auth sys)}))
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