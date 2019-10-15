(ns oc.auth.async.slack-router
  "Consume slack messages from SQS. This SQS queue is subscribed to the Slack
   message SNS topic.
  "
  (:require
   [clojure.core.async :as async :refer (<!! >!!)]
   [cheshire.core :as json]
   [oc.lib.sqs :as sqs-lib]
   [oc.auth.lib.sqs :as sqs]
   [oc.lib.db.pool :as pool]
   [oc.lib.slack :as slack-lib]
   [oc.lib.storage :as storage-lib]
   [oc.auth.config :as config]
   [oc.auth.resources.team :as team-res]
   [oc.auth.resources.user :as user-res]
   [oc.auth.resources.slack-org :as slack-res]
   [taoensso.timbre :as timbre]))

;; ----- core.async -----

(defonce slack-router-chan (async/chan 10000)) ; buffered channel

(defonce slack-router-go (atom nil))

(defn- remove-slack-medium [user-property]
  (if (= user-property "slack")
    :email
    (keyword user-property)))

(defn- clean-user-mediums [conn user]
  (let [fixed-user-map (-> user
                        (update-in [:digest-medium] remove-slack-medium)
                        (update-in [:notification-medium] remove-slack-medium)
                        (update-in [:reminder-medium] remove-slack-medium))]
    ;; Simply remove slack as medium where it's being used
    (user-res/update-user! conn (:user-id user) fixed-user-map)))

