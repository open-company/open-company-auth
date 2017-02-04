(ns oc.auth.representations.user
  "Resource representations for OpenCompany users."
  (:require [clojure.string :as s]
            [defun.core :refer (defun defun-)]
            [cheshire.core :as json]
            [oc.lib.hateoas :as hateoas]
            [oc.lib.jwt :as jwt]
            [oc.lib.api.common :as api-common]
            [oc.auth.config :as config]
            [oc.auth.representations.media-types :as mt]))

(def representation-props [:user-id :first-name :last-name :email :avatar-url :created-at :updated-at])
(def jwt-props [:user-id :first-name :last-name :name :email :avatar-url :teams :admin])

(defun url
  ([user-id :guard string?] (str "/users/" user-id))
  ([user :guard map?] (url (:user-id user))))

(defn- admin-url [team-id user-id]
  (s/join "/" ["/teams" team-id "admins" user-id]))

(defn- self-link [user-id] (hateoas/self-link (url user-id) {:accept mt/user-media-type}))

(defn- item-link [user-id] (hateoas/item-link (url user-id) {:accept mt/user-media-type}))

(defn- user-link [user-id] (hateoas/link-map "user" hateoas/GET (url user-id) {:accept mt/user-media-type}))

(defn- partial-update-link [user-id] (hateoas/partial-update-link (url user-id) {:accept mt/user-media-type
                                                                                 :content-type mt/user-media-type}))

(defn- delete-link [user-id] (hateoas/delete-link (url user-id) {:ref mt/user-media-type}))

(defn- remove-link [user-id] (hateoas/remove-link (url user-id) {} {:ref mt/user-media-type}))

(defn refresh-link [user-id] (hateoas/link-map "refresh" 
                                hateoas/GET
                                (str (url user-id) "/refresh-token")
                                {:accept jwt/media-type}))

(def teams-link (hateoas/collection-link "/teams" {:accept mt/team-collection-media-type}))

(defn authed-settings [user-id] {:links [(user-link user-id)
                                         (refresh-link user-id)
                                         teams-link]})

(defn- admin-action-link
  "If a user is an admin, a link to remove them, if not, a link to add them"
  [team-id user-id admin?]
  (if admin?
    (hateoas/remove-link (admin-url team-id user-id) {} {:ref mt/admin-media-type})
    (hateoas/add-link hateoas/PUT (admin-url team-id user-id) {} {:ref mt/admin-media-type})))

(defn- user-collection-links
  "HATEOAS links for a user resource in a collection of users"
  [user team-id]
  (let [user-id (:user-id user)]
    (assoc user :links [
      (item-link user-id)
      (partial-update-link user-id)
      (admin-action-link team-id user-id (:admin user))
      (remove-link user-id)])))

(defn- user-links
  "HATEOAS links for a user resource"
  [user]
  (let [user-id (:user-id user)]
    (assoc user :links [
      (self-link user-id)
      (partial-update-link user-id)
      (refresh-link user-id)
      (delete-link user-id)
      teams-link])))

(defun- name-for 
  ([user] (name-for (:first-name user) (:last-name user)))
  ([first-name :guard s/blank? last-name :guard s/blank?] "")
  ([first-name last-name :guard s/blank?] first-name)
  ([first-name :guard s/blank? last-name] last-name)
  ([first-name last-name] (str first-name " " last-name)))

(def jwt-slack-props [:slack-id :slack-token :slack-bots])

(defn jwt-props-for [user source]
  (let [jwt-props (zipmap jwt-props (map user jwt-props))
        slack? (:slack-id user)
        slack-bots? (:slack-bots user)
        slack-props (if slack?
                      (-> jwt-props
                        (assoc :slack-id (:slack-id user))
                        (assoc :slack-token (:slack-token user)))
                      jwt-props)
        bot-props (if slack-bots?
                    (assoc jwt-props :slack-bots (:slack-bots user))
                    slack-props)]
    (-> bot-props
      (assoc :name (name-for user))
      (assoc :auth-source source))))

(defn auth-response
  "Return a JWToken for the user, or and a Location header."
  [user source]
  (let [jwt-user (jwt-props-for user source)
        headers {"Location" (url (:user-id user))}]
    (api-common/text-response (jwt/generate jwt-user config/passphrase) 201 headers)))

(defn render-user-for-collection
  "Create a map of the user for use in a collection in the REST API"
  [team-id user]
  (let [user-id (:user-id user)]
    (-> user
      (select-keys (concat representation-props [:admin :status]))
      (user-collection-links team-id))))

(defn render-user
  "Create a JSON representation of the user for the REST API"
  [user]
  (json/generate-string
    (-> user
      (select-keys representation-props)
      (assoc :password "")
      (user-links))
    {:pretty true}))