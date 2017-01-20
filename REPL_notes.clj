;; productive set of development namespaces
(require '[oc.lib.jwt :as jwt] :reload)
(require '[oc.auth.app :as app] :reload)
(require '[oc.auth.slack-auth :as slack] :reload)
(require '[oc.auth.email-auth :as email] :reload)
(require '[oc.auth.resources.user :as u] :reload)
(require '[oc.auth.resources.team :as team] :reload)
(require '[oc.lib.rethinkdb.common :as common] :reload)

;; print last exception
(print-stack-trace *e)