(defn- cleanup-team-users
  "Set all users medium to :email instead of :slack if there is no other Slack org associated
   with one of the user's team."
  [conn team removing-slack-org]
  ;; For each user of the team
  (doseq [u (user-res/list-users conn (:team-id team))]
    (let [user (user-res/get-user conn (:user-id u))]
      ;; If the user has at least one medium set to Slack
      (when (or (= (:digest-medium user) "slack")
                (= (:notification-medium user) "slack")
                (= (:reminder-medium user) "slack"))
        (if (= (count (:teams user)) 1)
          ;; if he's part of only one team
          ;; or no other team has another Slack org associated
          (clean-user-mediums conn user)
          ;; else if he's part of multiple teams
          (let [has-another-slack-bot? (atom false)]
            (loop [user-teams (:teams user)]
              (let [t (first user-teams)
                    team-data (team-res/get-team conn t)
                    slack-team-ids (filterv #(not= % (:slack-org-id removing-slack-org)) (:slack-orgs team-data))
                    slack-teams (slack-res/list-slack-orgs-by-ids conn slack-team-ids [:bot-token])
                    slack-team-ids-with-bots (map :slack-org-id (filterv #(seq (:bot-token %)) slack-teams))
                    slack-users-with-bot (remove nil? (map #(get (:slack-users user) (keyword %)) slack-team-ids-with-bots))]
                (if (seq slack-users-with-bot)
                  (reset! has-another-slack-bot? true)
                  (when (seq (rest user-teams))
                    (recur (rest user-teams))))))
            (when-not @has-another-slack-bot?
              (clean-user-mediums conn user))))))))

(defn- email-admins-via-sqs [conn org admins]
  (let [all-admins (map #(user-res/get-user conn %) admins)
        to (mapv :email all-admins)]
    (sqs/send! sqs/EmailInvite
      (sqs/->email-bot-removed
        org to)
      config/aws-sqs-email-queue)))

(defn- send-slack-message [conn team]
  (let [c {:storage-server-url config/storage-server-url
           :auth-server-url config/auth-server-url
           :passphrase config/passphrase}
        orgs (storage-lib/orgs-team-for c {:user-id (first (:admins team))} (:team-id team))]
    (doseq [org orgs]
      (email-admins-via-sqs conn org (:admins team)))
    (slack-lib/message-webhook
     config/slack-customer-support-webhook
     (str "Carrot Auth Service"
      (cond
       (= config/short-server-name "staging")
       " (staging)"
       (= config/short-server-name "localhost")
       " (localhost)"))
     (str "<!here> Carrot bot was removed for the following orgs:"
      (clojure.string/join ","
       (map #(str
              " <" config/dashboard-endpoint "/orgs/" (:slug %)
              "|" (:name %) " (" (count (:admins team)) " admin" (when (not= (count (:admins team)) 1) "s") ")>")
        orgs))))))

(defn slack-event
  "
  Handle a message event from Slack. Ignore events that aren't threaded, or that are from us.
  Idea here is to do very minimal processing and get a 200 back to Slack as fast as possible as this is a 'fire hose'
  of requests. So minimal logging and minimal handling of the request.
  Message events look like:
  {'token' 'IxT9ZaxvjqRdKxYtWdTw21Xv',
   'team_id' 'T06SBMH60',
   'api_app_id' 'A0CHN2UDB',
   'event' {'type' 'message',
            'user' 'U06SBTXJR',
            'text' 'Call me back here',
            'thread_ts' '1494262410.072574',
            'parent_user_id' 'U06SBTXJR',
            'ts' '1494281750.011785',
            'channel' 'C10A1P4H2',
            'event_ts' '1494281750.011785'},
    'type' 'event_callback',
    'authed_users' ['U06SBTXJR'],
    'event_id' 'Ev5B8YSYQ6',
    'event_time' 1494281750}
  "
  [db-pool body]
  (let [type (:type body)
        token (:token body)
        event (:event body)
        channel (:channel event)
        thread (:thread_ts event)]
    ;; Token check
    (if-not (= token config/slack-verification-token)

      ;; Eghads! It might be a Slack impersonator!
      (timbre/warn "Slack verification token mismatch, request provided:" token)

      ;; Token check is A-OK
      (when (= type "event_callback")
        (let [event-type (:type event)]
          (when (= event-type "tokens_revoked")
            (let [slack-team-id (:team_id body)]
              (pool/with-pool [conn db-pool]
                (let [teams (team-res/list-teams-by-index conn :slack-orgs slack-team-id [:slack-orgs :admins])
                      slack-org (slack-res/get-slack-org conn slack-team-id)]
                  (slack-res/delete-slack-org! conn slack-team-id)
                  (doseq [team teams]
                    ;; Remove the slack org from the team
                    (team-res/remove-slack-org conn
                                               (:team-id team)
                                               slack-team-id)
                    (cleanup-team-users conn team slack-org)
                    ;; If we have the webhook setup send the message in Slack
                    ;; notifying the remove of the bot
                    (when config/slack-customer-support-webhook
                      (send-slack-message conn team))))))))))))
;; ----- SQS handling -----

(defn- read-message-body
  "
  Try to parse as json, otherwise use read-string.
  "
  [msg]
  (try
    (json/parse-string msg true)
    (catch Exception e
      (read-string msg))))

(defn sqs-handler
  "Handle an incoming SQS message to the auth service."
  [msg done-channel]
  (let [msg-body (read-message-body (:body msg))
        error (if (:test-error msg-body) (/ 1 0) false)] ; a message testing Sentry error reporting
    (timbre/infof "Received message from SQS: %s\n" msg-body)
    (>!! slack-router-chan msg-body))
  (sqs-lib/ack done-channel msg))

;; ----- Event loop -----

(defn- slack-router-loop
  "Start a core.async consumer of the slack router channel."
  [db-pool]
  (reset! slack-router-go true)
  (async/go (while @slack-router-go
      (timbre/info "Waiting for message on slack router channel...")
      (let [msg (<!! slack-router-chan)]
        (timbre/trace "Processing message on slack router channel...")
        (if (:stop msg)
          (do (reset! slack-router-go false) (timbre/info "Slack router stopped."))
          (try
            (when (:Message msg) ;; data change SNS message
              (let [msg-parsed (json/parse-string (:Message msg) true)]
                (slack-event db-pool msg-parsed)))
            (timbre/trace "Processing complete.")
            (catch Exception e
              (timbre/error e))))))))

;; ----- Component start/stop -----

(defn start
 "Stop the core.async slack router channel consumer."
 [sys]
 (let [db-pool (-> sys :db-pool :pool)]
   (timbre/info "Starting slack router...")
   (slack-router-loop db-pool)))

(defn stop
 "Stop the core.async slack router channel consumer."
  []
  (when @slack-router-go
    (timbre/info "Stopping slack router...")
    (>!! slack-router-chan {:stop true})))
