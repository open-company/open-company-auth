(ns oc.auth.db.migrations
  "Lein main to migrate RethinkDB data."
  (:require [oc.auth.config :as c]
            [oc.lib.db.migrations :as m])
  (:gen-class))

(defn -main
  "
  Run create or migrate from lein:
  
  lein create-migration <name>

  lein migrate-db
  "
  [which & args]
  (cond 
    (= which "migrate") (m/migrate c/db-map c/migrations-dir)
    (= which "create") (apply m/create c/migrations-dir c/migration-template args)
    :else (println "Unknown action: " which)))