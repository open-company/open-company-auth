;; productive set of development namespaces
(require '[rethinkdb.query :as r])
(require '[schema.core :as schema])
(require '[oc.lib.api.common :as api-common])
(require '[oc.lib.db.common :as common])
(require '[oc.lib.jwt :as jwt])
(require '[oc.auth.lib.jwtoken :as jwtoken] :reload)
(require '[oc.auth.app :as app] :reload)
(require '[oc.auth.slack-auth :as slack] :reload)
(require '[oc.auth.email-auth :as email] :reload)
(require '[oc.auth.resources.user :as u] :reload)
(require '[oc.auth.resources.team :as team] :reload)
(require '[oc.auth.resources.slack-org :as slack-org] :reload)
(require '[oc.auth.representations.user :as user-rep] :reload)
(require '[oc.auth.representations.team :as team-rep] :reload)
(require '[oc.auth.representations.slack-org :as slack-org-rep] :reload)
(require '[oc.auth.api.teams :as team-api] :reload)
(require '[oc.auth.api.users :as user-api] :reload)
(require '[oc.auth.api.slack :as slack-api] :reload)

;; print last exception
(print-stack-trace *e)

;; Direct RethinkDB usage
(def conn2 [:host "127.0.0.1" :port 28015 :db "open_company_auth"])

;; Update the name of a Team
(with-open [c (apply r/connect conn2)]
  (-> (r/table "teams")
      (r/get "f725-4791-80ac")
      (r/update {:name "GreenLabs"})
      (r/run c)))

;; Update a set of users
(with-open [c (apply r/connect conn2)]
  (-> (r/table "users")
    (r/get-all ["30b6-4fdd-b4fa" "8c3b-462a-b8e6" "4c61-4782-b0b3" "be62-4263-8b72"])
    (r/update (r/fn [_] {:status "verified"}))
    (r/run c)))