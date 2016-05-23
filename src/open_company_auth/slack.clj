(ns open-company-auth.slack
  (:require [taoensso.timbre :as timbre]
            [clj-slack.oauth :as slack-oauth]
            [clj-slack.auth :as slack-auth]
            [clj-slack.users :as slack-users]
            [open-company-auth.config :as config]
            [open-company-auth.store :as store]
            [open-company-auth.jwt :as jwt]))

(def ^:private slack-endpoint "https://slack.com/api")
(def ^:private slack-connection {:api-url slack-endpoint})

(def ^:private slack {
  :redirectURI  "/slack-oauth"
  :state        "open-company-auth"
  :scope        "users:read"})

(def ^:private slack-url (str
  "https://slack.com/oauth/authorize?client_id="
  config/slack-client-id
  "&redirect_uri="
  config/auth-server-url (:redirectURI slack)
  "&state="
  (:state slack)
  "&scope="
  (:scope slack)))

(def ^:private prefix "slack:")

(def auth-settings (merge {:full-url slack-url} slack))

(defn- get-user-info
  "Given a Slack access token, retrieve the user info from Slack for the specified user id."
  [access-token user-id]
  (let [user-info (slack-users/info (merge slack-connection {:token access-token}) user-id)
        user      (:user user-info)
        profile   (:profile user)]
    (if (:ok user-info)
      {:user-id (str prefix (:id user))
       :name (:name user)
       :real-name (:real_name profile)
       :avatar (:image_192 profile)
       :email (:email profile)
       :owner (:is_owner user)
       :admin (:is_admin user)}
      (throw (ex-info "Error response from Slack API while retrieving user data"
                      {:response user-info :user-id user-id})))))

(defn- test-access-token
  "Given a Slack access token, see if it's valid by making a test call to Slack.
   Throw an exception if the token is invalid."
  [access-token]
  (let [response (slack-auth/test (merge slack-connection {:token access-token}))]
    (when-not (:ok response)
      (throw (ex-info "Error while testing access token"
                      {:response response})))))

(defn- swap-code-for-token
  "Given a code from Slack, use the Slack OAuth library to swap it out for an access token.
  If the swap works, then test the access token."
  [slack-code]
  (let [response     (slack-oauth/access slack-connection
                                        config/slack-client-id
                                        config/slack-client-secret
                                        slack-code
                                        (str config/auth-server-url (:redirectURI slack)))
        user-id      (:user_id response)
        secrets      (when (-> response :bot :bot_user_id)
                       {:bot {:id    (-> response :bot :bot_user_id)
                              :token (-> response :bot :bot_access_token)}})
        org          {:org-id   (str prefix (:team_id response))
                      :org-name (:team response)}
        access-token (:access_token response)]
    (try
      (test-access-token access-token)
      (if (:ok response)
        (let [user (get-user-info access-token user-id)]
          (timbre/info "store/retrieve")
          [true
           (if secrets
             (do (store/store! (:org-id org) secrets)
                 (jwt/generate (merge user secrets org)))
             (jwt/generate (merge user (store/retrieve (:org-id org)) org)))])
        (throw (ex-info "Invalid slack code" {:response response})))
      (catch Throwable e
        [false (.getMessage e)]))))

;; (defun oauth-callback
;;   "Handle the callback from Slack, returning either a tuple of:
;;   [true, {JWToken-contents}]
;;     or
;;   [false, {error-description}]"

;;   ;; error, presumably user denied our app (in which case error value is "access denied")
;;   ([_params :guard #(get % "error")] [false "denied"])

;;   ;; we got back a code, use it to get user info
;;   ([params :guard #(get % "code")] (swap-code-for-token (params "code")))

;;   ;; no error and no code either, what's happening with you Slack?
;;   ([_params] [false "no-code"]))

(defn oauth-callback
  "Handle the callback from Slack, returning either a tuple of:
  [true, {JWToken-contents}]
    or
  [false, {error-description}]"
  [params]
  (timbre/info "oauth-callback")
  (cond
    (get params "error") [false "denied"]
    (get params "code")  (swap-code-for-token (get params "code"))
    :else                [false "no-code"]))