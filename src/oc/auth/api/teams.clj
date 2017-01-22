(ns oc.auth.api.teams
  "Liberator API for team resources."
  (:require [if-let.core :refer (when-let*)]
            [compojure.core :as compojure :refer (defroutes OPTIONS GET PATCH POST DELETE)]
            [liberator.core :refer (defresource by-method)]
            [oc.lib.schema :as lib-schema]
            [oc.lib.rethinkdb.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.auth.config :as config]
            [oc.auth.resources.team :as team-res]
            [oc.auth.resources.user :as user-res]
            [oc.auth.representations.team :as team-rep]))

;; ----- Actions -----

(defn teams-for-user
  "Return a sequence of teams that the user, specified by their user-id, is a member of."
  [conn user-id]
  (when-let* [user (user-res/get-user conn user-id)
            teams (:teams user)]
    (team-res/get-teams conn teams [:admins :created-at :updated-at])))

;; ----- Validations -----

(defn allow-team-admins
  "Return true if the JWToken user is an admin of the specified team."
  [conn {user-id :user-id} team-id]
  (if-let [team (team-res/get-team conn team-id)]
    ((set (:admins team)) user-id)
    false))

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

(defresource team-list [conn]
  (api-common/authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get]

  :available-charsets [api-common/UTF8]
  :available-media-types [team-rep/collection-media-type]
  :handle-not-acceptable (api-common/only-accept 406 team-rep/collection-media-type)

  :handle-ok (fn [ctx] (let [user-id (-> ctx :user :user-id)]
                        (team-rep/render-team-list (teams-for-user conn user-id) user-id))))

;; A resource for operations on a particular team
(defresource team [conn team-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get :delete]

  :available-media-types [team-rep/media-type]
  :handle-not-acceptable (api-common/only-accept 406 team-rep/media-type)
  
  :allowed? (fn [ctx] (allow-team-admins conn (:user ctx) team-id))

  :exists? (fn [ctx] (if-let [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))]
                        {:existing-team team}
                        false))

  :delete! (fn [_] (team-res/delete-team! conn team-id))

  :handle-ok (fn [ctx] (team-rep/render-team (:existing-team ctx))))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      ;; team listing
      (OPTIONS "/teams" [] (pool/with-pool [conn db-pool] (team-list conn)))
      (OPTIONS "/teams/" [] (pool/with-pool [conn db-pool] (team-list conn)))
      (GET "/teams" [] (pool/with-pool [conn db-pool] (team-list conn)))
      (GET "/teams/" [] (pool/with-pool [conn db-pool] (team-list conn)))
      ;; team operations
      (OPTIONS "/teams/:team-id" [team-id] (pool/with-pool [conn db-pool] (team conn team-id)))
      (GET "/teams/:team-id" [team-id] (pool/with-pool [conn db-pool] (team conn team-id)))
      (DELETE "/teams/:team-id" [team-id] (pool/with-pool [conn db-pool] (team conn team-id))))))