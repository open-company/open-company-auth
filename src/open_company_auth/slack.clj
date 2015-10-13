(ns open-company-auth.slack
  (:require [ring.util.response :refer [redirect]]
            [clj-slack.oauth :as slack-oauth]
            [clj-slack.auth :as slack-auth]
            [clj-slack.users :as slack-users]
            [open-company-auth.lib.ring :as ring]
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

(defn oauth-callback
  [params]
  (let [parsed-body (slack-oauth/access slack-connection
                                        config/slack-client-id
                                        config/slack-client-secret
                                        (params "code")
                                        (str config/auth-server-url (:redirectURI slack)))
        access-ok (:ok parsed-body)]
    (if-not access-ok
      (ring/error-response "invalid slack code" 401)
      (let [access-token (:access_token parsed-body)
            parsed-test-body (slack-auth/test (merge slack-connection {:token access-token}))
            test-ok (:ok parsed-body)]
        (if-not test-ok
          (ring/error-response "error in test call" 401)
          (let [user-id (:user_id parsed-test-body)
                user-info-parsed (slack-users/info (merge slack-connection {:token access-token}) user-id)
                info-ok (:ok user-info-parsed)]
            (if-not info-ok
              (ring/error-response "error in info call" 401)
              (let [user-obj (:user user-info-parsed)
                    profile-obj (:profile user-obj)
                    jwt-content {:user-id user-id
                                 :name (:name user-obj)
                                 :org-name (:team parsed-test-body)
                                 :org-id (:team_id parsed-test-body)
                                 :real-name (:real_name profile-obj)
                                 :avatar (:image_192 profile-obj)
                                 :email (:email profile-obj)
                                 :owner (:is_owner user-obj)
                                 :admin (:is_admin user-obj)}
                    jwt (jwt/generate jwt-content)]
                (redirect (str config/ui-server-url "/login?jwt=" jwt))))))))))