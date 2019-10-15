(ns oc.auth.resources.payments
  (:require [oc.auth.lib.stripe :as stripe]
            [oc.auth.resources.team :as team]
            [schema.core :as schema]
            [oc.lib.db.common :as db-common]))

;; ----- Schema -----

(def Plan
  {:id       schema/Str
   :amount   schema/Int
   :nickname schema/Str
   :currency (schema/enum "usd")
   :active   schema/Bool
   :interval (schema/enum "month" "annual")
   })

(def UsageSummary
  {:seats schema/Int})

(def SubscriptionItem
  {:id schema/Str})

(def Subscription
  {:id                   schema/Str
   :current-period-start schema/Int
   :current-period-end   schema/Int
   :trial-start          (schema/maybe schema/Int)
   :trial-end            (schema/maybe schema/Int)
   :status               (schema/enum "trialing" "active")
   :current-plan         Plan
   :available-plans      [Plan]
   :usage                UsageSummary
   :item                 SubscriptionItem
   })

(def Customer
  {:id        schema/Str
   :email     schema/Str
   :full-name schema/Str
   (schema/optional-key :subscription) (schema/maybe Subscription)
   })

(def CustomerContact
  {:email     schema/Str
   :full-name schema/Str})

;; ----- Payment CRUD -----

(defn- get-customer-id
  [conn team-id]
  (-> (team/get-team conn team-id)
      :stripe-customer-id))

(schema/defn ^:always-validate create-customer! :- Customer
  [conn team-id :- (:team-id team/Team) contact :- CustomerContact]
  {:pre [(db-common/conn? conn)]}
  (let [stripe-meta {"carrotTeamId" team-id}
        customer    (stripe/create-stripe-customer! contact stripe-meta)
        customer-id (:id customer)]
    (team/update-team! conn team-id {:stripe-customer-id customer-id})))

(schema/defn ^:always-validate get-customer :- Customer
  [conn team-id :- (:team-id team/Team)]
  {:pre [(db-common/conn? conn)]}
  (when-let [customer-id (get-customer-id conn team-id)]
    (stripe/customer-info customer-id)))

(schema/defn ^:always-validate start-plan! :- Subscription
  [conn team-id :- (:team-id team/Team) new-plan-id]
  {:pre [(db-common/conn? conn)]}
  (let [customer-id (get-customer-id conn team-id)]
    (stripe/subscribe-customer-to-plan! customer-id new-plan-id)))

(schema/defn ^:always-validate change-plan! :- SubscriptionItem
  [conn team-id :- (:team-id team/Team) new-plan-id]
  {:pre [(db-common/conn? conn)]}
  (let [customer-id (get-customer-id conn team-id)]
    (stripe/change-plan! customer-id new-plan-id)))

(schema/defn ^:always-validate cancel-subscription! :- Subscription
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
