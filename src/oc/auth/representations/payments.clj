(ns oc.auth.representations.payments
  (:require [cheshire.core :as json]
            [defun.core :refer (defun)]
            [oc.lib.hateoas :as hateoas]
            [oc.auth.representations.media-types :as mt]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Customer

(def customer-representation-props
  [:id :email :full-name :subscription :available-plans])

(defun customer-url
  ([team-id :guard string?] (str "/teams/" team-id "/customer"))
  ([team :guard map?]       (str "/teams/" (:team-id team) "/customer")))

(defun checkout-session-url
  ([team-id :guard string?] (str (customer-url team-id) "/checkout-session"))
  ([team :guard map?]       (str (customer-url team) "/checkout-session")))

(defn- self-link
  [team-id]
  (hateoas/self-link (customer-url team-id) {:content-type mt/payment-customer-media-type}))

(defn- create-subscription-link
  [team-id]
  (hateoas/update-link (customer-url team-id) {:content-type mt/payment-customer-media-type}))

(defn- change-plan-link
  [team-id]
  (hateoas/partial-update-link (customer-url team-id) {:content-type mt/payment-customer-media-type}))

(defn- cancel-subscription-link
  [team-id]
  (hateoas/delete-link (customer-url team-id) {:content-type mt/payment-customer-media-type}))

(defn- create-checkout-session-link
  [team-id]
  (hateoas/link-map "checkout" hateoas/POST (checkout-session-url team-id) {:content-type mt/payment-checkout-session-media-type}))

(defn render-customer
  [team-id customer]
  (-> customer
      (select-keys customer-representation-props)
      (assoc :links [(self-link team-id)
                     (create-subscription-link team-id)
                     (change-plan-link team-id)
                     (cancel-subscription-link team-id)
                     (create-checkout-session-link team-id)])
      json/generate-string))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Checkout Session

(def checkout-session-representation-props
  [:checkout-session-id])

(defn render-checkout-session
  [team-id session]
  (-> session
      (select-keys checkout-session-representation-props)
      json/generate-string))
