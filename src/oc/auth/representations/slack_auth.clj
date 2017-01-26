(ns oc.auth.representations.slack-auth
  "Resource representation functions for Slack authentication."
  (:require [clojure.string :as s]
            [oc.lib.hateoas :as hateoas]
            [oc.auth.config :as config]))

(def ^:private slack
  {:redirectURI  "/slack/auth"
   :state        "open-company-auth"})

(defn- slack-auth-url 
  ([scope] (slack-auth-url scope nil))
  ([scope state]
  {:pre [(string? scope)
         (or (nil? state)(string? state))]}
  (let [orig-state (:state slack)
        slack-state (if state (s/join ":" [orig-state state]) orig-state)]
    (str "https://slack.com/oauth/authorize?client_id="
       config/slack-client-id
       "&redirect_uri="
       config/auth-server-url (:redirectURI slack)
       "&state="
       slack-state
       "&scope="
       scope))))

(defn auth-link 
  ([rel] (auth-link rel nil))
  ([rel state]
  (hateoas/link-map rel
    hateoas/GET
    (slack-auth-url config/slack-scope state)
    "application/jwt"
    :auth-source "slack")))

(def auth-settings [(auth-link "authenticate") (auth-link "create")])