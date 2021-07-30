(ns oc.auth.lib.slack
  (:require [defun.core :refer (defun)]
            [clojure.string :as cstr]
            [oc.lib.slack :as slack-lib]
            [clj-slack.core :as clj-slack]
            [clj-slack.users :as slack-users]
            [taoensso.timbre :as timbre]
            [oc.lib.db.common :as db-common]
            [oc.auth.config :as config]
            [oc.lib.oauth :as oauth]))

(def ^:private slack-endpoint "https://slack.com/api")
(def ^:private slack-connection {:api-url slack-endpoint})

(def ^:private slack
  {:redirectURI  "/slack/auth"
   :state        {:team-id "open-company-auth"}})

(defn- best-logo
  "Slack has keys of type
   - :image_24
   - :image_44
   - :image_128
   Instead of using hardcoded kw, let's read them
   and return the bigger one"
  ([data-map] (best-logo data-map false))
  ([data-map skip-default?]
   (when (and data-map
              (or (not skip-default?)
                  (not (:image_default data-map))))
     (let [str-prefix "image_"
           keepf (fn [[k v]]
                   (let [sk (some-> k
                                    keyword
                                    name)]
                     (when (cstr/starts-with? sk str-prefix)
                       [(subs sk (count str-prefix)) v])))
           images-array (vec (keep keepf data-map))]
       (some->> images-array
                (sort-by first)
                first
                second)))))


(defn logo-url-from-response
  "Possible keys are [:image_230 :image_132 :image_88 :image_44 :image_34]"
  [response]
  (let [icon (merge (:icon response) response (:team response) (-> response :team :icon))]
    (best-logo icon true))) ; don't return the Slack default

(defn- avatar-url
  "Possible keys are: [:image_512 :image_192 :image_72 :image_48 :image_32 :image_24]"
  [user-data]
  (let [icon-map (merge (:profile user-data) user-data)]
    (best-logo icon-map)))

(defn- user-from-response [response]
  (merge response
         (:user response)
         (-> response :user :profile)
         (:authed_user response)
         (-> response :authed_user :profile)))

