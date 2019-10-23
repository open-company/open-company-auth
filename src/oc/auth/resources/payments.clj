(ns oc.auth.resources.payments
  (:require [oc.auth.lib.stripe :as stripe]
            [oc.auth.resources.team :as team]
            [schema.core :as schema]
            [oc.lib.db.common :as db-common]))

;; ----- Schema -----

(def Tier
  {:flat-amount (schema/maybe schema/Int)
   :unit-amount (schema/maybe schema/Int)
   :up-to       (schema/maybe schema/Int)
   })

(def Plan
  {:id       schema/Str
   :amount   (schema/maybe schema/Int)
   :nickname schema/Str
   :currency (schema/enum "usd")
   :active   schema/Bool
   :interval (schema/enum "month" "year")
   :tiers    [Tier]
   })

(def InvoiceLineItem
  {:id          schema/Str
   :amount      schema/Int
   :currency    schema/Str
   :description schema/Str
   :quantity    schema/Int
   })

(def Invoice
  {:amount-due           schema/Int
   :currency             schema/Str
   :total                schema/Int
   :subtotal             schema/Int
   :next-payment-attempt schema/Int
   :line-items           [InvoiceLineItem]})

(def SubscriptionItem
  {:id schema/Str
   })

(def Subscription
  {:id                   schema/Str
   :current-period-start schema/Int
   :current-period-end   schema/Int
   :trial-start          (schema/maybe schema/Int)
   :trial-end            (schema/maybe schema/Int)
   :status               (schema/enum "trialing"
                                      "active"
                                      "past_due"
                                      "unpaid"
                                      "canceled"
                                      "incomplete"
                                      "incomplete_expired")
   :quantity             schema/Int
   :current-plan         Plan
   :item                 SubscriptionItem
   :upcoming-invoice     Invoice
   })

(def PaymentMethod
  {:id      schema/Str
   :created schema/Int
   :default schema/Bool
   :card    {:brand     schema/Str
             :exp-year  schema/Int
             :exp-month schema/Int
             :last-4    schema/Str
             :country   schema/Str
             }})

(def Customer
  {:id        schema/Str
   :email     schema/Str
   :full-name schema/Str
   :available-plans [Plan]
   (schema/optional-key :subscription) (schema/maybe Subscription)
   (schema/optional-key :payment-methods) (schema/maybe [PaymentMethod])
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
    (team/update-team! conn team-id {:stripe-customer-id customer-id})
    customer))

(schema/defn ^:always-validate get-customer :- (schema/maybe Customer)
  [conn team-id :- (:team-id team/Team)]
  {:pre [(db-common/conn? conn)]}
  (when-let [customer-id (get-customer-id conn team-id)]
    (stripe/customer-info customer-id)))

(schema/defn ^:always-validate start-plan! :- Customer
  [conn team-id :- (:team-id team/Team) new-plan-id]
  {:pre [(db-common/conn? conn)]}
  (let [customer-id (get-customer-id conn team-id)]
    (stripe/subscribe-customer-to-plan! customer-id new-plan-id)
    (stripe/customer-info customer-id)))

(schema/defn ^:always-validate change-plan! :- Customer
  [conn team-id :- (:team-id team/Team) new-plan-id]
  {:pre [(db-common/conn? conn)]}
  (let [customer-id (get-customer-id conn team-id)]
    (stripe/change-plan! customer-id new-plan-id)
    (stripe/customer-info customer-id)))

(schema/defn ^:always-validate cancel-subscription! :- Customer
  [conn team-id :- (:team-id team/Team)]
  {:pre [(db-common/conn? conn)]}
  (let [customer-id (get-customer-id conn team-id)]
    (stripe/cancel-subscription! customer-id)
    (stripe/customer-info customer-id)))

(schema/defn ^:always-validate report-latest-team-size!
  [customer-id :- (:id Customer)
   seat-count :- (:quantity Subscription)]
  (stripe/report-seats! customer-id seat-count))

(schema/defn ^:always-validate create-checkout-session!
  [conn team-id :- (:team-id team/Team) callback-opts]
  (let [customer-id (get-customer-id conn team-id)]
    (stripe/create-checkout-session! customer-id callback-opts)))

(schema/defn ^:always-validate finish-checkout-session!
  [conn session-id]
  (stripe/assoc-session-result-with-customer! session-id))

(schema/defn ^:always-validate end-trial-period!
  [conn customer-id :- (:id Customer)]
  (stripe/end-trial-period! customer-id))
