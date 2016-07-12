(ns open-company-auth.slack
  (:require [clj-slack.oauth :as slack-oauth]
            [clj-slack.auth :as slack-auth]
            [clj-slack.core :as slack]
            [clj-slack.users :as slack-users]
            [taoensso.timbre :as timbre]
            [open-company-auth.config :as config]
            [open-company-auth.store :as store]
            [open-company-auth.jwt :as jwt]))

(def ^:private slack-endpoint "https://slack.com/api")
(def ^:private slack-connection {:api-url slack-endpoint})

(def ^:private slack
  {:redirectURI  "/slack-oauth"
   :state        "open-company-auth"})

(defn- slack-auth-url [scope]
  (str "https://slack.com/oauth/authorize?client_id="
       config/slack-client-id
       "&redirect_uri="
       config/auth-server-url (:redirectURI slack)
       "&state="
       (:state slack)
       "&scope="
       scope))

(def ^:private prefix "slack:")

(def auth-settings (merge {:basic-scopes-url    (slack-auth-url "identity.basic")
                           :extended-scopes-url (slack-auth-url "bot,users:read")}
                          slack))

(defn- get-user-info
  "Given a Slack access token, retrieve the user info from Slack for the specified user id."
  [access-token user-id]
  {:pre [(string? access-token) (string? user-id)]}
  (let [user-info (slack-users/info (merge slack-connection {:token access-token}) user-id)
        user      (:user user-info)
        profile   (:profile user)]
    (if (:ok user-info)
      {:user-id (str prefix user-id)
       :name (:name user)
       :real-name (:real_name profile)
       :avatar (:image_192 profile)
       :email (:email profile)
       :owner (:is_owner user)
       :admin (:is_admin user)}
      (throw (ex-info "Error response from Slack API while retrieving user data"
                      {:response user-info :user-id user-id})))))

(defn valid-access-token?
  "Given a Slack access token, see if it's valid by making a test call to Slack."
  [access-token]
  (let [conn      (merge slack-connection {:token access-token})
        identity  (slack/slack-request conn "users.identity")
        auth-test (slack-auth/test conn)]
    (if (or (:ok identity) (:ok auth-test))
      true
      (timbre/warn "Access token could not be validated"
                   {:identity identity :auth-test auth-test}))))

(defn- swap-code-for-token
  "Given a code from Slack, use the Slack OAuth library to swap it out for an access token.
  If the swap works, then test the access token."
  [slack-code]
  (let [response     (slack-oauth/access slack-connection
                                        config/slack-client-id
                                        config/slack-client-secret
                                        slack-code
                                        (str config/auth-server-url (:redirectURI slack)))
        user-id      (or (:user_id response) (-> response :user :id)) ; identity.basic returns different data
        secrets      (when (-> response :bot :bot_user_id)
                       {:bot {:id    (-> response :bot :bot_user_id)
                              :token (-> response :bot :bot_access_token)}})
        org          {:org-id   (str prefix (or (:team_id response)
                                                (-> response :team :id))) ; identity.basic returns different data
                      :org-name (:team_name response)}
        access-token (:access_token response)]
    (if (and (:ok response) (valid-access-token? access-token))
      (let [user (get-user-info access-token user-id)]
        [true
         (if secrets
           (do (store/store! (:org-id org) secrets)
               (jwt/generate (merge user secrets org {:user-token access-token})))
           (jwt/generate (merge user (store/retrieve (:org-id org)) org {:user-token access-token})))])
      (do
        (timbre/warn "Could not swap code for token" {:oauth-response response})
        [false "Could not swap code for token"]))))

(defn oauth-callback
  "Handle the callback from Slack, returning either a tuple of:
  [true, {JWToken-contents}]
    or
  [false, {error-description}]"
  [params]
  (cond
    (get params "error") [false "denied"]
    (get params "code")  (swap-code-for-token (get params "code"))
    :else                [false "no-code"]))