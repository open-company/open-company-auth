(ns oc.auth.representations.payments
  (:require [cheshire.core :as json]
            [defun.core :refer (defun)]
            [oc.lib.hateoas :as hateoas]
            [oc.auth.representations.media-types :as mt]))

(def customer-representation-props
  [:id :email :full-name :subscription])

(defun customer-url
  ([team-id :guard string?] (str "/teams/" team-id "/customer"))
  ([team :guard map?] (str "/teams/" (:team-id team) "/customer")))

(defn- self-link
  [team-id]
  (hateoas/self-link (customer-url team-id) {:content-type mt/payment-customer-media-type}))

(defn- create-customer-link
  [team-id]
  (hateoas/create-link (customer-url team-id) {:content-type mt/payment-customer-media-type}))

(defn- create-subscription-link
  [team-id]
  (hateoas/update-link (customer-url team-id) {:content-type mt/payment-customer-media-type}))

(defn- change-plan-link
  [team-id]
  (hateoas/partial-update-link (customer-url team-id) {:content-type mt/payment-customer-media-type}))

(defn- cancel-subscription-link
  [team-id]
  (hateoas/delete-link (customer-url team-id) {:content-type mt/payment-customer-media-type}))

(defn render-customer
  [team-id customer]
  (-> customer
      (select-keys customer-representation-props)
      (assoc :links [(self-link team-id)
                     (create-customer-link team-id)
                     (create-subscription-link team-id)
                     (change-plan-link team-id)
                     (cancel-subscription-link team-id)])
      json/generate-string))
