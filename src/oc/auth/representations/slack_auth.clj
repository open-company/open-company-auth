(ns oc.auth.representations.slack-auth
  "Resource representation functions for Slack authentication."
  (:require [oc.lib.hateoas :as hateoas]
            [oc.auth.config :as config]))

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

(defn auth-link [rel] 
  (hateoas/link-map rel
    hateoas/GET
    (slack-auth-url config/slack-scope)
    "application/jwt"
    :auth-source "slack"))

(def auth-settings [(auth-link "authenticate") (auth-link "create")])