(ns oc.auth.api.teams
  "Liberator API for team resources."
  (:require [compojure.core :as compojure :refer (defroutes OPTIONS GET PATCH POST DELETE)]
            [liberator.core :refer (defresource by-method)]
            [oc.lib.schema :as lib-schema]
            [oc.lib.rethinkdb.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.auth.config :as config]
            [oc.auth.resources.team :as team-res]
            [oc.auth.representations.team :as team-rep]))

;; ----- Validations -----

(defn allow-team-admins [conn {user :user} team-id]
  true
  )

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

(defresource team-list [conn]
  (api-common/authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get]

  :available-charsets [api-common/UTF8]
  :available-media-types [team-rep/collection-media-type]
  :handle-not-acceptable (api-common/only-accept 406 team-rep/collection-media-type)

  )

;; A resource for operations on a particular team
(defresource team [conn team-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get :delete]

  :available-media-types [team-rep/media-type]
  :handle-not-acceptable (api-common/only-accept 406 team-rep/media-type)
  
  :allowed? (fn [ctx] (allow-team-admins conn ctx team-id))

  :exists? (fn [ctx] (if-let [team (and (lib-schema/unique-id? team-id) (team-res/get-team conn team-id))]
                        {:existing-team team}
                        false))

  :delete! (fn [_] (team-res/delete-team! conn team-id))

  :handle-ok (fn [ctx] (team-rep/render-team (:existing-team ctx)))

  )

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