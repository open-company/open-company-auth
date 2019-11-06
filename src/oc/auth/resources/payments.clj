(ns oc.auth.resources.payments
  (:require [oc.auth.lib.stripe :as stripe]
            [oc.auth.resources.team :as team]
            [schema.core :as schema]
            [oc.lib.db.common :as db-common]))

;; ----- Schema -----

(def Subscription
  {:quantity schema/Int})

(def Customer
  {:id schema/Str})

(def CustomerContact
  {:email     schema/Str
   :full-name schema/Str})

;; ----- Payment CRUD -----

(defn- get-customer-id
  [conn team-id]
  (:stripe-customer-id (team/get-team conn team-id)))

(schema/defn ^:always-validate create-customer!
  [conn
   team-id :- (:team-id team/Team)
   contact :- CustomerContact]
  {:pre [(db-common/conn? conn)]}
  (let [options     {"email"    (:email contact)
                     "name"     (:full-name contact)
                     "metadata" {"carrotTeamId" team-id}}
        customer    (stripe/create-customer! options)
        customer-id (:id customer)]
    (team/update-team! conn team-id {:stripe-customer-id customer-id})
    customer))

(schema/defn ^:always-validate get-customer
  [conn
   team-id :- (:team-id team/Team)]
  {:pre [(db-common/conn? conn)]}
  (when-let [customer-id (get-customer-id conn team-id)]
    (stripe/get-customer customer-id)))

(schema/defn ^:always-validate start-new-trial!
  [conn
   team-id :- (:team-id team/Team)
   plan-id]
  {:pre [(db-common/conn? conn)]}
  (let [customer-id (get-customer-id conn team-id)]
    (stripe/start-trial! customer-id plan-id)))

(schema/defn ^:always-validate schedule-new-subscription!
  [conn
   team-id :- (:team-id team/Team)
   new-plan-id]
  {:pre [(db-common/conn? conn)]}
  (let [customer-id (get-customer-id conn team-id)]
    (stripe/schedule-new-subscription! customer-id new-plan-id)))

(schema/defn ^:always-validate cancel-subscription!
  [conn
   team-id :- (:team-id team/Team)]
  {:pre [(db-common/conn? conn)]}
  (let [customer-id (get-customer-id conn team-id)
        customer    (stripe/get-customer customer-id)]
    (stripe/cancel-all-subscriptions! customer)))

(schema/defn ^:always-validate report-latest-team-size!
  [customer-id :- (:id Customer)
   seat-count :- (:quantity Subscription)]
  (let [customer (stripe/get-customer customer-id)]
   (stripe/update-all-subscription-quantities! customer seat-count)))

(schema/defn ^:always-validate create-checkout-session!
  [conn
   team-id :- (:team-id team/Team)
   {:as callback-opts :keys [success-url cancel-url]}]
  (let [customer-id (get-customer-id conn team-id)]
    (stripe/create-checkout-session! customer-id success-url cancel-url)))

(schema/defn ^:always-validate finish-checkout-session!
  [conn session-id]
  (stripe/assoc-session-result-with-customer! session-id))

(schema/defn ^:always-validate end-trial-period!
  [conn
   customer-id :- (:id Customer)]
  (stripe/end-trial-period! customer-id))
