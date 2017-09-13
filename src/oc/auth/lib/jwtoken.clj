(ns oc.auth.lib.jwtoken
  (:require [oc.lib.jwt :as jwt]
            [oc.lib.db.common :as db-common]
            [oc.auth.config :as config]
            [oc.auth.resources.user :as user-res]))

(defn generate
  "Generate a JWToken for the user and note the token generation in the last-token timestamp."
  [conn jwt-user]
  {:pre [(db-common/conn? conn)
         (map? jwt-user)]}
  (if-let [jwtoken (jwt/generate jwt-user config/passphrase)]
    (do
      (user-res/update-user! conn (:user-id jwt-user) {:last-token-at (db-common/current-timestamp)})
      jwtoken)
    false))