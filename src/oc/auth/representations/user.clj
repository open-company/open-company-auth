(ns oc.auth.representations.user
  "Resource representations for OpenCompany users."
  (:require [clojure.string :as s]
            [defun.core :refer (defun-)]
            [oc.lib.hateoas :as hateoas]
            [oc.lib.jwt :as jwt]
            [oc.lib.api.common :as api-common]
            [oc.auth.config :as config]
            [oc.auth.representations.team :as team-rep]
            [oc.auth.resources.user :as user-res]))

(def media-type "application/vnd.open-company.user.v1+json")
(def collection-media-type "application/vnd.collection+vnd.open-company.user+json;version=1")

(def jwt-props [:user-id :teams :name :first-name :last-name :avatar-url :email])

(defn user-url [user-id] (str "/users/" user-id))

(defn user-link [user-id] (hateoas/link-map "user" hateoas/GET (user-url user-id) media-type))

(defn refresh-link [user-id] (hateoas/link-map "refresh" 
                                hateoas/GET
                                (str (user-url user-id) "/refresh-token")
                                jwt/media-type))

(def teams-link (hateoas/collection-link "/teams" team-rep/collection-media-type))

(defn authed-settings [user-id] {:links [(user-link user-id)
                                         (refresh-link user-id)
                                         teams-link]})

(defun- name-for 
  ([user] (name-for (:first-name user) (:last-name user)))
  ([first-name :guard s/blank? last-name :guard s/blank?] "")
  ([first-name last-name :guard s/blank?] first-name)
  ([first-name :guard s/blank? last-name] last-name)
  ([first-name last-name] (str first-name " " last-name)))

(defn jwt-props-for [user source]
  (-> (zipmap jwt-props (map user jwt-props))
    (assoc :name (name-for user))
    (assoc :source source)))

(defn auth-response
  "Return a JWToken for the user, or and a Location header."
  [user source location?]
  (let [jwt-user (jwt-props-for user source)
        headers (if location? {"Location" (user-url (:user-id user))} {})
        status (if location? 201 200)]
    (api-common/text-response (jwt/generate jwt-user config/passphrase) status headers)))