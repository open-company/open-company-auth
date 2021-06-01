(ns oc.auth.async.slack-api-calls
  "
   Asynchronous tasks that make many slack API calls for user information.
  "
  (:require [clojure.core.async :as async :refer (<!! >!!)]
            [taoensso.timbre :as timbre]
            [oc.lib.db.pool :as pool]
            [oc.lib.slack :as slack-lib]
            [oc.lib.sentry.core :as sentry]
            [oc.auth.resources.user :as user]))


;; ----- core.async -----

(defonce slack-api-calls-chan (async/chan 10000)) ; buffered channel

(defonce slack-api-calls-go (atom nil))

(defn slack-gather-display-names [db-pool team-id slack-org]
  (pool/with-pool [conn db-pool]
    (doseq [user (user/list-users conn team-id [:slack-users])]
      (let [slack-user ((keyword (:slack-org-id slack-org)) (:slack-users user))
            slack-user-info (slack-lib/get-user-info (:bot-token slack-org)
                                                     (:id slack-user))
            display-name (if-not (clojure.string/blank? (:display_name slack-user-info))
                           (:display_name slack-user-info)
                           (:name slack-user-info))]
        (user/update-user! conn
                           (:user-id user)
                           (assoc-in user
                                     [:slack-users
                                      (keyword (:slack-org-id slack-org))
                                      :display-name]
                                     display-name))))))

(defn gather-display-names [team-id slack-org]
  (>!! slack-api-calls-chan {:display-names true
                             :team-id team-id
                             :slack-org slack-org}))

;; ----- Event loop -----

(defn- slack-api-calls-loop
  "Start a core.async consumer of the slack api calls channel."
  [db-pool]
  (reset! slack-api-calls-go true)
  (async/go (while @slack-api-calls-go
      (timbre/info "Waiting for message on slack api calls channel...")
      (let [msg (<!! slack-api-calls-chan)]
        (timbre/trace "Processing message on slack api calls channel...")
        (if (:stop msg)
          (do (reset! slack-api-calls-go false) (timbre/info "Slack api calls stopped."))
          (try
            (when (:display-names msg)
              (slack-gather-display-names db-pool
                                          (:team-id msg)
                                          (:slack-org msg)))
            (timbre/trace "Processing complete.")
            (catch Exception e
              (timbre/warn e)
              (sentry/capture e))))))))

;; ----- Component start/stop -----

(defn start
 "Stop the core.async slack api calls channel consumer."
 [sys]
 (let [db-pool (-> sys :db-pool :pool)]
   (timbre/info "Starting slack api calls...")
   (slack-api-calls-loop db-pool)))

(defn stop
 "Stop the core.async slack api calls channel consumer."
  []
  (when @slack-api-calls-go
    (timbre/info "Stopping slack api calls...")
    (>!! slack-api-calls-chan {:stop true})))