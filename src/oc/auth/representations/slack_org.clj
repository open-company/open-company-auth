(ns oc.auth.representations.slack-org
  "Resource representations for Slack orgs."
  (:require [clojure.string :as s]
            [cheshire.core :as json]
            [oc.lib.hateoas :as hateoas]
            [oc.auth.representations.media-types :as mt]
            [oc.auth.representations.team :as team-rep]))

(def slack-org-representation-props [:name :slack-org-id])
(def slack-channel-representation-props [:name :id])

(defn- channel-for [channel]
  (select-keys channel slack-channel-representation-props))

(defn- slack-org-for-collection [slack-org]
  (assoc (select-keys slack-org slack-org-representation-props) :channels (map channel-for (:channels slack-org))))

(defn render-channel-list
  "Given a team-id and a sequence of channel maps, create a JSON representation of a list of channels for the REST API."
  [team-id slack-orgs]
  (let [url (str (team-rep/url team-id) "/channels")]
    (json/generate-string
      {:team-id team-id
       :collection {:version hateoas/json-collection-version
                    :href url
                    :links [(hateoas/self-link url {:accept mt/slack-channel-collection-media-type})]
                    :items (map slack-org-for-collection slack-orgs)}}
      {:pretty true})))