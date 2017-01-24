(ns oc.auth.api.slack
  "Liberator API for Slack callback to auth service."
  (:require [compojure.core :as compojure :refer (defroutes GET OPTIONS)]
            [ring.util.response :refer (redirect)]
            [oc.lib.rethinkdb.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.auth.lib.slack :as slack]
            [oc.auth.config :as config]))

;; ----- Slack Request Handling Functions -----

(defn- redirect-to-web-ui
  "Send them back to the UI login page with a JWT token or a reason they don't have one."
  [[success? jwt-or-reason]]
  (if success?
    (redirect (str config/ui-server-url "/login?jwt=" jwt-or-reason))
    (redirect (str config/ui-server-url "/login?access=" jwt-or-reason))))

(defn- slack-callback
  "Redirect browser to web UI after callback from Slack."
  [conn params]
  (println params) ; code and state
  (if (get params "test")
    (api-common/json-response {:test true :ok true} 200)
    (redirect-to-web-ui (slack/oauth-callback params))))

; (defn- refresh-slack-token
;   "Handle request to refresh an expired Slack JWToken by checking if the access token is still valid with Slack."
;   [req]
;   (if-let* [token    (jwt/read-token (:headers req))
;             decoded  (jwt/decode token)
;             user-id  (-> decoded :claims :user-id)
;             user-tkn (-> decoded :claims :user-token)
;             org-id   (-> decoded :claims :org-id)]
;     (do (timbre/info "Refresh token request for user" user-id "of org" org-id)
;       (if (slack/valid-access-token? user-tkn)
;         (do
;           (timbre/info "Refreshing token" user-id)
;           (ring/text-response (jwt/generate (merge (:claims decoded)
;                                                    (store/retrieve org-id)
;                                                    {:auth-source "slack"})) 200))
;         (do
;           (timbre/warn "Invalid access token for" user-id)            
;           (ring/error-response "Could note confirm token." 400))))
;     (do
;       (timbre/warn "Bad refresh token request")      
;       (ring/error-response "Could note confirm token." 400))))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      (OPTIONS "/slack/auth" {params :params} (pool/with-pool [conn db-pool] (slack-callback conn params)))
      (GET "/slack/auth" {params :params} (pool/with-pool [conn db-pool] (slack-callback conn params)))))) 