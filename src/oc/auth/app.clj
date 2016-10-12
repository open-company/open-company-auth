(ns oc.auth.app
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.walk :refer (keywordize-keys)]
            [if-let.core :refer (if-let*)]
            [cheshire.core :as json]
            [raven-clj.core :as sentry]
            [raven-clj.interfaces :as sentry-interfaces]
            [raven-clj.ring :as sentry-mw]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as appenders]
            [compojure.core :as compojure :refer (GET POST PATCH DELETE)]
            [ring.middleware.params :refer (wrap-params)]
            [ring.middleware.reload :refer (wrap-reload)]
            [ring.middleware.cors :refer (wrap-cors)]
            [buddy.auth.middleware :refer (wrap-authentication)]
            [buddy.auth.backends :as backends]
            [ring.util.response :refer (redirect)]
            [com.stuartsierra.component :as component]
            [oc.lib.hateoas :as hateoas]
            [oc.lib.rethinkdb.pool :as pool]
            [oc.auth.components :as components]
            [oc.auth.config :as c]
            [oc.auth.lib.sqs :as sqs]
            [oc.auth.lib.store :as store]
            [oc.auth.lib.jwt :as jwt]
            [oc.auth.lib.ring :as ring]
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

;; ----- Utility Functions -----

(defn- token-link [token]
  (s/join "/" [c/ui-server-url (str "invite?token=" token)]))

;; ----- Response Functions -----

(defn- jwt-debug-response
  "Helper to format a JWT debug response"
  [payload]
  (let [jwt-token (jwt/generate payload)
        response {
          :jwt-token jwt-token
          :jwt-verified (jwt/check-token jwt-token)
          :jwt-decoded (jwt/decode jwt-token)}]
    (ring/json-response response 200)))

(defn- redirect-to-web-ui
  "Send them back to the UI login page with a JWT token or a reason they don't have one."
  [[success? jwt-or-reason]]
  (if success?
    (redirect (str c/ui-server-url "/login?jwt=" jwt-or-reason))
    (redirect (str c/ui-server-url "/login?access=" jwt-or-reason))))

(defn- email-auth-response
  "Return a JWToken for the email auth'd user, or a 401 Unauthorized."
  ([sys req] (email-auth-response sys req false))
  
  ([sys req location]
  (if-let [email (:identity req)] ; email/pass sucessfully auth'd
    (pool/with-pool [conn (-> sys :db-pool :pool)]
      (if-let* [user (user/get-user-by-email conn email) ; user from DB for JWToken
                jwt-user (zipmap email/jwt-props (map user email/jwt-props))]
        ; respond with JWToken
        (let [headers (if location {"Location" (email/user-url (:org-id user) (:user-id user))} {})
              status (if location 201 200)]
          (ring/text-response (jwt/generate jwt-user) status headers))
        (ring/text-response "" 401))) ; couldn't get the auth'd user (unexpected)
    (ring/text-response "" 401)))) ; email/pass didn't auth (expected)

(defn- user-response
  "Return a JSON representation and HATEOAS links for a specific user."
  [user self?]
  (let [email? (= (:auth-source user) "email")
        org-id (:org-id user)
        user-id (:user-id user)
        status (:status user)
        user-map (select-keys user email/public-props)
        user-status-map (if email? (assoc user-map :status status))
        links [(email/self-link org-id user-id)]
        refresh-links (if self? (conj links (if email? email/refresh-link slack/refresh-link)) links)
        delete-links (if (and (not self?) email?) (conj refresh-links (email/delete-link org-id user-id)) refresh-links)
        update-links (if (and self? email?) (conj delete-links (email/partial-update-link org-id user-id)) delete-links)
        invite-links (if (= status "pending") (conj update-links (email/re-invite-link org-id user-id)) update-links)
        response (assoc user-status-map :links invite-links)]
    (ring/json-response response 200 "application/vnd.open-company.user.v1+json")))

