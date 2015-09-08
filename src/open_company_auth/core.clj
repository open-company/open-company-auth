(ns open-company-auth.core
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [ring.util.response :refer [redirect]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.middleware.cors :refer (wrap-cors)]
            [raven-clj.ring :refer (wrap-sentry)]
            [org.httpkit.server :refer (run-server)]
            [clojure.data.json :as json]
            [open-company-auth.config :as config]
            [clj-slack.oauth :as slack-oauth]
            [clj-slack.users :as slack-users]
            [clj-slack.auth :as slack-auth]
            [open-company-auth.jwt :as jwt]))

(def ^:private slack-endpoint "https://slack.com/api")
(def ^:private slack-connection {:api-url slack-endpoint})

(def ^:private slack {
  :redirectURI  "/slack-oauth"
  :state        "open-company-auth"
  :scope        "identify,read,post"})

(def session-store
  {:store (cookie-store {:key "a 16-byte secret"})})

(defn error-response [error]
  {:body (json/write-str {:ok false :error error})
   :headers {"Content-Type" "application/json"}
   :status 200})

(defroutes auth-routes
  (GET "/" {session :session params :params}
       {:body "it works!"
        :headers {"Content-Type" "text/html"}
        :status 200})
  (GET "/auth-settings" {session :session params :params}
    (let [url (str "https://slack.com/oauth/authorize?client_id="
                   config/slack-client-id
                   "&redirect_uri="
                   config/server-name (:redirectURI slack)
                   "&state="
                   (:state slack)
                   "&scope="
                   (:scope slack))
          settings (merge {:full-url url} slack)]
      {:body (json/write-str settings)
       :headers {"Content-Type" "application/json"}
       :status 200}))
  (GET "/slack-oauth" {session :session params :params}
    (let [parsed-body (slack-oauth/access slack-connection
                                          config/slack-client-id
                                          config/slack-client-secret
                                          (params "code")
                                          (str config/server-name (:redirectURI slack)))
          ok (:ok parsed-body)]
      (if-not ok
        (error-response "invalid slack code")
          (let [access-token (:access_token parsed-body)
                parsed-test-body (slack-auth/test (merge slack-connection {:token access-token}))
                jwt-content (merge parsed-test-body {:access-token access-token})
                jwt (jwt/generate jwt-content)]
            (redirect (str config/web-server-name "/login?jwt=" jwt))))))
  (GET "/test-token" {session :session params :params}
    (let [payload {:test "test" :bago "bago"}
          jwt-token (jwt/generate payload)
          jwt-verified (jwt/check-token jwt-token)
          jwt-decoded (jwt/decode jwt-token)
          resp {:jwt-token jwt-token
                :jwt-verified jwt-verified
                :jwt-decoded jwt-decoded
                }]
      {:body (json/write-str resp)
       :headers {"Content-Type" "application/json"}
       :status 200})))

(defonce hot-reload-routes
  ;; Reload changed files without server restart
  (if config/hot-reload
    (wrap-reload #'auth-routes)
    auth-routes))

(defonce cors-routes
  ;; Use CORS middleware to support in-browser JavaScript requests.
  (wrap-cors hot-reload-routes #".*"))

(defonce sentry-routes
  ;; Use sentry middleware to report runtime errors if we have a raven DSN.
  (if config/dsn
    (wrap-sentry cors-routes config/dsn)
    cors-routes))

(defonce app
  (-> sentry-routes
      (wrap-session session-store)
      (wrap-params)))
  
(defn start [port]
(run-server app {:port port :join? false})
  (println (str "\n Open Company Auth \n"
    "Running on port: " port "\n"
    "Hot-reload: " config/hot-reload "\n"
    "Sentry: " config/dsn "\n\n"
    "Ready to serve...\n")))

(defn -main []
  (start config/web-server-port))