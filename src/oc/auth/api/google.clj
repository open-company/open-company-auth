(ns oc.auth.api.google
  "Liberator API for Google oauth2"
  (:require [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (GET)]
            [ring.util.response :as response]
            [oc.lib.db.pool :as pool]
            [oc.lib.jwt :as jwt]
            [oc.lib.sentry.core :as sentry]
            [oc.auth.lib.jwtoken :as jwtoken]
            [oc.lib.oauth :as oauth]
            [oc.auth.lib.google :as google]
            [oc.auth.resources.user :as user-res]
            [oc.auth.config :as config]
            [oc.auth.async.notification :as notification]
            [oc.auth.representations.user :as user-rep]))

(defn- redirect-to-web-ui
  "Send them back to a UI page with an access description ('google' or 'failed') and a JWToken."
  ([redirect-origin redirect access]
   (redirect-to-web-ui redirect-origin redirect access nil :not-a-new-user)) ; nil = no jwtoken

  ([redirect-origin redirect access jwtoken last-token-at]
   (let [page (or redirect "/login")
         jwt-param (if jwtoken (str "&jwt=" jwtoken "&") "&")
         param-concat (if (.contains page "?") "&" "?")
         url (str (or redirect-origin config/ui-server-url) page param-concat "access=" (name access) "&new=" (if last-token-at false true))]
     (timbre/info "Redirecting request to:" url)
     (response/redirect (str url jwt-param)))))

(defn- clean-user
  "Remove properties from a user that are not needed for a JWToken."
  [user]
  (dissoc user :created-at :updated-at :activated-at :status))

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
  (let [user (user-res/create-user! conn (assoc new-user :status :active))
        trigger (notification/->trigger user)]
    (notification/send-trigger! trigger)
    user))

(defn- parse-state-from-params
  [params]
  (some-> params (get "state") oauth/decode-state-string))

(defn- google-callback
  [conn params]
  (try
    (timbre/info "Google Callback")
    (timbre/info params)
    (let [{:keys [redirect-origin]} (parse-state-from-params params)
          token (google/access-token params)]
      (when token
        (let [user-info (google/user-info token)
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
                                                   (assoc :premium-teams (user-res/premium-teams conn (:user-id user)))
                                                ;; include slack bot info
                                                   (assoc :slack-bots
                                                          (jwt/bots-for conn user))
                                                   (assoc :google-id (:id user-info))
                                                   (assoc :google-domain (:hd user-info))
                                                   (assoc :google-token token))
                                               :google)]
          (timbre/debug jwt-user)
          (redirect-to-web-ui redirect-origin
                              (:success-uri config/google)
                              :google
                              (jwtoken/generate conn jwt-user)
                              (:last-token-at user)))))
    (catch Exception e
      (timbre/warn e)
      (sentry/capture e)
      (redirect-to-web-ui config/ui-server-url (:success-uri config/google) :failed))))

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
     (GET "/google/oauth/callback" {params :params}
       (pool/with-pool [conn db-pool] (google-callback conn params))))))