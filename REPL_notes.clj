;; productive set of development namespaces
(require '[oc.auth.jwt :as jwt] :reload)
(require '[oc.auth.slack :as slack] :reload)
(require '[oc.auth.app :as app] :reload)
(require '[oc.auth.email :as email] :reload)
(require '[oc.auth.user :as user] :reload)
(require '[oc.lib.rethinkdb.common :as common] :reload)

;; print last exception
(print-stack-trace *e)