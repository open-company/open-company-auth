(ns oc.auth.representations.team
  "Resource representations for OpenCompany teams."
  (:require [defun.core :refer (defun)]
            [cheshire.core :as json]
            [oc.lib.hateoas :as hateoas]))

(def media-type "application/vnd.open-company.team.v1+json")
(def collection-media-type "application/vnd.collection+vnd.open-company.team+json;version=1")

(def representation-props [:team-id :name :admins :email-domains :slack-orgs :created-at :updated-at])

(defun url
  ([team-id :guard string?] (str "/teams/" team-id))
  ([team :guard map?] (url (:team-id team))))

(defn- self-link [team-id] (hateoas/self-link (url team-id) media-type))

(defn- delete-link [team-id] (hateoas/delete-link (url team-id)))

(defn- team-links [team]
  (let [team-id (:team-id team)]
    (assoc team :links [
      (self-link team-id)
      (delete-link team-id)])))

(defn render-team
  "Create a JSON representation of the team for the REST API"
  [team]
  (let [team-id (:team-id team)]
    (json/generate-string
      (-> team
        (select-keys representation-props)
        (team-links))
      {:pretty true})))