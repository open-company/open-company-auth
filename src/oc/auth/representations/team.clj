(ns oc.auth.representations.team
  "Resource representations for OpenCompany teams."
  (:require [clojure.string :as s]
            [defun.core :refer (defun)]
            [cheshire.core :as json]
            [oc.lib.hateoas :as hateoas]
            [oc.auth.representations.slack-auth :as slack]))

(def media-type "application/vnd.open-company.team.v1+json")
(def collection-media-type "application/vnd.collection+vnd.open-company.team+json;version=1")

(def admin-media-type "application/vnd.open-company.team.admin.v1")
(def email-domain-media-type "application/vnd.open-company.team.email-domain.v1")
(def slack-org-media-type "application/vnd.open-company.team.slack-org.v1")

(def representation-props [:team-id :name :users :created-at :updated-at])

(defun url
  ([team-id :guard string?] (str "/teams/" team-id))
  ([team :guard map?] (url (:team-id team))))

(defn- self-link [team-id] (hateoas/self-link (url team-id) media-type))

(defn- delete-link [team-id] (hateoas/delete-link (url team-id)))

(defn- remove-email-domain-link [team-id domain]
  (hateoas/remove-link (s/join "/" [(url team-id) "email-domains" domain]) email-domain-media-type))

(defn- add-email-domain-link [team-id]
  (hateoas/add-link hateoas/POST (str (url team-id) "/email-domains/") email-domain-media-type))

(defn add-slack-org-link [team-id]
  (slack/auth-link "authenticate" team-id))

(defn- remove-slack-org-link [team-id slack-org-id]
  (hateoas/remove-link (s/join "/" [(url team-id) "slack-orgs" slack-org-id]) slack-org-media-type))

(defn add-slack-bot-link [team-id]
  (slack/bot-link team-id))

(defn- team-links
  "HATEOAS links for a team resource"
  [team & self-name]
  (let [team-id (:team-id team)]
    (assoc team :links [
      (if self-name 
        (hateoas/link-map self-name hateoas/GET (url team-id) media-type)
        (self-link team-id))
      (add-email-domain-link team-id)
      (add-slack-org-link team-id)
      (add-slack-bot-link team-id)
      (delete-link team-id)])))

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
        (team-links))
      {:pretty true})))

(defn render-team-list
  "
  Given a user and a sequence of team maps, create a JSON representation of a list of teams for the REST API.

  If the user is an admin for the team, the team representation contains links.
  "
  [teams user-id]
  (json/generate-string
    {:collection {:version hateoas/json-collection-version
                  :href "/teams"
                  :links [(hateoas/self-link "/teams" collection-media-type)]
                  :teams (->> teams
                          (map #(if ((set (:admins %)) user-id) (team-links % :item) %))
                          (map #(dissoc % :admins)))}}
    {:pretty true}))