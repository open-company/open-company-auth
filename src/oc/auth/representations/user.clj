(ns oc.auth.representations.user
  "Resource representations for OpenCompany users."
  (:require [clojure.string :as s]
            [defun.core :refer (defun)]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [cheshire.core :as json]
            [oc.lib.hateoas :as hateoas]
            [oc.lib.jwt :as jwt]
            [oc.lib.api.common :as api-common]
            [oc.auth.lib.jwtoken :as jwtoken]
            [oc.auth.config :as config]
            [oc.auth.representations.media-types :as mt]
            [oc.auth.representations.team :as team-rep]
            [oc.auth.representations.email-auth :as email-rep]
            [oc.auth.representations.slack-auth :as slack-auth]
            [oc.auth.representations.google-auth :as google-auth]
            [oc.auth.resources.user :as user-res]))

(def slack-props [:name :slack-id :slack-org-id :slack-display-name :slack-bots])
(def oc-props [:user-id :first-name :last-name :email :avatar-url
               :timezone :created-at :slack-users :status :title :blurb :location :profiles])
(def representation-props (concat slack-props oc-props))
(def self-user-props [:digest-medium :notification-medium :reminder-medium :updated-at :qsg-checklist :expo-push-tokens :digest-delivery :digest-last-at])
(def team-user-props [:admin?])
(def self-user-representation-props (concat representation-props self-user-props))
(def team-user-representation-props (concat representation-props team-user-props))
(def jwt-props [:user-id :first-name :last-name :name :email :avatar-url :teams :admin :digest-delivery :digest-last-at])

(defun url
  ([user-id :guard string?] (str "/users/" user-id))
  ([user :guard map?] (url (:user-id user))))

(defn- admin-url [team-id user-id]
  (s/join "/" ["/teams" team-id "admins" user-id]))

(defn- team-member-url [team-id user-id]
  (s/join "/" ["/teams" team-id "users" user-id]))

(defn- self-link [user-id] (hateoas/self-link (url user-id) {:accept mt/user-media-type}))

(defn- item-link [user-id] (hateoas/item-link (url user-id) {:accept mt/user-media-type}))

(defn- user-link [user-id] (hateoas/link-map "user" hateoas/GET (url user-id) {:accept mt/user-media-type}))

(defn- partial-update-link [user-id] (hateoas/partial-update-link (url user-id) {:accept mt/user-media-type
                                                                                 :content-type mt/user-media-type}))

(defn- delete-link [user-id] (hateoas/delete-link (url user-id) {:ref mt/user-media-type}))

(defn- remove-link [team-id user-id] (hateoas/remove-link (team-member-url team-id user-id) {} {:ref mt/user-media-type}))

(defn- resend-verification-email-link [user-id]
  (hateoas/link-map "resend-verification" hateoas/POST (str (url user-id) "/verify") {}))

(def refresh-link (hateoas/link-map "refresh" hateoas/GET "/users/refresh" {:accept jwt/media-type}))

(def teams-link (hateoas/collection-link "/teams" {:accept mt/team-collection-media-type}))

(def add-expo-push-token-link
  (hateoas/link-map "add-expo-push-token" hateoas/POST "/users/expo-push-token" {:accept mt/expo-push-token-media-type
                                                                                 :content-type mt/expo-push-token-media-type}))

(defn authed-settings
  "Status can be an array of:
    :password-required user has an empty password
    :name-required user has empty first-name and empty last-name
    nothing if the user is good to go"
  [user]
  (let [with-password-required (if (and (s/blank? (:password-hash user))
                                        (= (:auth-source user) "email"))
                                 [:password-required]
                                 [])
        with-name-required (if (and (s/blank? (:first-name user))
                                   (s/blank? (:last-name user)))
                                 (conj with-password-required :name-required)
                                 with-password-required)
        auth-links (conj
                     (concat
                      slack-auth/auth-settings
                      google-auth/auth-settings)
                     email-rep/auth-link)  ; auth-link used for email verification w/ token
        id-token-links [teams-link]
        jwt-links (conj
                   [(user-link (:user-id user))]
                   refresh-link
                   teams-link)]
    {:links (concat
             auth-links
             (if (:id-token user)
               id-token-links
               jwt-links))
     :status with-name-required}))

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
      (admin-action-link team-id user-id (:admin? user))
      (remove-link team-id user-id)])))

