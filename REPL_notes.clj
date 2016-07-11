;; productive set of development namespaces
(require '[open-company-auth.jwt :as jwt] :reload)
(require '[open-company-auth.slack :as slack] :reload)
(require '[open-company-auth.app :as app] :reload)

;; print last exception
(print-stack-trace *e)