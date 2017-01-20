(ns oc.auth.representations.email-auth
  "Resource representation functions for email authentication."
  (:require [oc.lib.hateoas :as hateoas]
            [oc.lib.jwt :as jwt]
            [oc.auth.representations.user :as user-rep]))
            
(def auth-link (hateoas/link-map "authenticate" 
                                 hateoas/GET
                                 "/users/auth"
                                 jwt/media-type
                                 :auth-source "email"))

(def create-link (hateoas/create-link "/users/"
                                 user-rep/media-type
                                 :auth-source "email"))

(def reset-link (hateoas/link-map "reset-password"
                                  hateoas/POST
                                  "/users/reset"
                                  "text/x-email"
                                  :auth-source "email"))

(def auth-settings [auth-link create-link reset-link])