(ns oc.auth.api.payments
  (:require [oc.auth.resources.payments :as payments]
            [compojure.core :as compojure :refer [GET]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REST API

;; (defn routes [sys]
;;   (let [db-pool (-> sys :db-pool :pool)]
;;     (compojure/routes
;;      (GET "/payments/customer")
;;      )))

