(ns oc.auth.api.user
  "Liberator API for user resource."
  (:require [compojure.core :as compojure :refer (defroutes OPTIONS GET PATCH DELETE)]
            [liberator.core :refer (defresource by-method)]
            [oc.lib.rethinkdb.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.auth.config :as config]
            [oc.auth.representations.user :as user-rep]
            [oc.auth.resources.user :as user-res]))

;; ----- Actions -----


;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

(defresource user [conn user-id]
  (api-common/open-company-authenticated-resource config/passphrase)

  :available-media-types [user-rep/media-type]
  :allowed-methods [:options :get :patch :delete]

)

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      (OPTIONS "/users/:user-id" [user-id] (pool/with-pool [conn db-pool] (user conn user-id)))
      (GET "/users/:user-id" [user-id] (pool/with-pool [conn db-pool] (user conn user-id)))
      (PATCH "/users/:user-id" [user-id] (pool/with-pool [conn db-pool] (user conn user-id)))
      (DELETE "/users/:user-id" [user-id] (pool/with-pool [conn db-pool] (user conn user-id))))))