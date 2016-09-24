(ns oc.auth.user
  ""
  (:require [clojure.string :as s]
            [rethinkdb.query :as r]))

;; ----- RethinkDB metadata -----

(def table-name "users")
(def primary-key :id)