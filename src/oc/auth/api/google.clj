(ns oc.auth.api.google
  "Liberator API for Google oauth2"
  (:require [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes GET OPTIONS)]
            [ring.util.response :as response]            
            [clj-oauth2.client :as oauth2]
            [cheshire.core :as json]
            [oc.lib.db.pool :as pool]
            [oc.auth.config :as config]))

(def auth-req
  (oauth2/make-auth-request config/google))

(defn- google-access-token [params]
  (oauth2/get-access-token config/google params auth-req))

(defn- google-user-email [access-token]
  (let [response (oauth2/get "https://www.googleapis.com/oauth2/v1/userinfo" {:oauth access-token})]
    (get (json/parse-string (:body response)) "email")))


(defn- google-callback
  [conn params]
  (timbre/info "Google Callback" params)
  (let [token (google-access-token params)]
    (when token
      ;; Redirect them to (:uri auth-req)
      ;; When they comeback to /authentication/callback
      (google-user-email
       (google-access-token params)))))

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      (OPTIONS "/google/oauth" {params :params} (pool/with-pool [conn db-pool] (google-callback conn params)))
      (GET "/google/oauth" {params :params} (pool/with-pool [conn db-pool] (google-callback conn params))))))