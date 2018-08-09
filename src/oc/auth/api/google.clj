(ns oc.auth.api.google
  "Liberator API for Google oauth2"
  (:require [taoensso.timbre :as timbre]
            [clojure.walk :refer (keywordize-keys)]
            [compojure.core :as compojure :refer (defroutes GET OPTIONS)]
            [ring.util.response :as response]
            [clj-oauth2.client :as oauth2]
            [cheshire.core :as json]
            [oc.lib.db.pool :as pool]
            [oc.auth.lib.jwtoken :as jwtoken]
            [oc.auth.resources.user :as user-res]
            [oc.auth.config :as config]
            [oc.auth.representations.user :as user-rep]))

(defn- redirect-to-web-ui
  "Send them back to a UI page with an access description ('google' or 'failed') and a JWToken."
  ([redirect access]
    (redirect-to-web-ui redirect access nil :not-a-new-user)) ; nil = no jwtoken

  ([redirect access jwtoken last-token-at]
  (let [page (or redirect "/login")
        jwt-param (if jwtoken (str "&jwt=" jwtoken) "")
        param-concat (if (.contains page "?") "&" "?")
        url (str config/ui-server-url page param-concat "access=" (name access) "&new=" (if last-token-at false true))]
    (timbre/info "Redirecting request to:" url)
    (response/redirect (str url jwt-param)))))

(defn- clean-user
  "Remove properties from a user that are not needed for a JWToken."
  [user]
  (dissoc user :created-at :updated-at :status))

(defn- clean-google-user
  [google-user token]
  {:first-name (:given_name google-user)
   :last-name (:family_name google-user)
   :avatar-url (:picture google-user)
   :email (:email google-user)
   :google-users {:id (:id google-user)
                  :token token}})

(defn- create-user-for
  [conn new-user]
  (timbre/info "Creating new user:" (:email new-user) (:first-name new-user) (:last-name new-user))
  (user-res/create-user! conn (assoc new-user :status :active)))

(def auth-req
  (oauth2/make-auth-request config/google))

(defn- google-access-token [params]
  (oauth2/get-access-token config/google (keywordize-keys params) auth-req))

(defn- google-user-info [access-token]
  (let [response (oauth2/get "https://www.googleapis.com/oauth2/v1/userinfo"
                             {:oauth2 access-token})]
    (keywordize-keys (json/parse-string (:body response)))))

(defn- google-callback
  [conn params]
  (timbre/info "Google Callback")
  (let [token (google-access-token params)]
    (when token
      (let [user-info (google-user-info token)
            email (:email user-info)
            existing-user (user-res/get-user-by-email conn email)
            new-user (when-not existing-user
                       (user-res/->user (clean-google-user user-info token)))
            user (or existing-user (create-user-for conn new-user))
            new-google-user {:id (:id user-info)
                             :token token}
            updated-google-user (do
                                  (when (:verified_email user-info)
                                    (user-res/activate! conn (:user-id user)))
                                  (user-res/update-user! conn
                                    (:user-id user)
                                    (update-in user [:google-users] merge new-google-user)))
            ;; Create a JWToken from the user for the response
            jwt-user (user-rep/jwt-props-for (-> updated-google-user
                                               (clean-user)
                                               (assoc :admin (user-res/admin-of conn (:user-id user)))
                                               (assoc :google-id (:id user-info))
                                               (assoc :google-domain (:hd user-info))
                                               (assoc :google-token token)) :google)]
        (timbre/debug jwt-user)
        (redirect-to-web-ui (:success-uri config/google)
                            :google
                            (jwtoken/generate conn jwt-user)
                            (:last-token-at user))))))

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
     (GET "/google/oauth" {params :params}
       (pool/with-pool [conn db-pool]
         (response/redirect (:uri auth-req))))
     (GET "/google/oauth/callback" {params :params}
       (pool/with-pool [conn db-pool] (google-callback conn params))))))