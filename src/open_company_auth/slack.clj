(ns open-company-auth.slack
  (:require [defun :refer (defun)]
            [clj-slack.oauth :as slack-oauth]
            [clj-slack.auth :as slack-auth]
            [clj-slack.users :as slack-users]
            [open-company-auth.config :as config]
            [open-company-auth.jwt :as jwt]))

(def ^:private slack-endpoint "https://slack.com/api")
(def ^:private slack-connection {:api-url slack-endpoint})

(def ^:private slack {
  :redirectURI  "/slack-oauth"
  :state        "open-company-auth"
  :scope        "identify,read,post"})

(def ^:private slack-url (str
  "https://slack.com/oauth/authorize?client_id="
  config/slack-client-id
  "&redirect_uri="
  config/auth-server-url (:redirectURI slack)
  "&state="
  (:state slack)
  "&scope="
  (:scope slack)))

(def auth-settings (merge {:full-url slack-url} slack))

(defn- jwt-token-for
  "Given user, profile and org data, package it up into a map and encode it as a JWToken."
  [user profile org]
  (let [jwt-content {
          :user-id (:id user)
          :name (:name user)
          :real-name (:real_name profile)
          :avatar (:image_192 profile)
          :email (:email profile)
          :owner (:is_owner user)
          :admin (:is_admin user)}]
    [true (jwt/generate (merge org jwt-content))]))

(defn- user-info-for
  "Given a Slack access token, retrieve the user info from Slack for the specified user id."
  [access-token org user-id]
  (let [user-info (slack-users/info (merge slack-connection {:token access-token}) user-id)
        user (:user user-info)
        profile (:profile user)]
    (if (:ok user-info)
      (jwt-token-for user profile org)
      [false "user-info-error"])))

(defn- test-access-token
  "
  Given a Slack access token, see if it's valid by making a test call to Slack.
  If it's valid, use it to retrieve the user's info.
  "
  [access-token]
  (let [response (slack-auth/test (merge slack-connection {:token access-token}))
        user-id (:user_id response)
        org-id (str "slack:" (:team_id response))
        org-name (:team response)
        org {:org-id org-id :org-name org-name}]
    (if (:ok response)
      (user-info-for access-token org user-id)
      [false "test-call-error"])))

(defn- swap-code-for-token
  "
  Given a code from Slack, use the Slack OAuth library to swap it out for an access token.
  If the swap works, then test the access token.
  "
  [slack-code]
  (let [parsed-body (slack-oauth/access slack-connection
                                        config/slack-client-id
                                        config/slack-client-secret
                                        slack-code
                                        (str config/auth-server-url (:redirectURI slack)))
        access-token (:access_token parsed-body)]
    (if (:ok parsed-body)
      (test-access-token access-token)
      [false "invalid-slack-code"])))

(defun oauth-callback
  "
  Handle the callback from Slack, returning either a tuple of:

  [true, {JWToken-contents}]

    or

  [false, {error-description}]
  "

  ;; error, presumably user denied our app (in which case error value is "access denied")
  ([_params :guard #(get % "error")] [false "denied"])

  ;; we got back a code, use it to get user info
  ([params :guard #(get % "code")] (swap-code-for-token (params "code")))

  ;; no error and no code either, what's happening with you Slack?
  ([_params] [false "no-code"]))