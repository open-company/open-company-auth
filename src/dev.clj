(ns dev
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.namespace.repl :as ctnrepl]
            [oc.auth.config :as c]
            [oc.auth.app :as app]
            [oc.lib.db.pool :as pool]
            [oc.auth.async.slack-router :as slack-router]
            [oc.auth.components :as components]))

(def system nil)
(def conn nil)

(defn init
  ([] (init c/auth-server-port))
  ([port]
   (alter-var-root #'system (constantly (components/auth-system
                                         {:handler-fn app/app
                                          :sqs-queue c/aws-sqs-slack-router-auth-queue
                                          :slack-sqs-msg-handler slack-router/sqs-handler
                                          :sqs-creds {:access-key c/aws-access-key-id
                                                      :secret-key c/aws-secret-access-key}
                                          :port port})))))

(defn init-db []
  (alter-var-root #'system (constantly (components/db-only-auth-system {}))))

(defn bind-conn! []
  (alter-var-root #'conn (constantly (pool/claim (get-in system [:db-pool :pool])))))

(defn- start⬆ []
  (alter-var-root #'system component/start))

(defn stop []
  (alter-var-root #'system (fn [s] (when s (component/stop s)))))

(defn go-db []
  (init-db)
  (start⬆)
  (bind-conn!)
  (println (str "A DB connection is available with: conn\n"
                "When you're ready to stop the system, just type: (stop)\n")))

(defn go

  ([] (go c/auth-server-port))
  
  ([port]
  (init port)
  (start⬆)
  (bind-conn!)
  (app/echo-config port)
  (println (str "Now serving auth from the REPL.\n"
                "A DB connection is available with: conn\n"
                "When you're ready to stop the system, just type: (stop)\n"))
  port))

(defn reset []
  (stop)
  (ctnrepl/refresh :after 'user/go))