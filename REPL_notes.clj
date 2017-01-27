;; productive set of development namespaces
(require '[schema.core :as schema])
(require '[oc.lib.jwt :as jwt] :reload)
(require '[oc.lib.rethinkdb.common :as common] :reload)
(require '[oc.lib.api.common :as api-common] :reload)
(require '[oc.auth.app :as app] :reload)
(require '[oc.auth.slack-auth :as slack] :reload)
(require '[oc.auth.email-auth :as email] :reload)
(require '[oc.auth.resources.user :as u] :reload)
(require '[oc.auth.resources.team :as team] :reload)
(require '[oc.auth.resources.slack-org :as slack-org] :reload)
(require '[oc.auth.representations.user :as user-rep] :reload)
(require '[oc.auth.representations.team :as team-rep] :reload)
(require '[oc.auth.api.teams :as team-api] :reload)
(require '[oc.auth.api.users :as user-api] :reload)

;; print last exception
(print-stack-trace *e)