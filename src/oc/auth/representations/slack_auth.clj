(ns oc.auth.representations.slack-auth
  (:require [oc.lib.hateoas :as hateoas]
            [oc.auth.config :as config]))

; [clj-slack.oauth :as slack-oauth]
; [clj-slack.auth :as slack-auth]
; [clj-slack.core :as slack]
; [clj-slack.users :as slack-users]
; [taoensso.truss :as t]

; (def prefix "slack-")

; (def ^:private slack-endpoint "https://slack.com/api")
; (def ^:private slack-connection {:api-url slack-endpoint})

; (def ^:private invite-type "application/vnd.open-company.invitation.v1+json")

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

; (defn user-url [org-id user-id] (s/join "/" ["/org" org-id "users" user-id]))

; (def bot-auth-link (hateoas/link-map "bot"
;                                  hateoas/GET
;                                  (slack-auth-url "bot,users:read")
;                                  "text/plain"))

(defn auth-link [rel] 
  (hateoas/link-map rel
    hateoas/GET
    (slack-auth-url "identity.basic,identity.email,identity.avatar,identity.team")
    "application/jwt"
    :source "slack"))

; (def refresh-link (hateoas/link-map "refresh" 
;                                      hateoas/GET
;                                      "/slack/refresh-token"
;                                      "text/plain"))

; (defn invite-link [org-id] (hateoas/link-map "invite" 
;                                              hateoas/POST
;                                              (s/join "/" ["/org" org-id "users" "invite"])
;                                              invite-type))

; (defn self-link [org-id user-id] (hateoas/self-link (user-url org-id user-id) user-type))

; (defn user-enumerate-link [org-id] (hateoas/link-map "users"
;                                                hateoas/GET
;                                                (s/join "/" ["/org" org-id "users"])
;                                                "application/vnd.collection+vnd.open-company.user+json;version=1"))

; (defn channel-enumerate-link [org-id] (hateoas/link-map "channels"
;                                                hateoas/GET
;                                                (s/join "/" ["/org" org-id "channels"])
;                                                "application/vnd.collection+vnd.open-company.slack-channels+json;version=1"))

(def auth-settings [(auth-link "authenticate") (auth-link "create")])

; (defn authed-settings [org-id user-id] {:links [(self-link org-id user-id)
;                                                 refresh-link
;                                                 bot-auth-link
;                                                 (invite-link org-id)
;                                                 (user-enumerate-link org-id)
;                                                 (channel-enumerate-link org-id)]})

; (defn- prefixed? [s]
;   (and (string? s) (.startsWith s prefix)))

; (defn- have-user
;   [{:keys [name first-name last-name email avatar-url user-id] :as m}]
;   (t/with-dynamic-assertion-data {:user m} ; (Optional) setup some extra debug data
;     (t/have map? m)
;     (t/have [:ks= #{:name :first-name :last-name :email :avatar-url :user-id}] m)
;     (t/have string? name first-name last-name email)
;     (t/have prefixed? user-id))
;   m)

; (defn- coerce-to-user
;   "Coerce the given map to a user, return nil if any important attributes are missing"
;   [{:keys [id name email] :as user-data}]
;   (when (and id name email)
;     {:user-id (str prefix id)
;      :name (or (:name user-data) "")
;      :first-name (if-let [names (s/split (:name user-data) #" " )]
;                     (if (= 2 (count names)) (first names) "")
;                     "")
;      :last-name (if-let [names (s/split (:name user-data) #" " )]
;                     (if (= 2 (count names)) (last names) "")
;                     "")
;      ; avatar by biggest first
;      :avatar-url (or (:image_192 user-data)
;                      (:image_72 user-data)
;                      (:image_48 user-data)
;                      (:image_512 user-data)
;                      (:image_32 user-data)
;                      (:image_24 user-data))
;      :email email}))

; (defn- get-user-info
;   [access-token scope user-id]
;   {:pre [(string? access-token) (string? user-id)]}
;   (let [resp      (slack-users/info (merge slack-connection {:token access-token}) user-id)]
;     (if (:ok resp)
;       (coerce-to-user (merge (:user resp) (-> resp :user :profile)))
;       (throw (ex-info "Error response from Slack API while retrieving user data"
;                       {:response resp :user-id user-id :scope scope})))))

; (defn valid-access-token?
;   "Given a Slack access token, see if it's valid by making a test call to Slack."
;   [access-token]
;   (let [conn      (merge slack-connection {:token access-token})
;         identity  (slack/slack-request conn "users.identity")
;         auth-test (slack-auth/test conn)]
;     (if (or (:ok identity) (:ok auth-test))
;       true
;       (timbre/warn "Access token could not be validated"
;                    {:identity identity :auth-test auth-test}))))

; (defn- swap-code-for-token
;   "Given a code from Slack, use the Slack OAuth library to swap it out for an access token.
;   If the swap works, then test the access token."
;   [slack-code]
;   (let [response     (slack-oauth/access slack-connection
;                                         config/slack-client-id
;                                         config/slack-client-secret
;                                         slack-code
;                                         (str config/auth-server-url (:redirectURI slack)))
;         user-id      (or (:user_id response) (-> response :user :id)) ; identity.basic returns different data
;         secrets      (when (-> response :bot :bot_user_id)
;                        {:bot {:id    (-> response :bot :bot_user_id)
;                               :token (-> response :bot :bot_access_token)}})
;         org          {:org-id   (str prefix (or (:team_id response)
;                                                 (-> response :team :id))) ; identity.basic returns different data
;                       :org-name (or (:team_name response) (-> response :team :name) "")}
;         auth-source  {:auth-source "slack"}
;         access-token (:access_token response)
;         scope        (:scope response)]
;     (if (and (:ok response) (valid-access-token? access-token))
;       ;; w/ identity.basic this response contains all user information we can get
;       ;; so munge that into the right shape or get user info if that doesn't work
;       (let [user (have-user (or (coerce-to-user (:user response))
;                                 (get-user-info access-token scope user-id)))]
;         [true
;          (if secrets
;            (do (store/store! (:org-id org) secrets)
;                (jwt/generate (merge user secrets org auth-source {:user-token access-token})))
;            (jwt/generate (merge user (store/retrieve (:org-id org)) org auth-source {:user-token access-token})))])
;       (do
;         (timbre/warn "Could not swap code for token" {:oauth-response response})
;         [false "Could not swap code for token"]))))

; (defn oauth-callback
;   "Handle the callback from Slack, returning either a tuple of:
;   [true, {JWToken-contents}]
;     or
;   [false, {error-description}]"
;   [params]
;   (cond
;     (get params "error") [false "denied"]
;     (get params "code")  (swap-code-for-token (get params "code"))
;     :else                [false "no-code"]))

; (defn channel-list
;   "Given a Slack bot token, list the public channels for the Slack org."
;   [bot-token]
;   (let [conn      (merge slack-connection {:token bot-token})
;         channels  (slack/slack-request conn "channels.list")]
;     (if (:ok channels)
;       (remove :is_archived (:channels channels)) ; unarchived channels
;       (do (timbre/warn "Channel list could not be retrieved."
;                        {:response channels :bot-token bot-token})
;           false))))
