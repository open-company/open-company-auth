(ns oc.auth.representations.email-auth
  "Resource representation functions for email authentication."
  (:require [oc.lib.hateoas :as hateoas]
            [oc.lib.jwt :as jwt]
            [oc.auth.representations.media-types :as mt]))
            
(def auth-link (hateoas/link-map "authenticate" 
                                 hateoas/GET
                                 "/users/auth"
                                 {:accept jwt/media-type}
                                 {:auth-source "email"
                                 :authentication "basic"}))

(def create-link (hateoas/create-link "/users/"
                                 {:accept jwt/media-type
                                  :content-type mt/user-media-type}
                                 {:auth-source "email"}))

(def reset-link (hateoas/link-map "reset-password"
                                  hateoas/POST
                                  "/users/reset"
                                  {:content-type "text/x-email"}
                                  {:auth-source "email"}))

(def auth-settings [auth-link create-link reset-link])