(defn- user-links
  "HATEOAS links for a user resource"
  [user]
  (let [user-id (:user-id user)]
    (assoc user :links [
      (self-link user-id)
      (partial-update-link user-id)
      refresh-link
      (delete-link user-id)
      teams-link
      (resend-verification-email-link user-id)
      add-expo-push-token-link])))

(defn- clean-user-tokens [user]
  (as-> user u
   (if (:slack-users u)
     (update u :slack-users (fn [slack-users]
                              (apply merge
                               (map (fn [[slack-team-id slack-values]]
                                      (hash-map slack-team-id (dissoc slack-values :token))) slack-users))))
     u)
   (if (:google-users u)
     (update u :google-users (fn [google-users]
                              (apply merge
                               (map (fn [[google-team-id google-values]]
                                      (hash-map google-team-id (dissoc google-values :token))) google-users))))
     u)))

(schema/defn ^:always-validate jwt-props-for
  [user :- user-res/UserRep source :- schema/Keyword]
  (let [jwt-props (zipmap jwt-props (map user jwt-props))
        slack? (:slack-id user)
        slack-bots? (:slack-bots user)
        slack-users? (:slack-users user)
        slack-props (if slack?
                      (-> jwt-props
                        (assoc :slack-id (:slack-id user))
                        (assoc :slack-token (:slack-token user))
                        ; "-" for backward compatability w/ old JWTokens
                        (assoc :slack-display-name (or (:slack-display-name user) "-")))
                      jwt-props)
        bot-props (if slack-bots?
                    (assoc slack-props :slack-bots (:slack-bots user))
                    slack-props)
        slack-users-props (if slack-users?
                            (assoc bot-props :slack-users (:slack-users user))
                            bot-props)
        google-users-props (if (:google-id user)
                             (-> slack-users-props
                               (assoc :google-id (:google-id user))
                               (assoc :google-domain (:google-domain user))
                               (assoc :google-token (:google-token user)))
                            slack-users-props)]
    (-> google-users-props
      (assoc :name (jwt/name-for user))
      (assoc :auth-source source)
      (assoc :refresh-url (str config/auth-server-url "/users/refresh")))))

(schema/defn ^:always-validate auth-response
  "Return a JWToken for the user, or and a Location header."
  [conn user :- user-res/UserRep source :- schema/Keyword]
  (let [jwt-user (jwt-props-for user source)
        location (url (:user-id user))]
    (api-common/location-response location (jwtoken/generate conn jwt-user) jwt/media-type)))

(schema/defn ^:always-validate render-user-for-collection
  "Create a map of the user for use in a collection in the REST API"
  [team-id :- lib-schema/UniqueID user]
  {:pre [(map? user)]}
  (-> user
    (clean-user-tokens)
    (select-keys team-user-representation-props)
    (user-collection-links team-id)))

(schema/defn ^:always-validate render-user :- schema/Str
  "Create a JSON representation of the user for the REST API"
  [user :- user-res/User]
  (json/generate-string
    (-> user
      (select-keys self-user-representation-props)
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
                    :items (map #(-> % (clean-user-tokens) (select-keys team-user-representation-props)) users)}}
      {:pretty config/pretty?})))

(defn render-active-users-list
  "Given a team-id and a sequence of active user maps, create a JSON representation of a list of users for the REST API."
  [team-id users]
  (let [url (str (team-rep/url team-id) "/active-users")]
    (json/generate-string
      {:team-id team-id
       :collection {:version hateoas/json-collection-version
                    :href url
                    :links [(hateoas/self-link url {:accept mt/user-collection-media-type})]
                    :items (map #(select-keys % team-user-representation-props) users)}}
      {:pretty config/pretty?})))
