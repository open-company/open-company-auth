(defproject open-company-auth "0.0.1-SNAPSHOT"
  :description "Handles auth calls and callbacks"
  :url "https://opencompany.com/"
  :license {
    :name "Mozilla Public License v2.0"
    :url "http://www.mozilla.org/MPL/2.0/"
  }

  :min-lein-version "2.5.1" ; highest version supported by Travis-CI as of 7/5/2015

  ;; JVM memory
  :jvm-opts ^:replace ["-Xms512m" "-Xmx2048m" "-server"]

  :dependencies [
    [org.clojure/clojure "1.9.0-alpha14"] ; Lisp on the JVM http://clojure.org/documentation
    [org.clojure/core.async "0.2.395"] ; Async programming and communication https://github.com/clojure/core.async
    [lockedon/if-let "0.1.0"] ; More than one binding for if/when macros https://github.com/LockedOn/if-let
    [defun "0.3.0-RC1"] ; Erlang-esque pattern matching for Clojure functions https://github.com/killme2008/defun
    [ring/ring-devel "1.6.0-beta7"] ; Web application library https://github.com/ring-clojure/ring
    [ring/ring-core "1.6.0-beta7"] ; Web application library https://github.com/ring-clojure/ring
    [compojure "1.6.0-beta3"] ; A concise routing library for Ring/Clojure https://github.com/weavejester/compojure
    [liberator "0.14.1"] ; WebMachine (REST API server) port to Clojure https://github.com/clojure-liberator/liberator
    [commons-codec "1.10" :exclusions [[org.clojure/clojure]]] ; Dependency of compojure, ring-core, and midje http://commons.apache.org/proper/commons-codec/
    [http-kit "2.3.0-alpha1"] ; Web server http://http-kit.org/
    [com.stuartsierra/component "0.3.2"] ; Component Lifecycle
    [prismatic/schema "1.1.3"] ; Data validation https://github.com/Prismatic/schema
    [cheshire "5.7.0"] ; JSON encoder/decoder https://github.com/dakrone/cheshire
    [com.apa512/rethinkdb "0.15.26"] ; RethinkDB client for Clojure https://github.com/apa512/clj-rethinkdb
    [org.julienxx/clj-slack "0.5.4"] ; Clojure Slack REST API https://github.com/julienXX/clj-slack
    [raven-clj "1.5.0"] ; Clojure interface to Sentry error reporting https://github.com/sethtrain/raven-clj
    [buddy "1.2.0"] ; Security library https://github.com/funcool/buddy
    [buddy/buddy-auth "1.3.0"] ; Authentication for ring https://github.com/funcool/buddy-auth
    [environ "1.1.0"] ; Get environment settings from different sources https://github.com/weavejester/environ
    [jumblerg/ring.middleware.cors "1.0.1"] ; CORS library https://github.com/jumblerg/ring.middleware.cors
    [clj-jwt "0.1.1"] ; Clojure library for JSON Web Token (JWT) https://github.com/liquidz/clj-jwt
    [org.clojure/tools.cli "0.3.5"] ; command-line parsing https://github.com/clojure/tools.cli
    [com.taoensso/timbre "4.8.0"] ; logging https://github.com/ptaoussanis/timbre
    [alandipert/enduro "1.2.0"] ; Durable atoms https://github.com/alandipert/enduro
    [clj-time "0.13.0"] ; JodaTime wrapper https://github.com/clj-time/clj-time
    [com.taoensso/truss "1.3.6"] ; Assertions w/ great errors https://github.com/ptaoussanis/truss
    [amazonica "0.3.84"] ; A comprehensive Clojure client for the entire Amazon AWS api https://github.com/mcohen01/amazonica
    [open-company/lib "0.1.1-dc1d942"] ; Library for OC projects https://github.com/open-company/open-company-lib
  ]

  :plugins [
    [lein-ring "0.10.0"]
    [lein-environ "1.1.0"] ; Get environment settings from different sources https://github.com/weavejester/environ
  ]

  :profiles {
    ;; QA environment and dependencies
    :qa {
      :env {
        :db-name "open_company_auth_qa"
        :hot-reload "false"
        :open-company-auth-passphrase "this_is_a_qa_secret" ; JWT secret
      }
      :dependencies [
        [midje "1.9.0-alpha6"] ; Example-based testing https://github.com/marick/Midje
        [ring-mock "0.1.5"] ; Test Ring requests https://github.com/weavejester/ring-mock
      ]
      :plugins [
        [lein-midje "3.2.1"] ; Example-based testing https://github.com/marick/lein-midje
        [jonase/eastwood "0.2.3"] ; Clojure linter https://github.com/jonase/eastwood
        [lein-kibit "0.1.3"] ; Static code search for non-idiomatic code https://github.com/jonase/kibit
      ]
    }

    ;; Dev environment and dependencies
    :dev [:qa {
      :env ^:replace {
        :db-name "open_company_auth_dev"
        :open-company-auth-passphrase "this_is_a_dev_secret" ; JWT secret
        :hot-reload "true" ; reload code when changed on the file system
        :open-company-slack-client-id "CHANGE-ME"
        :open-company-slack-client-secret "CHANGE-ME"
        :aws-access-key-id "CHANGE-ME"
        :aws-secret-access-key "CHANGE-ME"
        :aws-secrets-bucket "CHANGE-ME"
        :intro "true"
        :log-level "debug"
      }
      :plugins [
        [lein-bikeshed "0.4.1"] ; Check for code smells https://github.com/dakrone/lein-bikeshed
        [lein-checkall "0.1.1"] ; Runs bikeshed, kibit and eastwood https://github.com/itang/lein-checkall
        [lein-pprint "1.1.2"] ; pretty-print the lein project map https://github.com/technomancy/leiningen/tree/master/lein-pprint
        [lein-ancient "0.6.10"] ; Check for outdated dependencies https://github.com/xsc/lein-ancient
        [lein-spell "0.1.0"] ; Catch spelling mistakes in docs and docstrings https://github.com/cldwalker/lein-spell
        [lein-deps-tree "0.1.2"] ; Print a tree of project dependencies https://github.com/the-kenny/lein-deps-tree
        [venantius/yagni "0.1.4"] ; Dead code finder https://github.com/venantius/yagni
      ]
    }]
    :repl-config [:dev {
      :dependencies [
        [org.clojure/tools.nrepl "0.2.12"] ; Network REPL https://github.com/clojure/tools.nrepl
        [aprint "0.1.3"] ; Pretty printing in the REPL (aprint ...) https://github.com/razum2um/aprint
      ]
      ;; REPL injections
      :injections [
        (require '[aprint.core :refer (aprint ap)]
                 '[clojure.stacktrace :refer (print-stack-trace)]
                 '[clj-time.core :as t]
                 '[clj-time.format :as format]
                 '[clojure.string :as s]
                 '[rethinkdb.query :as r]
                 '[oc.auth.config :as config]
                 '[oc.auth.email-auth :as email]
                 '[oc.auth.slack-auth :as slack]
                 '[oc.auth.resources.user :as u]
                 '[oc.auth.resources.team :as team])
      ]
    }]

    ;; Production environment
    :prod {
      :env {
        :db-name "open_company_auth"
        :hot-reload "false"
      }
    }
  }

  :repl-options {
    :welcome (println (str "\n" (slurp (clojure.java.io/resource "ascii_art.txt")) "\n"
                      "OpenCompany Auth REPL\n"
                      "Database: " oc.auth.config/db-name "\n"
                      "\nReady to do your bidding... I suggest (go) or (go <port>) as your first command.\n"))
      :init-ns dev
  }

  :aliases{
    "build" ["do" "clean," "deps," "compile"] ; clean and build code
    "create-migration" ["run" "-m" "oc.auth.db.migrations" "create"] ; create a data migration
    "migrate-db" ["run" "-m" "oc.auth.db.migrations" "migrate"] ; run pending data migrations
    "start" ["do" "migrate-db," "run" "-m" "oc.auth.app"] ; start a development server
    "start!" ["with-profile" "prod" "do" "start"] ; start a server in production
    "midje!" ["with-profile" "qa" "midje"] ; run all tests
    "test!" ["with-profile" "qa" "do" "clean," "build," "midje"] ; build, init the DB and run all tests
    "autotest" ["with-profile" "qa" "midje" ":autotest"] ; watch for code changes and run affected tests
    "repl" ["with-profile" "+repl-config" "repl"]
    "spell!" ["spell" "-n"] ; check spelling in docs and docstrings
    "bikeshed!" ["bikeshed" "-v" "-m" "120"] ; code check with max line length warning of 120 characters
    "ancient" ["ancient" ":all" ":allow-qualified"] ; check for out of date dependencies
  }
  
  ;; ----- Code check configuration -----

  :eastwood {
    ;; Disable some linters that are enabled by default
    :exclude-linters [:constant-test :wrong-arity]
    ;; Enable some linters that are disabled by default
    :add-linters [:unused-namespaces :unused-private-vars]

    ;; Exclude testing namespaces
    :tests-paths ["test"]
    :exclude-namespaces [:test-paths]
  }

  ;; ----- Web Application -----

  :ring {
    :handler oc.auth.app/app
    :reload-paths ["src"] ; work around issue https://github.com/weavejester/lein-ring/issues/68
    :port 3003
  }

  :resource-paths ["resources" ]

  :main oc.auth.app
)
