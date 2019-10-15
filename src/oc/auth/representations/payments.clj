(ns oc.auth.representations.payments
  (:require [cheshire.core :as json]))

(def customer-representation-props
  [:id :email :full-name :subscription])

(def subscription-representation-props
  [:id
   :current-period-start
   :current-period-end
   :trial-start
   :trial-end
   :status
   :current-plan
   :available-plans
   :usage
   :item
   ])

(defn render-customer
  [customer]
  (-> customer
      (select-keys customer-representation-props)
      json/generate-string))


(defn render-subscription
  [sub]
  (-> sub
      (select-keys subscription-representation-props)
      json/generate-string))
