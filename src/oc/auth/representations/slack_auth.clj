(ns oc.auth.representations.slack-auth
  "Resource representation functions for Slack authentication."
  (:require [clojure.string :as s]
            [oc.lib.hateoas :as hateoas]
            [oc.auth.config :as config]
            [oc.auth.lib.oauth :as oauth]))

(def ^:private slack
  {:redirectURI  "/slack/auth"
   :state        {:team-id "open-company-auth"}})

(defn- slack-auth-url
  ([scope] (slack-auth-url scope nil))
  ([scope state]
  {:pre [(string? scope)
         (or (nil? state) (map? state))]}
  (let [orig-state (:state slack)
        slack-state (-> (merge orig-state state)
                        oauth/encode-state-string)]
    (str "https://slack.com/oauth/authorize?client_id="
       config/slack-client-id
       "&redirect_uri="
       config/auth-server-url (:redirectURI slack)
       "&state="
       slack-state
       "&scope="
       scope))))

(defn- slack-link
  [rel scope state]
  (hateoas/link-map rel
    hateoas/GET
    (slack-auth-url scope state)
    {:accept "application/jwt"}
    {:auth-source "slack"
    :authentication "oauth"}))

(defn bot-link
  ([] (bot-link nil))
  ([state] (slack-link "bot" config/slack-bot-scope state)))

(defn auth-link
  ([rel] (auth-link rel nil))
  ([rel state] (slack-link rel config/slack-user-scope state)))

(def auth-settings [(auth-link "authenticate") (auth-link "create") (bot-link)])
