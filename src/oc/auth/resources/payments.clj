(ns oc.auth.resources.payments
  (:require [oc.auth.lib.stripe :as stripe]
            [oc.auth.resources.team :as team]
            [schema.core :as schema]
            [oc.lib.db.common :as db-common]))

;; ----- Schema -----

(def PaymentContact
  {:email     schema/Str
   :full-name schema/Str})

;; ----- Payment CRUD -----

(defn- get-customer-id
  [conn team-id]
  (-> (team/get-team conn team-id)
      :stripe-customer-id))

(schema/defn ^:always-validate create-customer!
  [conn team-id :- (:team-id team/Team) contact :- PaymentContact]
  {:pre [(db-common/conn? conn)]}
  (let [stripe-meta {"carrotTeamId" team-id}
        customer    (stripe/create-stripe-customer! contact stripe-meta)
        customer-id (:id customer)]
    (team/update-team! conn team-id {:stripe-customer-id customer-id})))

(schema/defn ^:always-validate get-customer
  [conn team-id :- (:team-id team/Team)]
  {:pre [(db-common/conn? conn)]}
  (let [customer-id (get-customer-id conn team-id)]
    (stripe/customer-info customer-id)))

(schema/defn ^:always-validate change-plan!
  [conn team-id :- (:team-id team/Team) new-plan-id]
  {:pre [(db-common/conn? conn)]}
  (let [customer-id (get-customer-id conn team-id)]
    (stripe/change-plan! customer-id new-plan-id)))

(schema/defn ^:always-validate cancel-subscription!
  [conn team-id :- (:team-id team/Team)]
  {:pre [(db-common/conn? conn)]}
  (let [customer-id (get-customer-id conn team-id)]
    (stripe/cancel-subscription! customer-id)))

;; TODO: figure out how to count seats...
;; (schema/defn ^:always-validate report-latest-team-size!
;;   [conn team-id :- (:team-id team/Team)]
;;   {:pre [(db-common/conn? conn)]}
;;   (let [team (team/get-team conn team-id)]
;;     (stripe/report-seats! customer-id)))
