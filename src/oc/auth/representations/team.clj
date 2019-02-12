(ns oc.auth.representations.team
  "Resource representations for OpenCompany teams."
  (:require [clojure.string :as s]
            [defun.core :refer (defun)]
            [cheshire.core :as json]
            [oc.lib.hateoas :as hateoas]
            [oc.auth.config :as config]
            [oc.auth.representations.media-types :as mt]
            [oc.auth.representations.slack-auth :as slack]
            [oc.auth.representations.google-auth :as google]))

(def representation-props [:team-id :logo-url :name :users :created-at :updated-at])

(defun url
  ([team-id :guard string?] (str "/teams/" team-id))
  ([team :guard map?] (url (:team-id team))))

(defn- self-link [team-id] (hateoas/self-link (url team-id) {:accept mt/team-media-type}))

(defn- partial-update-link [team-id] (hateoas/partial-update-link (url team-id) {:content-type mt/team-media-type
                                                                 :accept mt/team-media-type}))

(defn- delete-link [team-id] (hateoas/delete-link (url team-id) {:ref mt/team-media-type}))

(defn- add-email-domain-link [team-id]
  (hateoas/add-link hateoas/POST (str (url team-id) "/email-domains/") {:content-type mt/email-domain-media-type}))

(defn- remove-email-domain-link [team-id domain]
  (hateoas/remove-link (s/join "/" [(url team-id) "email-domains" domain]) {} {:ref mt/email-domain-media-type}))

(defn- invite-user-link [team-id]
  (hateoas/add-link hateoas/POST (str (url team-id) "/users/") {:content-type mt/invite-media-type
                                                                :accept mt/user-media-type}))

(defn add-slack-org-link [team-id]
  (slack/auth-link "authenticate" team-id))

(defn- remove-slack-org-link [team-id slack-org-id]
  (hateoas/remove-link (s/join "/" [(url team-id) "slack-orgs" slack-org-id]) {} {:ref mt/slack-org-media-type}))

(defn add-slack-bot-link [team-id]
  (slack/bot-link team-id))

(defn add-google-auth-link [team-id]
  (google/auth-link "authenticate" team-id))

(defn roster-link [team-id]
  (hateoas/link-map "roster" hateoas/GET (str (url team-id) "/roster") {:accept mt/user-collection-media-type}))

(defn channels-link [team-id]
  (hateoas/link-map "channels" hateoas/GET (str (url team-id) "/channels") {:accept mt/slack-channel-collection-media-type}))

(defn- admin-links
  "HATEOAS links for a team resource for a team admin."
  ([team] (admin-links team nil))
  
  ([team self-name]
  (let [team-id (:team-id team)]
    (assoc team :links [
      (if self-name 
        (hateoas/link-map self-name hateoas/GET (url team-id) {:accept mt/team-media-type})
        (self-link team-id))
      (partial-update-link team-id)
      (invite-user-link team-id)
      (add-email-domain-link team-id)
      (add-slack-org-link team-id)
      (add-slack-bot-link team-id)
      (add-google-auth-link team-id)
      (delete-link team-id)
      (roster-link team-id)
      (channels-link team-id)]))))

(defn- member-links
  "HATEOAS links for a team resource for a regular team member."
  [{team-id :team-id :as team}]
  (assoc team :links [(roster-link team-id)
                      (invite-user-link team-id)
                      (channels-link team-id)]))

(defn- email-domain
  "Item entry for an email domain for the team."
  [team-id domain]
  {:domain domain
   :links [(remove-email-domain-link team-id domain)]})

(defn- slack-org
  "Item entry for a Slack org for the team."
  [team-id {slack-org-id :slack-org-id :as slack-org}]
  (let [remove-link (remove-slack-org-link team-id slack-org-id)
        links (if (:bot-token slack-org)
                [remove-link]
                [remove-link (add-slack-bot-link team-id)])]
  {:name (:name slack-org)
   :slack-org-id slack-org-id
   :logo-url (:logo-url slack-org)
   :slack-domain (:slack-domain slack-org)
   :links links}))

(defn render-team
  "Given a team map, create a JSON representation of the team for the REST API."
  [team]
  (let [team-id (:team-id team)]
    (json/generate-string
      (-> team
        (select-keys representation-props)
        (assoc :email-domains (map #(email-domain team-id %) (:email-domains team)))
        (assoc :slack-orgs (map #(slack-org team-id %) (:slack-orgs team)))
        (admin-links))
      {:pretty config/pretty?})))

(defn render-team-list
  "
  Given a user and a sequence of team maps, create a JSON representation of a list of teams for the REST API.

  If the user is an admin for the team, the team representation contains links.
  "
  [teams user-id]
  (json/generate-string
    {:collection {:version hateoas/json-collection-version
                  :href "/teams"
                  :links [(hateoas/self-link "/teams" {:accept mt/team-collection-media-type})]
                  :items (->> teams
                            (map #(if ((set (:admins %)) user-id) 
                                    (admin-links % "item")
                                    (member-links %)))
                            (map #(dissoc % :admins)))}}
    {:pretty config/pretty?}))