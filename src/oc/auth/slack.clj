(ns oc.auth.slack
  (:require [clojure.string :as s]
            [clj-slack.oauth :as slack-oauth]
            [clj-slack.auth :as slack-auth]
            [clj-slack.core :as slack]
            [clj-slack.users :as slack-users]
            [schema.core :as schema]
            [taoensso.timbre :as timbre]
            [taoensso.truss :as t]
            [oc.lib.hateoas :as hateoas]
            [oc.auth.config :as config]
            [oc.auth.store :as store]
            [oc.auth.jwt :as jwt]
            [oc.auth.user :as user]))

(def prefix "slack-")

(def ^:private slack-endpoint "https://slack.com/api")
(def ^:private slack-connection {:api-url slack-endpoint})

(def ^:private slack
  {:redirectURI  "/slack/auth"
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

(def auth-link (hateoas/link-map "authenticate"
                                 hateoas/GET
                                 (slack-auth-url "bot,users:read")
                                 "text/plain"))

(def auth-retry-link (hateoas/link-map "authenticate-retry"
                                 hateoas/GET
                                 (slack-auth-url "identity.basic,identity.email,identity.avatar,identity.team")
                                 "text/plain"))

(def refresh-link (hateoas/link-map "refresh" 
                                     hateoas/GET
                                     "/slack/refresh-token"
                                     "text/plain"))

(def enumerate-link (hateoas/link-map "users" 
                                      hateoas/GET
                                      "/slack/users"
                                      "application/vnd.collection+vnd.open-company.user+json;version=1"))

(def auth-settings {:links [auth-link auth-retry-link]})

(def authed-settings {:links [refresh-link enumerate-link]})

(defn- prefixed? [s]
  (and (string? s) (.startsWith s prefix)))

(defn- have-user
  [{:keys [name real-name email avatar user-id owner admin] :as m}]
  (t/with-dynamic-assertion-data {:user m} ; (Optional) setup some extra debug data
    (t/have map? m)
    (t/have [:ks= #{:name :real-name :first-name :last-name :email :avatar :user-id :owner :admin}] m)
    (t/have string? name real-name email avatar)
    (t/have prefixed? user-id)
    (t/have boolean? owner admin))
  m)

(defn- coerce-to-user
  "Coerce the given map to a user, return nil if any important attributes are missing"
  [{:keys [id name email] :as user-data}]
  (when (and id name email)
    {:user-id (str prefix id)
     :name (or (:name user-data) "")
     :real-name (or (:real_name_normalized user-data)
                    (:real_name user-data)
                    (if (and (:first_name user-data) (:last_name user-data))
                      (s/join " " [(:first_name user-data) (:last_name user-data)]) 
                      (or
                        (:first_name user-data)
                        (:last_name user-data)
                        (:name user-data)
                        "")))
     :first-name (:first_name user-data)
     :last-name (:last_name user-data)
     :avatar (or (:image_192 user-data)
                 (:image_72 user-data)
                 (:image_48 user-data)
                 (:image_512 user-data)
                 (:image_32 user-data)
                 (:image_24 user-data))
     :email email
     ;; if not provided we assume they're not
     :owner (boolean (:is_owner user-data))
     :admin (boolean (:is_admin user-data))}))

(defn- get-user-info
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
        auth-source  {:auth-source "slack"}
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
               (jwt/generate (merge user secrets org auth-source {:user-token access-token})))
           (jwt/generate (merge user (store/retrieve (:org-id org)) org auth-source {:user-token access-token})))])
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

;; ----- Schema -----

(def SlackUser 
  (merge user/User {
   :owner schema/Bool
   :admin schema/Bool}))