(ns oc.auth.representations.slack-org
  "Resource representations for Slack orgs."
  (:require [cheshire.core :as json]
            [clojure.string :as s]
            [oc.lib.user :as user-lib]
            [oc.lib.hateoas :as hateoas]
            [oc.auth.config :as config]
            [oc.auth.representations.media-types :as mt]
            [oc.auth.representations.team :as team-rep]))

(def slack-org-representation-props [:name :slack-org-id])
(def slack-channel-representation-props [:name :id :channel-type :needs-join])

(defn- channel-for [channel slack-users]
  (-> channel
      (select-keys slack-channel-representation-props)
      (update :name #(or %
                         (some-> slack-users
                                 (get (:user channel)) ;; retrieve the user from the map
                                 user-lib/name-for)))
      (assoc :channel-type (if (:is_im channel) "user" "channel"))
      (assoc :needs-join (and (:is_channel channel)
                              (not (:is_member channel))))))

(defn slack-org-for-collection
  ([slack-org] (slack-org-for-collection slack-org {}))
  ([slack-org slack-users]
   (let [channels (->> slack-org
                       :channels
                       (map #(channel-for % slack-users))
                       (filter (comp not s/blank? :name)))]
     (-> slack-org
         (select-keys slack-org-representation-props)
         (assoc :channels channels)))))

(defn render-channel-list
  "Given a team-id and a sequence of channel maps, create a JSON representation of a list of channels for the REST API."
  ([team-id slack-orgs] (render-channel-list team-id slack-orgs []))
  ([team-id slack-orgs slack-users]
   (let [url (str (team-rep/url team-id) "/channels")
         users-map (zipmap (mapv :slack-id slack-users) slack-users)
         slack-orgs-with-channels (map #(slack-org-for-collection % users-map) slack-orgs)]
     (json/generate-string
      {:team-id team-id
       :collection {:version hateoas/json-collection-version
                    :href url
                    :links [(hateoas/self-link url {:accept mt/slack-channel-collection-media-type})]
                    :items slack-orgs-with-channels}}
      {:pretty config/pretty?}))))