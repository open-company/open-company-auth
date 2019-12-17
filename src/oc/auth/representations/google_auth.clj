(ns oc.auth.representations.google-auth
  "Resource representation functions for google oauth."
  (:require [oc.lib.hateoas :as hateoas]
            [oc.auth.config :as config]
            [oc.lib.oauth :as oauth]
            [clj-oauth2.client :as oauth2]))

(def ^:private google
  {:redirectURI  "/google/oauth/callback"
   :state        {:team-id "open-company-auth"}})

(defn- google-auth-url
  ([scope] (google-auth-url scope nil))
  ([scope state]
   {:pre [(vector? scope)
          (or (nil? state) (map? state))]}
   (let [orig-state      (:state google)
         goog-state      (oauth/encode-state-string (merge orig-state state))
         oauth-req       (oauth2/make-auth-request config/google goog-state)]
     (:uri oauth-req))))

(defn- google-link
  [rel scope state]
  (hateoas/link-map rel
    hateoas/GET
    (google-auth-url scope state)
    {:accept "application/jwt"}
    {:auth-source "google"
     :authentication "oauth"}))

(defn auth-link
  ([rel] (auth-link rel nil))
  ([rel state] (google-link rel (:scope config/google) state)))

(def auth-settings [(auth-link "authenticate")])