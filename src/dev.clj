(ns dev
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.namespace.repl :as ctnrepl]
            [oc.auth.config :as c]
            [oc.auth.app :as app]
            [oc.lib.rethinkdb.pool :as pool]
            [oc.auth.components :as components]))

(def system nil)
(def conn nil)

(defn init
  ([] (init c/auth-server-port))
  ([port]
  (alter-var-root #'system (constantly (components/auth-system {:handler-fn app/app
                                                                :port port})))))

(defn bind-conn! []
  (alter-var-root #'conn (constantly (pool/claim (get-in system [:db-pool :pool])))))

(defn start []
  (alter-var-root #'system component/start))

(defn stop []
  (alter-var-root #'system (fn [s] (when s (component/stop s)))))

(defn go
  ([] (go c/auth-server-port))
  ([port]
  (init port)
  (start)
  (bind-conn!)))

(defn reset []
  (stop)
  (ctnrepl/refresh :after 'user/go))

(comment

  (into {} system)

  (go)

  (do
    (clojure.tools.namespace.repl/set-refresh-dirs "src")
    (reset))

  )