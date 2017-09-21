(ns oc.auth.unit.user
  (:require [midje.sweet :refer :all]
            [oc.lib.db.pool :as pool]
            [oc.auth.lib.test-setup :as ts]
            [oc.auth.resources.team :as team-res]
            [oc.auth.resources.user :as user-res]))

;; ----- Utility Functions -----

(def team-admin "abcd-1234-abcd")
(def new-admin "1234-abcd-1234")
(def team-names ["a" "b" "c"])

(defn- create-teams [conn]
  (doall (map #(:team-id (team-res/create-team! conn (team-res/->team {:name %} team-admin))) team-names)))

;; ----- Tests -----

(with-state-changes [(before :contents (ts/setup-system!))
                     (after :contents (ts/teardown-system!))]

  (pool/with-pool [conn (-> @ts/test-system :db-pool :pool)]
    (facts "Test team admins"

      (with-state-changes [(before :contents (team-res/delete-all-teams! conn))
                           (after :contents (team-res/delete-all-teams! conn))]

        (let [team-ids (create-teams conn)]
          
          (fact "team creator is an admin"
            (user-res/admin-of conn team-admin) => (contains team-ids :in-any-order)
            (:admins (team-res/get-team conn (first team-ids))) => [team-admin]
            (:admins (team-res/get-team conn (second team-ids))) => [team-admin]
            (:admins (team-res/get-team conn (last team-ids))) => [team-admin])

          (fact "added admin is an admin"
            (team-res/add-admin conn (first team-ids) new-admin)
            (user-res/admin-of conn new-admin) => [(first team-ids)]
            (:admins (team-res/get-team conn (first team-ids))) => (contains [team-admin new-admin] :in-any-order))

          (fact "removed admin is not an admin"
            ;; 2 admins
            (user-res/admin-of conn new-admin) => [(first team-ids)]
            (:admins (team-res/get-team conn (first team-ids))) => (contains [team-admin new-admin] :in-any-order)
            ;; remove
            (team-res/remove-admin conn (first team-ids) new-admin)
            ;; 1 admin
            (user-res/admin-of conn new-admin) => []
            (:admins (team-res/get-team conn (first team-ids))) => [team-admin])

          (fact "deleted user is not an admin"
            ;; is an admin
            (:admins (team-res/get-team conn (first team-ids))) => [team-admin]
            (:admins (team-res/get-team conn (second team-ids))) => [team-admin]
            (:admins (team-res/get-team conn (last team-ids))) => [team-admin]            
            ;; delete user
            (user-res/delete-user! conn team-admin)
            ;; not an admin
            (:admins (team-res/get-team conn (first team-ids))) => []
            (:admins (team-res/get-team conn (second team-ids))) => []
            (:admins (team-res/get-team conn (last team-ids))) => []))))))