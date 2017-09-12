(ns oc.auth.unit.user
  (:require [midje.sweet :refer :all]
            [oc.lib.db.pool :as pool]
            [oc.auth.lib.test-setup :as ts]
            [oc.auth.resources.team :as team-res]
            [oc.auth.resources.user :as user-res]))

;; ----- Utility Functions -----

(def team-admin "abcd-1234-abcd")
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
            ;(map #(:admins (team-res/get-team conn %)) team-ids)
            )

          (future-fact "added admin is an admin")
        )
      )
    )
  )
)
;     (fact "By pinging"
;       (let [resp (test-utils/api-request :get "/ping" {})]
;         (:status resp) => 200))

;     (facts "By testing errors"
;       (test-utils/api-request :get "/---error-test---" {}) => (throws Exception)
;       (let [resp (test-utils/api-request :get "/---500-test---" {})]
;         (:status resp) => 500))
    
;     (fact "By requesting auth-settings anonymously"
;       (let [resp (test-utils/api-request :get "/" {})
;             body (json/parse-string (:body resp))]
;         (:status resp) => 200
;         (test-utils/response-mime-type resp) => "application/json"
;         (contains? body "slack") => true
;         (contains? (body "slack") "links") => true
;         (contains? body "email") => true
;         (contains? (body "email") "links") => true))
    
;     (future-fact "by requesting auth-settings with an invalid JWToken")

;     (future-fact "by requesting auth-settings with an old JWToken")

;     (future-fact "by requesting auth-settings with a Slack JWToken")

;     (future-fact "by requesting auth-settings with an Email JWToken")

;     (fact "by requesting token debugging with /test-token"
;       (let [resp (test-utils/api-request :get "/test-token" {})
;             body (json/parse-string (:body resp))]
;         (:status resp) => 200
;         (contains? body "jwt-token") => true
;         (contains? body "jwt-verified") => true
;         (contains? body "jwt-decoded") => true
;         (body "jwt-verified") => true))))