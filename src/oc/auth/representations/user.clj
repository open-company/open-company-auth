(ns oc.auth.representations.user
  "Resource representations for OpenCompany users."
  (:require [clojure.string :as s]
            [defun.core :refer (defun defun-)]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [cheshire.core :as json]
            [oc.lib.hateoas :as hateoas]
            [oc.lib.jwt :as jwt]
            [oc.lib.api.common :as api-common]
            [oc.auth.config :as config]
            [oc.auth.representations.media-types :as mt]
            [oc.auth.representations.team :as team-rep]
            [oc.auth.representations.email-auth :as email-rep]
            [oc.auth.resources.user :as user-res]))
(def slack-props [:name :slack-id])
(def oc-props [:user-id :first-name :last-name :email :avatar-url :created-at :updated-at])
(def representation-props (concat slack-props oc-props))
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

(def refresh-link (hateoas/link-map "refresh" hateoas/GET "/users/refresh" {:accept jwt/media-type}))

(def teams-link (hateoas/collection-link "/teams" {:accept mt/team-collection-media-type}))

(defn authed-settings [user-id] {:links [(user-link user-id)
                                         refresh-link
                                         teams-link
                                         email-rep/auth-link]}) ; auth-link used for email verification w/ token

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
      refresh-link
      (delete-link user-id)
      teams-link])))

(defun- name-for 
  ([user] (name-for (:first-name user) (:last-name user)))
  ([first-name :guard s/blank? last-name :guard s/blank?] "")
  ([first-name last-name :guard s/blank?] first-name)
  ([first-name :guard s/blank? last-name] last-name)
  ([first-name last-name] (str first-name " " last-name)))

(schema/defn ^:always-validate jwt-props-for
  [user :- user-res/UserRep source :- schema/Keyword]
  (let [jwt-props (zipmap jwt-props (map user jwt-props))
        slack? (:slack-id user)
        slack-bots? (:slack-bots user)
        slack-props (if slack?
                      (-> jwt-props
                        (assoc :slack-id (:slack-id user))
                        (assoc :slack-token (:slack-token user)))
                      jwt-props)
        bot-props (if slack-bots?
                    (assoc slack-props :slack-bots (:slack-bots user))
                    slack-props)]
    (-> bot-props
      (assoc :name (name-for user))
      (assoc :auth-source source)
      (assoc :refresh-url (str config/auth-server-url "/users/refresh")))))

(schema/defn ^:always-validate auth-response
  "Return a JWToken for the user, or and a Location header."
  [user :- user-res/UserRep source :- schema/Keyword]
  (let [jwt-user (jwt-props-for user source)
        location (url (:user-id user))]
    (api-common/location-response location (jwt/generate jwt-user config/passphrase) jwt/media-type)))

(schema/defn ^:always-validate render-user-for-collection
  "Create a map of the user for use in a collection in the REST API"
  [team-id :- lib-schema/UniqueID user]
  {:pre [(map? user)
         (schema/validate user-res/User (dissoc user :admin?))]}
  (let [user-id (:user-id user)]
    (-> user
      (select-keys (concat representation-props [:admin? :status]))
      (user-collection-links team-id))))

(schema/defn ^:always-validate render-user :- schema/Str
  "Create a JSON representation of the user for the REST API"
  [user :- user-res/User]
  (json/generate-string
    (-> user
      (select-keys representation-props)
      (assoc :password "")
      (user-links))
    {:pretty config/pretty?}))

(defn render-user-list
  "Given a team-id and a sequence of user maps, create a JSON representation of a list of users for the REST API."
  [team-id users]
  (let [url (str (team-rep/url team-id) "/roster")]
    (json/generate-string
      {:team-id team-id
       :collection {:version hateoas/json-collection-version
                    :href url
                    :links [(hateoas/self-link url {:accept mt/user-collection-media-type})]
                    :items (map #(select-keys % (conj representation-props :status)) users)}}
      {:pretty config/pretty?})))