(ns oc.auth.slack
  (:require [clojure.string :as s]
            [clj-slack.oauth :as slack-oauth]
            [clj-slack.auth :as slack-auth]
            [clj-slack.core :as slack]
            [clj-slack.users :as slack-users]
            [taoensso.timbre :as timbre]
            [taoensso.truss :as t]
            [oc.auth.config :as config]
            [oc.auth.store :as store]
            [oc.auth.jwt :as jwt]))

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

(def auth-settings (merge {:basic-scopes-url    (slack-auth-url "identity.basic,identity.email,identity.avatar,identity.team")
                           :extended-scopes-url (slack-auth-url "bot,users:read")
                           :refresh-url (s/join "/" [config/auth-server-url "slack" "refresh-token"])}
                          slack))

(defn prefixed? [s]
  (and (string? s) (.startsWith s prefix)))

(defn have-user
  [{:keys [name real-name email avatar user-id owner admin] :as m}]
  (t/with-dynamic-assertion-data {:user m} ; (Optional) setup some extra debug data
    (t/have map? m)
    (t/have [:ks= #{:name :real-name :email :avatar :user-id :owner :admin}] m)
    (t/have string? name real-name email avatar)
    (t/have prefixed? user-id)
    (t/have boolean? owner admin))
  m)

(defn coerce-to-user
  "Coerce the given map to a user, return nil if any important attributes are missing"
  [{:keys [id name image_192 email] :as user-data}]
  (when (and id name image_192 email)
    {:user-id (str prefix (:id user-data))
     :name (:name user-data)
     :real-name (or (:real_name user-data) name)
     :avatar (:image_192 user-data)
     :email (:email user-data)
     ;; if not provided we assume they're not
     :owner (boolean (:is_owner user-data))
     :admin (boolean (:is_admin user-data))}))

(defn get-user-info
  [access-token scope user-id]
  {:pre [(string? access-token) (string? user-id)]}
  (let [resp      (slack-users/info (merge slack-connection {:token access-token}) user-id)]
    (if (:ok resp)
      (coerce-to-user (merge (:user resp) (-> resp :user :profile)))
      (throw (ex-info "Error response from Slack API while retrieving user data"
                      {:response resp :user-id user-id :scope scope})))))

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
        access-token (:access_token response)
        scope        (:scope response)]
    (if (and (:ok response) (valid-access-token? access-token))
      ;; w/ identity.basic this response contains all user information we can get
      ;; so munge that into the right shape or get user info if that doesn't work
      (let [user (have-user (or (coerce-to-user (:user response))
                                (get-user-info access-token scope user-id)))]
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