(ns oc.auth.representations.slack-auth
  "Resource representation functions for Slack authentication."
  (:require [oc.lib.hateoas :as hateoas]
            [oc.auth.config :as config]
            [oc.lib.oauth :as oauth]))

(def ^:private slack
  {:redirectURI  "/slack/auth"
   :state        {:team-id "open-company-auth"}})

(defn- slack-auth-url
  ([] (slack-auth-url nil))
  ([state]
   {:pre [(or (nil? state) (map? state))]}
   (let [orig-state (:state slack)
         slack-state (oauth/encode-state-string (merge orig-state state))]
     (str config/slack-oauth-url
          "?client_id="
          config/slack-client-id
          "&redirect_uri="
          config/auth-server-url (:redirectURI slack)
          "&user_scope=" config/slack-user-scope
          "&scope=" config/slack-bot-scope
          "&state=" slack-state))))

(defn- slack-link
  [rel state]
  (hateoas/link-map rel
    hateoas/GET
    (slack-auth-url state)
    {:accept "application/jwt"}
    {:auth-source "slack"
     :authentication "oauth"}))

(defn bot-link
  ([] (bot-link nil))
  ([state] (slack-link "bot" state)))

(defn auth-link
  ([rel] (auth-link rel nil))
  ([rel state] (slack-link rel state)))

(def auth-settings [(auth-link "authenticate") (auth-link "create") (bot-link)])