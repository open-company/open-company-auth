(ns oc.auth.lib.google
  "OAuth2 convenience functions for google."
  (:require [taoensso.timbre :as timbre]
            [clojure.walk :refer (keywordize-keys)]
            [cheshire.core :as json]
            [clj-oauth2.client :as oauth2]
            [oc.auth.config :as config]))

(def auth-req
  (oauth2/make-auth-request config/google))

(defn access-token [params]
  (oauth2/get-access-token config/google (keywordize-keys params) auth-req))

(defn user-info [access-token]
  (let [response (oauth2/get "https://www.googleapis.com/oauth2/v1/userinfo"
                             {:oauth2 access-token})]
    (keywordize-keys (json/parse-string (:body response)))))


(defn refresh-token
  "Check if token has expired. If expired send false, otherwise return the token. "
  [conn user-info]
  (let [expires-in (get-in user-info [:google-users
                                      :token
                                      :params
                                      :expires_in])]
    (timbre/info expires-in)
    (if (pos? expires-in)
      user-info
      false)))