(defn- user-enumeration-response
  "Return a JSON collection of users for the org."
  [users url]
  (let [response {:collection {
                  :version "1.0"
                  :href url
                  :links [(hateoas/self-link url "application/vnd.collection+vnd.open-company.user+json;version=1")]
                  :users (sort-by :email (map #(dissoc % :user-id) users))}}]
  (ring/json-response response 200 "application/vnd.collection+vnd.open-company.user+json;version=1")))

(defn- invite-response
  "Return a JSON response for the user that was just invited/re-invited."
  [user]
  (ring/json-response (email/user-links user) 201 {"Location" (email/user-url (:org-id user) (:user-id user))}))

;; ----- Request Handling Functions -----


(defn- auth-settings
  "Return a set of HATEOAS links appropriate to the user's auth status: none, Slack, email"
  [req]
  (if-let* [token (jwt/read-token (:headers req))
            decoded (jwt/decode token)
            org-id (-> decoded :claims :org-id)
            user-id (-> decoded :claims :user-id)]
    ;; auth'd, give settings specific to their authentication source
    (let [authed-settings (if (= (-> decoded :claims :auth-source) "email")
                            (email/authed-settings org-id user-id)
                            (slack/authed-settings org-id user-id))]
      (ring/json-response authed-settings 200))
    ;; not auth'd, give them both settings
    (ring/json-response 
      {:slack slack/auth-settings
       :email email/auth-settings} 200)))

;; Suggested refactor
; (defun- user-enumerate
;   "Return the users in an org."

;   ;; Bad JWToken
;   ([_sys _req :guard? bad-token?] (ring/bad-token-response))

;   ;; Read the JWToken
;   ([sys req] (user-enumerate sys req (identity-from-token req))

;   ;; Missing :org-id
;   ([_sys _req :guard #(missing-param? :org-id %) _id] (ring/error-response "No org for user request" nil 400))

;   ;; Read :org-id
;   ([sys req [org-id user-id]] (user-enumerate sys req [org-id user-id (-> req :params :org-id)]))

;   ;; Mismatched org-ids
;   ([_sys _req [org_id _user_id req-org-id] :guard #(not= (first %) (last %))]
;   (ring/error-response (str "Wrong org " org-id " for org requested " req-org-id) nil 401))

;   ;; Enumerate users
;   ([sys _req [_org_id _user_id req-org-id]]
;   (timbre/info "User enumeration for" req-org-id)
;   (pool/with-pool [conn (-> sys :db-pool :pool)]
;     (if-let* [users (email/users-links conn org-id) ; list of all users in the org
;               _any? (not-empty users)]
;       ;; Remove the requesting user from the list and respond
;       (user-enumeration-response (filter #(not= (:user-id %) user-id) users)
;                                                (:href (email/enumerate-link org-id)))
;       (ring/error-response (str "No org for" org-id) nil 404))))

(defn- user-enumerate
  "Return the users in an org."
  [sys req]
  (if-let* [token    (jwt/read-token (:headers req))
            decoded  (jwt/decode token)
            user-id  (-> decoded :claims :user-id)
            org-id   (-> decoded :claims :org-id)]
    (if-let* [req-org-id (-> req :params :org-id)]
      (do (timbre/info "Request for org" req-org-id)
          (if (= org-id req-org-id)
            (pool/with-pool [conn (-> sys :db-pool :pool)]
              (if-let* [users (email/users-links conn org-id user-id) ; list of all users in the org
                        _any? (not-empty users)]
                (do (timbre/info "User enumeration for" org-id)
                    (user-enumeration-response users (:href (email/enumerate-link org-id))))
                (do
                  (timbre/warn "No org for" org-id)
                  (user-enumeration-response [] "/email/users")))) ; TODO replace with 404 once Slack orgs hold onto users
            (do (timbre/warn "Wrong org for org request")
                (ring/error-response nil 401))))            
      (do (timbre/warn "No org for user request")
          (ring/error-response nil 400)))
    (do
      (timbre/warn "Bad token for request")      
      (ring/error-response "Could note confirm token." 401))))

(defn- user-request
  "Return a JSON representation and HATEOAS links for a specific user."
  [sys req]
  (if-let* [token    (jwt/read-token (:headers req))
            decoded  (jwt/decode token)
            org-id   (-> decoded :claims :org-id)
            user-id  (-> decoded :claims :user-id)]
    (if-let* [req-org-id (-> req :params :org-id)
              req-user-id (-> req :params :user-id)]
      (do (timbre/info "Request for user" req-user-id "of org" req-org-id)
          (if (= org-id req-org-id)
            (pool/with-pool [conn (-> sys :db-pool :pool)]
              (if-let* [user (user/get-user conn req-user-id)
                        _valid (= req-org-id (:org-id user))]
                (user-response user (= user-id req-user-id))
                (do (timbre/warn "No user for user request")
                    (ring/error-response nil 404))))
            (do (timbre/warn "Wrong org for user request")
                (ring/error-response nil 401))))
      (do (timbre/warn "No user or org for user request")
          (ring/error-response nil 400)))
    (do (timbre/warn "Bad token for request")      
        (ring/error-response "Could note confirm token." 401))))

(defn- user-delete
  "Delete the specified user."
  [sys req prefix]
  (if-let* [token    (jwt/read-token (:headers req))
            decoded  (jwt/decode token)
            org-id   (-> decoded :claims :org-id)]
    (pool/with-pool [conn (-> sys :db-pool :pool)]
      (if-let* [del-user-id (-> req :params :user-id)
                _valid-prefix (s/starts-with? del-user-id prefix)
                del-user (user/get-user conn del-user-id)] ; user to delete
        (do (timbre/info "Delete request for" del-user-id)
          (if (= (:org-id del-user) org-id) ; member of the same org
            (do (timbre/info "Deleting" del-user-id)
                (user/delete-user! conn del-user-id)
                (ring/text-response "" 204)) ; All good
            (do (timbre/warn "Unauth'd delete request of" del-user-id)
                (ring/error-response nil 401))))
        (do (timbre/warn "No user for delete request")
            (ring/error-response nil 404))))
    (do (timbre/warn "Bad token for request")      
        (ring/error-response "Could note confirm token." 401))))

;; ----- Slack Request Handling Functions -----

(defn- oauth-callback
  "Redirect browser to web UI after callback from Slack."
  [callback params]
  (if (get params "test")
    (ring/json-response {:test true :ok true} 200)
    (redirect-to-web-ui (callback params))))

(defn- refresh-slack-token
  "Handle request to refresh an expired Slack JWToken by checking if the access token is still valid with Slack."
  [req]
  (if-let* [token    (jwt/read-token (:headers req))
            decoded  (jwt/decode token)
            user-id  (-> decoded :claims :user-id)
            user-tkn (-> decoded :claims :user-token)
            org-id   (-> decoded :claims :org-id)]
    (do (timbre/info "Refresh token request for user" user-id "of org" org-id)
      (if (slack/valid-access-token? user-tkn)
        (do
          (timbre/info "Refreshing token" user-id)
          (ring/text-response (jwt/generate (merge (:claims decoded)
                                                   (store/retrieve org-id)
                                                   {:auth-source "slack"})) 200))
        (do
          (timbre/warn "Invalid access token for" user-id)            
          (ring/error-response "Could note confirm token." 400))))
    (do
      (timbre/warn "Bad refresh token request")      
      (ring/error-response "Could note confirm token." 400))))

;; ----- Email Request Handling Functions -----

(defn- refresh-email-token
  "Handle request to refresh an expired email JWToken by checking if the user still exists in the org."
  [sys req]
  (if-let* [token    (jwt/read-token (:headers req))
            decoded  (jwt/decode token)
            user-id  (-> decoded :claims :user-id)
            org-id   (-> decoded :claims :org-id)]
    (do (timbre/info "Refresh token request for user" user-id " of org" org-id)
      (pool/with-pool [conn (-> sys :db-pool :pool)]
        (let [user (user/get-user conn user-id)]
          (if (and user (= org-id (:org-id user))) ; user still present in the DB and still member of the org
            (do 
              (timbre/info "Refreshing token" user-id)
              (ring/text-response (jwt/generate (zipmap email/jwt-props (map user email/jwt-props))) 200))
            (do
              (timbre/warn "No user or org-id match for token refresh of" user-id)            
              (ring/error-response "Could note confirm token." 401))))))
    (do
      (timbre/warn "Bad refresh token request")      
      (ring/error-response "Could note confirm token." 400))))

(defn- email-user-create
  "Onboard a new user with email address and password authentication."
  [sys req]
  ;; check if the request is well formed JSON
  (if-let* [post-body (:body req)
            json-body (slurp post-body)
            map-body (try (json/parse-string json-body) (catch Exception e false))
            dirty-body (keywordize-keys map-body)
            body (dissoc dirty-body :user-id :org-id)] ; tsk, tsk
    ;; request is well formed JSON, check if it's valid
    (if-let* [email (:email body)
              password (:password body)]
      ;; request is valid, check if the user already exists
      (do (timbre/info "User create request for" email)
          (pool/with-pool [conn (-> sys :db-pool :pool)]
            (if (user/get-user-by-email conn email) ; prior user?
              (do (timbre/warn "User already exists with email" email)
                  (ring/error-response "User with email already exists." 409)) ; already exists
              (if (email/create-user! conn (email/->user body password)) ; doesn't exist, so create the user
                (do (timbre/info "Creating user" email)
                    (email-auth-response sys (assoc req :identity email) true)) ; respond w/ JWToken and location
                (do (timbre/error "Failed creating user" email)
                    (ring/error-response "" 500))))))
      (do (timbre/warn "Invalid request body")
          (ring/error-response "Invalid request body." 400))) ; request not valid
    (do (timbre/warn "Could not parse request body")
        (ring/error-response "Could not parse request body." 400)))) ; request not well formed

(defn- email-update-user
  "Update an existing user's props."
  [sys req]
  ;; check if the request is auth'd
  (if-let* [token    (jwt/read-token (:headers req))
            decoded  (jwt/decode token)
            user-id  (-> decoded :claims :user-id)]
    
    ;; check if the request is well formed JSON
    (if-let* [post-body (:body req)
              json-body (slurp post-body)
              map-body (try (json/parse-string json-body) (catch Exception e false))
              dirty-body (keywordize-keys map-body)
              body (dissoc dirty-body :user-id :org-id)] ; tsk, tsk
      
      ;; request is well formed JSON, check if it's valid
      (if-let* [req-org-id (-> req :params :org-id)
                req-user-id (-> req :params :user-id)
                body-keys (keys body)
                update-keys (filter email/updateable-props body-keys) ; just the updateable props
                no-extra-props? (= update-keys body-keys)
                _valid (not-empty update-keys)] ; any updateable props?
        
        (do (timbre/info "Update request for user" req-user-id)
          
          (if (= user-id req-user-id)

            (pool/with-pool [conn (-> sys :db-pool :pool)]
              (if-let* [user (user/get-user conn req-user-id)]

                (if (= (:auth-source user) "email") ; email user?
                  
                  ;; Everything checks out, so try to update the user
                  (if-let* [updated-user (email/update-user conn user body)]
                    (email-auth-response sys (assoc req :identity (:email updated-user)) true)
                    (do (timbre/error "Failed updating user" user-id)
                        (ring/error-response "" 500)))

                  ;; Not an email user
                  (do (timbre/warn "Update request for Slack user")
                      (ring/error-response nil 405)))
                
                ;; No user by that ID in the DB
                (do (timbre/warn "No user for user request")
                    (ring/error-response nil 404))))
            
            ;; JWToken and request user ID don't match, can only update your own user
            (do (timbre/warn "Wrong user for user request")
                (ring/error-response nil 401))))
        
        ;; Request not valid
        (do (timbre/warn "Invalid request body")
          (ring/error-response "Invalid request body." 400))) ; request not valid

      ;; Request not well-formed
      (do (timbre/warn "Could not parse request body")
          (ring/error-response "Could not parse request body." 400))) ; request not well formed
    
    ;; Not auth'd
    (do (timbre/warn "Bad token for request")      
        (ring/error-response "Could note confirm token." 401))))


(defn- email-user-invite
  "Invite or re-invite a user by their email address."
  [sys req]
  ;; check if the request is auth'd
  (if-let* [token    (jwt/read-token (:headers req))
            decoded  (jwt/decode token)
            org-id   (-> decoded :claims :org-id)]
    ;; request is auth'd, check if the request is well formed JSON
    (if-let* [post-body (:body req)
              json-body (slurp post-body)
              map-body (try (json/parse-string json-body) (catch Exception e false))
              body (keywordize-keys map-body)]
      ;; request is well formed JSON, check if it's valid
      (if-let* [email (:email body)
                _company-name (:company-name body)
                _logo (:logo body)]
        (do (timbre/info "Invite request for" email)
          (pool/with-pool [conn (-> sys :db-pool :pool)]
            (if-let* [user (user/get-user-by-email conn email)
                      user-id (:user-id user)
                      status (:status user)]
              ;; TODO user exists, but in a different org, need a 2nd org invite...
              (if (or (nil? (-> req :params :user-id)) (= (-> req :params :user-id) user-id))
                (if (= status "pending")
                  (do (timbre/info "Re-inviting user" email) 
                      (sqs/send-invite! (sqs/->invite (merge body {:token-link (token-link (:one-time-token user))})
                                                      (-> decoded :claims :real-name)
                                                      (-> decoded :claims :email)))
                      (invite-response user))
                  (do (timbre/warn "Can't re-invite user" email "in status" (:status user))
                      (ring/error-response "User not eligible for reinvite" 409)))
                (do (timbre/warn "Re-invite request didn't match user" user-id)
                    (ring/error-response "" 404)))
              (do (timbre/info "Creating user" email)
                (if-let* [token (str (java.util.UUID/randomUUID))
                          user (email/create-user! conn
                                  (email/->user {:email email
                                                 :org-id org-id
                                                 :one-time-token token}
                                                "pending"
                                                (str (java.util.UUID/randomUUID))))] ; random passwd
                  (do (timbre/info "Inviting user" email)
                      (sqs/send-invite! (sqs/->invite (merge body {:token-link (token-link token)})
                                                      (-> decoded :claims :real-name)
                                                      (-> decoded :claims :email)))
                      (invite-response user))
                  (do (timbre/error "Failed to create user" email)
                      (ring/error-response "" 500)))))))
        (do (timbre/warn "Invalid request body")
            (ring/error-response "Invalid request body." 400))) ; request not valid
      (do (timbre/warn "Could not parse request body")
          (ring/error-response "Could not parse request body." 400))) ; request not well formed
    (do
      (timbre/warn "Bad token for request")      
      (ring/error-response "Could note confirm token." 401))))

(defn- email-auth
  "An attempt to auth, could be email/pass or one time use token (invite or reset password)."
  [sys req]
  (let [request (keywordize-keys req)
        headers (:headers request)
        authorization (or (:authorization headers) (:Authorization headers))]
    
    (if (s/starts-with? authorization "Basic ")
    
      (email-auth-response sys request) ; HTTP Basic Auth

      ; One time use token Auth
      (if-let* [valid (s/starts-with? authorization "Bearer ")
                token (last (s/split authorization #" "))]
        
        ; Provided a token
        (pool/with-pool [conn (-> sys :db-pool :pool)]
          (timbre/info "Token auth request for" token)
          (if-let* [user (user/get-user-by-token conn token)
                    email (:email user)]
            (do
              (timbre/info "Authed" email "with token" token)
              (user/replace-user! conn (:user-id user) (-> user (dissoc :one-time-token) (assoc :status "active")))
              (email-auth-response sys (assoc request :identity email)))
            (do (timbre/warn "No email user for token" token)
                (ring/error-response "" 401)))) ; token not found        
        
        ; No token
        (do (timbre/warn "Invalid token auth request body")
            (ring/error-response "Invalid request body." 400)))))) ; request not valid

(defn- email-basic-auth
  "HTTP Basic Auth function (email/pass) for ring middleware."
  [sys req auth-data]
  (if-let* [email (:username auth-data)
            password (:password auth-data)]
    (pool/with-pool [conn (-> sys :db-pool :pool)] 
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

    ;; Auth API
    
    ;; HATEOAS entry-point
    (GET "/" req (auth-settings req))

    ;; Slack
    (GET "/slack/auth" {params :params} (oauth-callback slack/oauth-callback params)) ; Slack authentication callback
    (GET "/slack/refresh-token" req (refresh-slack-token req)) ; refresh JWToken
    
    ;; Email
    (GET "/email/auth" req (email-auth sys req)) ; authentication request
    (GET "/email/refresh-token" req (refresh-email-token sys req)) ; refresh JWToken
    (POST "/email/users" req (email-user-create sys req)) ; new user creation
    (POST "/org/:org-id/users/invite" req (email-user-invite sys req)) ; new user invite
    (POST "/org/:org-id/users/:user-id/invite" req (email-user-invite sys req)) ; Re-invite
    (PATCH "/org/:org-id/users/:user-id" req (email-update-user sys req)) ; user update
    
    ;; User Management
    (GET "/org/:org-id/users" req (user-enumerate sys req)) ; user enumeration
    (GET "/org/:org-id/users/:user-id" req (user-request sys req)) ; user retrieval
    (DELETE "/org/:org-id/users/:user-id" req (user-delete sys req email/prefix)) ; user/invite removal
    
    ;; Utilities
    (GET "/---error-test---" req (/ 1 0))
    (GET "/---500-test---" req {:status 500 :body "Testing bad things."})
    (GET "/ping" [] (ring/text-response  "OpenCompany auth server: OK" 200)) ; Up-time monitor
    (GET "/test-token" [] (jwt-debug-response {:test "test" :bago "bago"})))) ; JWToken decoding

;; ----- System Startup -----

;; Ring app definition
(defn app [sys]
  (cond-> (auth-routes sys)
    true          wrap-params
    true          (wrap-cors #".*")
    true          (wrap-authentication (backends/basic {:realm "oc-auth"
                                                        :authfn (partial email-basic-auth sys)}))
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
                "AWS SQS email queue: " c/aws-sqs-email-queue "\n"
                "Hot-reload: " c/hot-reload "\n"
                "Sentry: " c/dsn "\n\n"
                "Ready to serve...\n")))

(defn -main
  "Main"
  []
  (start c/auth-server-port))