(defn- coerce-to-user
  "Coerce the given map to a user."
  ([response] (coerce-to-user (user-from-response response)
                              (:team response)))

  ([user-data team-data]
  (let [user-name (or (:real_name_normalized user-data)
                      (:real_name user-data)
                      (:name user-data))
        split-name (when user-name (cstr/split user-name #"\s" 2))
        name-size (count split-name)
        splittable-name? (= name-size 2)
        slack-org-id (or (:team_id user-data) (:id team-data))
        slack-display-name (or (:display_name_normalized user-data)
                               (:display_name user-data)
                               (:name user-data))
        first-name (cond (seq (:first_name user-data)) (:first_name user-data)
                         (= name-size 1)               user-name
                         splittable-name?              (first split-name)
                         :else                         "")
        last-name (cond (seq (:last_name user-data)) (:last_name user-data)
                        splittable-name?             (last split-name)
                        :else                        "")
        title (or (:title user-data)
                  (get-in user-data [:profile :title]))
        avatar (avatar-url user-data)
        logo (best-logo team-data)
        email (or (:email user-data)
                  (get-in user-data [:profile :email]))
        user-info-base {:user-id (db-common/unique-id)
                        :slack-id (:id user-data)
                        :slack-org-id slack-org-id
                        :name (:real_name user-data)
                        :first-name first-name
                        :last-name last-name
                        :timezone (:tz user-data)
                        :title title
                        :avatar-url avatar
                        :email email}]
    (cond-> user-info-base
      (not (cstr/blank? (:name team-data)))
      (assoc :slack-org-name (:name team-data))
      (not (cstr/blank? (:domain team-data)))
      (assoc :slack-domain (:domain team-data))
      (not (cstr/blank? slack-display-name))
      (assoc :display-name slack-display-name)
      (not (cstr/blank? logo))
      (assoc :logo-url logo)))))

(defn get-user-info
  "Given a Slack token, the scope of the token and a user ID, retrieve the user's information
   using the proper endpoint depending on the provided token."
  [access-token scope slack-id]
  {:pre [(string? access-token) (string? slack-id)]}
  (let [conn (merge slack-connection {:token access-token})
        response (if (= scope "bot")
                   (slack-users/info conn slack-id)
                   (clj-slack/slack-request conn "users.identity"))]
    (if (:ok response)
      (coerce-to-user response)
      (throw (ex-info "Error response from Slack API while retrieving user data"
                      {:response response :slack-id slack-id :scope scope})))))

(defn get-team-info
  "Get the logo for the Slack org with team.info if we don't already have it."
  [bot-token {logo-url :logo-url slack-org-id :slack-org-id
              slack-domain :slack-domain slack-org-name :slack-org-name}]
  (timbre/info "Retrieving Slack team info for" slack-org-id)
  (let [update-team-data? (or (not slack-domain)
                              (not logo-url)
                              (not slack-org-name))
        response (when update-team-data? ; get team.info if we need it
                   (try
                     (slack-lib/get-team-info bot-token)
                     (catch Exception e ; may not have sufficient scope to make this call (need bot perms)
                       (timbre/info e))))
        updated-logo-url (or
                          (logo-url-from-response response)
                          logo-url)
        updated-slack-org-name (or (:name response)
                                   slack-org-name)
        updated-slack-domain (or (:domain response)
                                 slack-domain)]
    (cond-> {:slack-org-id slack-org-id}
      (seq updated-slack-domain)   (assoc :slack-domain updated-slack-domain)
      (seq updated-slack-org-name) (assoc :slack-org-name updated-slack-org-name)
      (seq updated-logo-url)       (assoc :logo-url updated-logo-url))))

(defn token-scope [response]
  (if (= (:token_type response) "bot") "bot" "team"))

(defn auth-test
  "Perform the auth.test method call:
   Auth.test:
   {:ok true,
    :url \"https://opencompanyhq.slack.com/\",
    :team \"Carrot\",
    :user \"carrot-local\",
    :team_id \"T06SBMH60\",
    :user_id \"U6DRJF3EC\",
    :bot_id \"B6EFBE4R0\",
    :is_enterprise_install false}"
  [access-token]
  (let [conn (merge slack-connection {:token access-token})
        auth-test-response (clj-slack/slack-request conn "auth.test")]
    (if (:ok auth-test-response)
      {:slack-org-name (:team auth-test-response)
       :slack-id (:user_id auth-test-response)
       :slack-org-id (:team_id auth-test-response)
       :bot {:display-name (:user auth-test-response)
             :id (:bot_id auth-test-response)}}
      (timbre/warn "Access token could not be validated"
                   {:auth-test auth-test-response
                    :access-token access-token}))))

(defn valid-access-token?
  "Validate the token depending on its scope:
   - if it's a bot token we can evaluate using the auth.test method
   - for user tokens instead, we have to try an api call, like users.identity"
  [access-token token-scope slack-id]
  (if (= token-scope "bot")
    (auth-test access-token)
    (get-user-info access-token token-scope slack-id)))

;; ¯\_(ツ)_/¯
(defn- fixed-decode-state-string
  [state-str]
  (let [decoded-state (oauth/decode-state-string state-str)]
    (cond-> decoded-state
      (= "open-company-auth" (:team-id decoded-state))
      (assoc :user-id nil
             :team-id nil))))

(defn oauth-v2-access
  "Exchanges a temporary OAuth code for an API token.
  Provides client-id and client-secret using HTTP Basic auth."
  [code redirect-uri]
  (let [conn (merge slack-connection
                    {:skip-token-validation true
                     :basic-auth [config/slack-client-id config/slack-client-secret]})
        oauth-payload {"code" code
                       "redirect_uri" redirect-uri}]
    (clj-slack/slack-request conn "oauth.v2.access" oauth-payload)))

(defn- swap-code-for-user
  "Given a code from Slack, use the Slack OAuth library to swap it out for an access token.
  If the swap works, then test the access token to get user information.
  Possible state format:
    open-company-auth:/redirect/path first user auth, no user or team id just yet
    open-company-auth:{team-id}:{user-id}:/redirect/path adding team or bot
    open-company-auth:{team-id}:{user-id}:/redirect/path:{slack-org-id} second step of user auth trying to add the bot"
  [slack-code slack-state]
  (timbre/info "Processing Slack response code with Slack state:" slack-state)
  (let [decoded-state (fixed-decode-state-string slack-state)]
    (if slack-code
      (let [response (oauth-v2-access slack-code (str config/auth-server-url (:redirectURI slack)))
            slack-id      (or (:user_id response) ; identity.basic returns different data
                              (-> response :authed_user :id)) ;; oauth v2
            slack-org-name (or (:team_name response)
                               (-> response :team :name))
            slack-domain (or (:team_domain response)
                             (-> response :team :domain))
            slack-org-id (or (:team_id response) ; identity.basic returns different data
                             (-> response :team :id)) ;; oauth v2
            slack-logo-url (logo-url-from-response response)
            bot-access-token (when (:ok response)
                               (:access_token response))
            slack-bot     {:id (:bot_user_id response)
                           :token bot-access-token}
            user-access-token  (when (:ok response)
                                 (-> response :authed_user :access_token))
            slack-org     (cond-> {:slack-org-id slack-org-id
                                   :bot slack-bot
                                   :slack-token user-access-token}
                            (not (cstr/blank? slack-org-name)) (assoc :slack-org-name slack-org-name)
                            (not (cstr/blank? slack-domain))   (assoc :slack-domain slack-domain)
                            (not (cstr/blank? slack-logo-url)) (assoc :logo-url slack-logo-url))
            user-profile  (user-from-response response)
            team-profile  (:team response)]
        (if (valid-access-token? bot-access-token "bot" slack-id) ;; If user-info is a map it means we have a working token
          ;; valid response and access token
          ;; w/ identity.basic this response contains all user information we can get
          ;; so munge that into the right shape, or get user info if that doesn't work
          (let [fallback-access-token (or bot-access-token user-access-token)
                token-scope (if (seq bot-access-token) "bot" "team")
                user-info (get-user-info fallback-access-token token-scope slack-id)
                team-info (get-team-info bot-access-token slack-org)
                ;; Cleanup empty values
                non-empty-user-info (apply merge
                                           (for [[k v] user-info
                                                 :when (not (cstr/blank? v))]
                                             {k v}))
                user (if user-profile
                       (merge (coerce-to-user user-profile team-profile)
                              non-empty-user-info
                              team-info)
                       (merge user-info
                              team-info))]
            ;; return user and Slack org info
            (merge user
                   slack-org
                   decoded-state))

          ;; invalid response or access token
          (do
            (timbre/warn "Could not swap code for token" {:oauth-response response})
            (merge decoded-state {:error true}))))
      (do
        (timbre/warn "Empty slack code, user denied permission:" slack-code)
        (merge decoded-state {:error true})))))

(defn oauth-callback
  "Handle the callback from Slack, returning either a tuple of:
  [true, {JWToken-contents}]
    or
  [false, {error-description}]"
  [params]
  (cond
    (get params "state") (swap-code-for-user (get params "code") (get params "state"))
    (get params "error") [false "denied"]
    :else                [false "no-code"]))

(defun user-list
  "Given a Slack bot token, list the user roster for the Slack org, excluding restricted, deleted and bots users."
  ([bot-tokens-list :guard sequential?]
   (mapcat user-list bot-tokens-list))
  ([bot-token :guard string?]
   (timbre/info "Lookup Slack user list for bot token:" bot-token)
   (let [conn (merge slack-connection {:token bot-token})
         users (clj-slack/slack-request conn "users.list")]
     (if (:ok users)
       (let [members (:members users)]
         (keep #(when-not (or (:deleted %)
                              (:is_bot %)
                              (:is_restricted %)
                              (:is_ultra_restricted %)
                              (= (:name %) "slackbot")) ; slackbot is_bot is false
                  (-> %
                      coerce-to-user
                      (dissoc :user-id)))
               members))
       (do (timbre/warn "User list could not be retrieved."
                        {:response users :bot-token bot-token})
           false)))))

