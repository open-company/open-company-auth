(ns oc.auth.email
  (:require [clojure.string :as s]
            [oc.auth.config :as config]))

(def auth-settings {:auth-url (s/join "/" [config/auth-server-url "email-auth"])
                    :refresh-url (s/join "/" [config/auth-server-url "email" "refresh-token"])})
