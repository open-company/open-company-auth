(ns oc.auth.async.slack-router
  "Consume slack messages from SQS. This SQS queue is subscribed to the Slack
   message SNS topic.
  "
  (:require
   [clojure.core.async :as async :refer (<!! >!!)]
   [cheshire.core :as json]
   [oc.lib.sqs :as sqs]
   [oc.lib.db.pool :as pool]
   [oc.auth.config :as config]
   [oc.auth.resources.team :as team-res]
   [oc.auth.resources.slack-org :as slack-res]
   [taoensso.timbre :as timbre]))

;; ----- core.async -----

(defonce slack-router-chan (async/chan 10000)) ; buffered channel

(defonce slack-router-go (atom nil))

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
        (let [event-type (:type event)
              bot-tokens (-> event :tokens :bot)]
          ;; If message is for a token revoked of a bot (we ignore revoke for users atm)
          (when (and (= event-type "tokens_revoked")
                     (seq bot-tokens))
            (let [slack-team-id (:team_id body)]
              (pool/with-pool [conn db-pool]
                (let [slack-org (slack-res/get-slack-org conn slack-team-id)
                      teams (team-res/list-teams-by-index
                             conn
                             :slack-orgs
                             slack-team-id)]
                  ;; If the bot id is the same of our bot:
                  (when (some #(= (:bot-user-id slack-org) %) bot-tokens)
                    (slack-res/delete-slack-org! conn slack-team-id)
                    (doseq [team teams]
                      (team-res/remove-slack-org conn
                                                 (:team-id team)
                                                 slack-team-id))))))))))))
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
  (sqs/ack done-channel msg))

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