(defn user-info
  "Given a Slack bot token, return the info on a specified user in the Slack org."
  [bot-token slack-id]
  (let [conn (merge slack-connection {:token bot-token :user slack-id})
        info (clj-slack/slack-request conn "users.info")]
    (if (:ok info)
      (coerce-to-user info)
      (do (timbre/warn "User info could not be retrieved."
                       {:response info :bot-token bot-token})
          false))))

(defn user-profile
  "
  Given a Slack user token and the corresponding user's email address, return the profile info that can only
  be retrieved by the `users.lookupByEmail` method when using a user token.

  Namely this is the display_name of the user, their title and their timezone.
  "
  [user-token email]
  (timbre/info "Lookup Slack user by email:" email " with user token:" user-token)
  (let [info (try
               (slack-lib/slack-api "users.lookupByEmail" {:token user-token
                                                           :email email})
               (catch Exception e {})) ; only bot installing users have the scope to make this call
        slack-display-name (-> info :user :profile :display_name_normalized)
        slack-real-name (-> info :user :profile :real_name_normalized)
        display-name (cond
                        (not (cstr/blank? slack-display-name)) slack-display-name
                        (not (cstr/blank? slack-real-name)) slack-real-name
                        :else "-")] ; can't be blank
    (if (:ok info)
      {:title (-> info :user :profile :title)
       :timezone (-> info :user :tz)
       :display-name display-name}
      (do (timbre/warn "User profile could not be retrieved by email."
                       {:response info :user-token user-token})
          false))))

(defun channels-for
  "Given a sequence of Slack orgs, retrieve the channel list of those that have a bot."

  ;; Initial case
  ([slack-orgs] (channels-for (sort-by :name slack-orgs) []))

  ;; All done case
  ([slack-orgs :guard empty? channels] (vec channels))

  ;; No bot for this Slack org
  ([slack-orgs :guard #(nil? (:bot-token (first %))) channels] (channels-for (rest slack-orgs) channels))

  ;; Retrieve channel for this Slack org
  ([slack-orgs channels]
  (let [slack-org (first slack-orgs)
        bot-token (:bot-token slack-org)
        org-channels (try
                       (slack-lib/get-channels bot-token)
                       (catch Exception e
                         (timbre/error e)
                         []))
        sorted-channels (sort-by :name org-channels)
        updated-org (assoc slack-org :channels sorted-channels)]
    (channels-for (rest slack-orgs) (conj channels updated-org)))))