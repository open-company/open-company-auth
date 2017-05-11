(ns oc.auth.lib.slack
  (:require [defun.core :refer (defun)]
            [clojure.string :as s]
            [clj-slack.oauth :as slack-oauth]
            [clj-slack.auth :as slack-auth]
            [clj-slack.core :as slack]
            [clj-slack.users :as slack-users]
            [taoensso.timbre :as timbre]
            [oc.lib.db.common :as db-common]
            [oc.auth.config :as config]))

(def ^:private slack-endpoint "https://slack.com/api")
(def ^:private slack-connection {:api-url slack-endpoint})

(def ^:private slack
  {:redirectURI  "/slack/auth"
   :state        "open-company-auth"})

(defn- coerce-to-user
  "Coerce the given map to a user."
  ([user-data] (coerce-to-user user-data nil))
  
  ([user-data team-data]
  (let [user-name (or (:real_name_normalized user-data)
                      (:real_name user-data)
                      (:name user-data))
        split-name (when user-name (s/split user-name #"\s"))
        name-size (count split-name)
        splittable-name? (= name-size 2)]
    {:user-id (db-common/unique-id)
     :slack-id (:id user-data)
     :slack-org-id (:team_id user-data)
     :name (:name user-data)
     :first-name (cond
                    (= name-size 1) user-name
                    splittable-name? (first split-name)
                    :else "")
     :last-name (if splittable-name? (last split-name) "")
     :avatar-url (or (:image_512 user-data)
                     (:image_192 user-data)
                     (:image_72 user-data)
                     (:image_48 user-data)
                     (:image_32 user-data)
                     (:image_24 user-data)
                     (get-in user-data [:profile :image_512])
                     (get-in user-data [:profile :image_192])
                     (get-in user-data [:profile :image_72])
                     (get-in user-data [:profile :image_48])
                     (get-in user-data [:profile :image_32])
                     (get-in user-data [:profile :image_24]))
     :logo-url (when team-data
                  (or (:image_230 team-data)
                      (:image_132 team-data)
                      (:image_88 team-data)
                      (:image_44 team-data)
                      (:image_34 team-data)
                      nil))
     :email (or (:email user-data)
                (get-in user-data [:profile :email]))})))

(defn get-user-info
  [access-token scope slack-id]
  {:pre [(string? access-token) (string? slack-id)]}
  (let [response (slack-users/info (merge slack-connection {:token access-token}) slack-id)]
    (if (:ok response)
      (coerce-to-user (merge (:user response) (-> response :user :profile)) (:team response))
      (throw (ex-info "Error response from Slack API while retrieving user data"
                      {:response response :slack-id slack-id :scope scope})))))

(defn valid-access-token?
  "Given a Slack access token, see if it's valid by making a test call to Slack."
  [access-token]
  (let [conn      (merge slack-connection {:token access-token})
        identity  (slack/slack-request conn "users.identity")
        auth-test (slack-auth/test conn)]
    (if (or (:ok identity) (:ok auth-test))
      (coerce-to-user (merge (:user identity) (-> identity :user :profile)))
      (timbre/warn "Access token could not be validated"
                   {:identity identity :auth-test auth-test}))))

(defn- swap-code-for-user
  "Given a code from Slack, use the Slack OAuth library to swap it out for an access token.
  If the swap works, then test the access token to get user information."
  [slack-code slack-state]
  (timbre/info "Processing Slack response code with Slack state:" slack-state)
  (let [split-state   (s/split slack-state #":")
        team-id       (when (= (count split-state) 4) (second split-state)) ; team-id from state
        user-id       (when (= (count split-state) 4) (nth split-state 2)) ; user-id from state
        redirect      (when (= (count split-state) 4) (last split-state)) ; redirect URL fragment from state
        response      (slack-oauth/access slack-connection
                                        config/slack-client-id
                                        config/slack-client-secret
                                        slack-code
                                        (str config/auth-server-url (:redirectURI slack)))
        slack-id      (or (:user_id response) (-> response :user :id)) ; identity.basic returns different data
        slack-org     {:slack-org-id (or (:team_id response)
                                     (-> response :team :id)) ; identity.basic returns different data
                       :slack-org-name (or (:team_name response) (-> response :team :name) "")}
        slack-bot     (when (-> response :bot :bot_user_id)
                        {:id (-> response :bot :bot_user_id)
                         :token (-> response :bot :bot_access_token)})
        access-token  (:access_token response)
        scope         (:scope response)
        user-profile  (:user response)
        team-profile  (:team response)]
    (if (and (:ok response) (valid-access-token? access-token))
      ;; valid response and access token
      ;; w/ identity.basic this response contains all user information we can get
      ;; so munge that into the right shape, or get user info if that doesn't work
      (let [user (if user-profile
                    (coerce-to-user user-profile team-profile)
                    (get-user-info access-token scope slack-id))]
        ;; return user and Slack org info
        (merge user slack-org {:bot slack-bot :slack-token access-token :team-id team-id
                               :user-id user-id :redirect redirect}))

      ;; invalid response or access token
      (do
        (timbre/warn "Could not swap code for token" {:oauth-response response})
        {:error true :team-id team-id}))))

(defn oauth-callback
  "Handle the callback from Slack, returning either a tuple of:
  [true, {JWToken-contents}]
    or
  [false, {error-description}]"
  [params]
  (cond
    (get params "error") [false "denied"]
    (get params "code")  (swap-code-for-user (get params "code") (get params "state"))
    :else                [false "no-code"]))

(defn channel-list
  "Given a Slack bot token, list the public channels for the Slack org."
  [bot-token]
  (let [conn (merge slack-connection {:token bot-token})
        channels (slack/slack-request conn "channels.list")]
    (if (:ok channels)
      (remove :is_archived (:channels channels)) ; unarchived channels
      (do (timbre/warn "Channel list could not be retrieved."
                       {:response channels :bot-token bot-token})
          false))))

(defn user-list
  "Given a Slack bot token, list the user roster for the Slack org, excluding restricted, deleted and bots users."
  [bot-token]
  (let [conn (merge slack-connection {:token bot-token})
        users (slack/slack-request conn "users.list")]
    (if (:ok users)
      (let [members (:members users)
            current-users (filter #(not (or (:deleted %)
                                            (:is_bot %)
                                            (:is_restricted %)
                                            (:is_ultra_restricted %)
                                            (= (:name %) "slackbot"))) members)] ; slackbot is_bot is false
        (map #(dissoc % :user-id) (map coerce-to-user current-users)))
      (do (timbre/warn "User list could not be retrieved."
                       {:response users :bot-token bot-token})
          false))))

(defn user-info
  "Given a Slack bot token, return the info on a specified user in the Slack org."
  [bot-token slack-id]
  (let [conn (merge slack-connection {:token bot-token :user slack-id})
        info (slack/slack-request conn "users.info")]
    (if (:ok info)
      (coerce-to-user info)
      (do (timbre/warn "User info could not be retrieved."
                       {:response info :bot-token bot-token})
          false))))

(defun channels-for
  "Given a sequence of Slack orgs, retrieve the channel list of those that have a bot."

  ;; Initial case
  ([slack-orgs] (channels-for slack-orgs []))

  ;; All done case
  ([slack-orgs :guard empty? channels] (vec channels))

  ;; No bot for this Slack org
  ([slack-orgs :guard #(nil? (:bot-token (first %))) channels] (channels-for (rest slack-orgs) channels))

  ;; Retrieve channel for this Slack org
  ([slack-orgs channels]
  (let [slack-org (first slack-orgs)
        bot-token (:bot-token slack-org)]
    (channels-for (rest slack-orgs) (conj channels (assoc slack-org :channels (channel-list bot-token)))))))