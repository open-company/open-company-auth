(ns oc.auth.resources.invite-throttle
  "Store notification details with a TTL"
  (:require [taoensso.faraday :as far]
            [clojure.set :as clj-set]
            [taoensso.timbre :as timbre]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.auth.config :as c]
            [oc.lib.dynamo.common :as ttl]))

;; ----- DynamoDB -----

(def table-name (keyword (str c/dynamodb-table-prefix "_invite_throttle")))

;; {
;;  :user-id "1234-1234-1234"
;;  :team-id "1234-1234-1234"
;;  :token "4321-4321-4321"
;;  :invites-count 0
;;  :ttl 123456789
;; }

;; ----- Schema -----

(def user-key-reg-ex #"^team-(\d|[a-f]){4}-(\d|[a-f]){4}-(\d|[a-f]){4}-user-(\d|[a-f]){4}-(\d|[a-f]){4}-(\d|[a-f]){4}$")

(defn user-key?
  "Is this a user-key unique ID? ie: team-1234-1234-1234-user-1234-2134-1234"
  [s]
  (if (and s (string? s) (re-matches user-key-reg-ex s)) true false))

(def UserKey
  (schema/pred user-key?))

(def InviteThrottle {
  :user-id lib-schema/UniqueID
  :team-id lib-schema/UniqueID
  :token lib-schema/UUIDStr
  :invite-count schema/Num
  (schema/optional-key :ttl) schema/Num
})

;; ----- Constructors -----

(schema/defn ^:always-validate ->InviteThrottle :- InviteThrottle
  ([user-id :- lib-schema/UniqueID
    team-id :- lib-schema/UniqueID]
   (->InviteThrottle user-id team-id (str (java.util.UUID/randomUUID)) 0))
  ([user-id :- lib-schema/UniqueID
    team-id :- lib-schema/UniqueID
    invite-count :- schema/Num]
   (->InviteThrottle user-id team-id (str (java.util.UUID/randomUUID)) invite-count))
  ([user-id :- lib-schema/UniqueID
    team-id :- lib-schema/UniqueID
    token :- lib-schema/UUIDStr
    invite-count :- schema/Num]
  {:user-id user-id
   :team-id team-id
   :token token
   :invite-count invite-count
   :ttl (ttl/ttl-epoch c/invite-throttle-ttl)}))

;; ----- DB Operations -----

(defn- transform-invite-throttle
  [invite-throttle-data]
  (-> invite-throttle-data
      (clj-set/rename-keys {:user-id :user_id
                            :team-id :team_id
                            :invite-count :invite_count})
      (assoc :ttl (or (:ttl invite-throttle-data) (ttl/ttl-epoch c/invite-throttle-ttl)))))

(schema/defn ^:always-validate store!
  ([user-id :- lib-schema/UniqueID
    team-id :- lib-schema/UniqueID]
   (store! (->InviteThrottle user-id team-id))
   true)

  ([invite-throttle-data :- InviteThrottle]
  (timbre/debugf "Store invite-throttle for %s %s" (:user-id invite-throttle-data) (:team-id invite-throttle-data))
  (far/put-item c/dynamodb-opts table-name (transform-invite-throttle invite-throttle-data))
  true))

(schema/defn ^:always-validate retrieve :- (schema/maybe InviteThrottle)
  [user-id :- lib-schema/UniqueID
   team-id :- lib-schema/UniqueID]
  (timbre/debugf "Retrieve invite-throttle for %s %s" user-id team-id)
  ;; Filter out TTL records as TTL expiration doesn't happen with local DynamoDB,
  ;; and on server DynamoDB it can be delayed by up to 48 hours
  (-> (far/get-item c/dynamodb-opts table-name {:user_id user-id :team_id team-id})
      (clj-set/rename-keys {:user_id :user-id
                            :team_id :team-id
                            :invite_count :invite-count})
      (dissoc :ttl)))

(schema/defn ^:always-validate delete!
  [user-id :- lib-schema/UniqueID
   team-id :- lib-schema/UniqueID]
  (timbre/debugf "Delete invite-throttle for %s %s" user-id team-id)
  (far/delete-item c/dynamodb-opts table-name {:user_id user-id
                                               :team_id team-id})
  true)

(schema/defn ^:always-validate increase-invite-count!
  [user-id :- lib-schema/UniqueID
   team-id :- lib-schema/UniqueID]
  (timbre/debugf "Increase invite count for %s %s" user-id team-id)
  (if-let [item (retrieve user-id team-id)]
    (do (timbre/debugf "Found item for %s %s, increasing from %s to %s" (:user-id item) (:team-id item) (:invite-count item) (inc (:invite-count item)))
        (far/update-item c/dynamodb-opts table-name {:user_id user-id
                                                     :team_id team-id}
                         {:update-expr "SET invite_count = invite_count + :inc"
                          :expr-attr-vals {":inc" 1}
                          :return :updated-new}))
    (do
      (timbre/debugf "No item found for %s %s, will create one" user-id team-id)
      (store! (->InviteThrottle user-id team-id 1)))))

(schema/defn ^:always-validate update-token!
  [user-id :- lib-schema/UniqueID
   team-id :- lib-schema/UniqueID]
  (timbre/debugf "Update token for %s %s" user-id team-id)
  (if-let [item (retrieve user-id team-id)]
    (do (timbre/debugf "Found item for %s %s, increasing from %s to %s" (:user-id item) (:team-id item) (:invite-count item) (inc (:invite-count item)))
        (far/update-item c/dynamodb-opts table-name {:user_id user-id
                                                     :team_id team-id}
                         {:update-expr "SET #token = :token"
                          :expr-attr-names {"#token" "token"}
                          :expr-attr-vals {":token" (str (java.util.UUID/randomUUID))}
                          :return :updated-new}))
    (do
      (timbre/debugf "No item found for %s %s, will create one" user-id team-id)
      (store! (->InviteThrottle user-id team-id)))))

;; ----- Table handling -----

(defn create-table
  ([] (create-table c/dynamodb-opts))

  ([dynamodb-opts]
   (far/ensure-table dynamodb-opts table-name
                     [:user_id :s]
                     {:range-keydef [:team_id :s]
                      :billing-mode :pay-per-request
                      :block? true})))

(defn delete-table
  ([] (delete-table c/dynamodb-opts))

  ([dynamodb-opts]
   (far/delete-table dynamodb-opts table-name)))

(defn delete-all! []
  (delete-table)
  (create-table))

(comment
  (require '[oc.auth.resources.invite-throttle :as invite-throttle] :reload)

  (far/list-tables c/dynamodb-opts)

  (far/delete-table c/dynamodb-opts invite-throttle/table-name)

  (aprint
   (far/create-table c/dynamodb-opts
                     invite-throttle/table-name
                     [:user_id :s]
                     {:range-keydef [:team_id :s]
                      :billing-mode :pay-per-request
                      :block? true}))

  (aprint (far/describe-table c/dynamodb-opts invite-throttle/table-name))

  (def user-id "1234-5678-1234")
  (def team-id "1234-6463-2563")

  (def it1 (invite-throttle/->InviteThrottle user-id team-id))
  (invite-throttle/store! it1)
  (invite-throttle/increase-invite-count! user-id team-id)
  (invite-throttle/retrieve user-id team-id))
