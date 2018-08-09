(ns oc.auth.representations.google-auth
  "Resource representation functions for google oauth."
  (:require [oc.lib.hateoas :as hateoas]
            [oc.auth.config :as config]))

(defn- google-auth-url
  ([scope] (google-auth-url scope nil))
  ([scope state]
     (str (:oauth-token-uri config/google))))

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
