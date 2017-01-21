(ns oc.auth.api.user
  "Liberator API for user resource."
  (:require [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes OPTIONS GET PATCH DELETE)]
            [liberator.core :refer (defresource by-method)]
            [schema.core :as schema]
            [oc.lib.rethinkdb.pool :as pool]
            [oc.lib.schema :as lib-schema]
            [oc.lib.api.common :as api-common]
            [oc.auth.config :as config]
            [oc.auth.representations.user :as user-rep]
            [oc.auth.resources.user :as user-res]))

;; ----- Actions -----

(defn- update-user [conn {data :data} user-id]
  (if-let [updated-user (user-res/update-user! conn user-id data)]
    {:updated-user updated-user}
    false))

;; ----- Validations -----

(defn- allow-user-and-team-admins [conn {user :user} user-id]
  (or (= user-id (:user-id user)) ; JWToken user-id matches URL user-id, user accessing themself
    false)) ; TODO admin of a team the user is on

(defn- processable-patch-req? [conn {data :data} user-id]
  (if-let [user (user-res/get-user conn user-id)]
    (try
      (schema/validate user-res/User (merge user data))
      true
      (catch clojure.lang.ExceptionInfo e
        (timbre/error e "Validation failure of user PATCH request.")
        false))
    true)) ; No user for this user-id, so this will 404 after :exists? decision

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

;; A resource for a particular user
(defresource user [conn user-id]
  (api-common/open-company-authenticated-resource config/passphrase)

  :allowed-methods [:options :get :patch :delete]

  :available-media-types [user-rep/media-type]
  :known-content-type? (by-method {
                          :options true
                          :get true
                          :patch (fn [ctx] (api-common/known-content-type? ctx user-rep/media-type))
                          :delete true})

  :allowed? (fn [ctx] (allow-user-and-team-admins conn ctx user-id))

  :processable? (by-method {
    :get true
    :options true
    :patch (fn [ctx] (processable-patch-req? conn ctx user-id))
    :delete true})

  :exists? (fn [ctx] (if-let [user (and (lib-schema/unique-id? user-id) (user-res/get-user conn user-id))]
                        {:existing-user user}
                        false))

  :patch! (fn [ctx] (update-user conn ctx user-id))
  :delete! (fn [_] (user-res/delete-user! conn user-id))

  :handle-ok (by-method {
    :get (fn [ctx] (user-rep/render-user (:existing-user ctx)))
    :patch (fn [ctx] (user-rep/render-user (:updated-user ctx)))}))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      (OPTIONS "/users/:user-id" [user-id] (pool/with-pool [conn db-pool] (user conn user-id)))
      (GET "/users/:user-id" [user-id] (pool/with-pool [conn db-pool] (user conn user-id)))
      (PATCH "/users/:user-id" [user-id] (pool/with-pool [conn db-pool] (user conn user-id)))
      (DELETE "/users/:user-id" [user-id] (pool/with-pool [conn db-pool] (user conn user-id))))))