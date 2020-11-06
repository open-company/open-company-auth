(ns oc.auth.representations.slack-org
  "Resource representations for Slack orgs."
  (:require [cheshire.core :as json]
            [oc.lib.hateoas :as hateoas]
            [oc.auth.config :as config]
            [oc.auth.representations.media-types :as mt]
            [oc.auth.representations.team :as team-rep]))

(def slack-org-representation-props [:name :slack-org-id])
(def slack-channel-representation-props [:name :id])

(defn- channel-for [channel]
  (select-keys channel slack-channel-representation-props))

(defn- slack-org-for-collection [slack-org]
  (assoc (select-keys slack-org slack-org-representation-props) :channels (mapv channel-for (:channels slack-org))))

(defn render-channel-list
  "Given a team-id and a sequence of channel maps, create a JSON representation of a list of channels for the REST API."
  [team-id slack-orgs]
  (println "render-channel-list for" team-id "and slack orgs:" slack-orgs)
  (let [url (str (team-rep/url team-id) "/channels")
        _ (println "   url" url)
        self-link (hateoas/self-link url {:accept mt/slack-channel-collection-media-type})
        _ (println "   self-link" self-link)
        self-links [self-link]
        _ (println "   self-links" self-links)
        orgs-collection (map slack-org-for-collection slack-orgs)
        _ (println "   orgs-collection" orgs-collection)]
    (json/generate-string
      {:team-id team-id
       :collection {:version hateoas/json-collection-version
                    :href url
                    :links self-links
                    :items orgs-collection}}
      {:pretty config/pretty?})))