(defproject open-company-auth "0.2.0-SNAPSHOT"
  :description "OpenCompany Auth Service"
  :url "https://github.com/open-company/open-company-auth"
  :license {
    :name "GNU Affero General Public License Version 3"
    :url "https://www.gnu.org/licenses/agpl-3.0.en.html"
  }

  :min-lein-version "2.9.1"

  ;; JVM memory
  :jvm-opts ^:replace ["-Xms512m" "-Xmx2048m" "-server"]

  :dependencies [
    ;; Lisp on the JVM http://clojure.org/documentation
    [org.clojure/clojure "1.10.3"]
    ;; Command-line parsing https://github.com/clojure/tools.cli
    [org.clojure/tools.cli "1.0.206"]
    ;; Web application library https://github.com/ring-clojure/ring
    [ring/ring-devel "1.9.1"]
    ;; Web application library https://github.com/ring-clojure/ring
    ;; NB: clj-time pulled in by oc.lib
    ;; NB: joda-time pulled in by oc.lib via clj-time
    ;; NB: commons-codec pulled in by oc.lib
    [ring/ring-core "1.9.1" :exclusions [clj-time joda-time]]
    ;; CORS library https://github.com/jumblerg/ring.middleware.cors
    [jumblerg/ring.middleware.cors "1.0.1"]
    ;; Ring logging https://github.com/nberger/ring-logger-timbre
    ;; NB: com.taoensso/encore pulled in by oc.lib
    ;; NB: com.taoensso/timbre pulled in by oc.lib
    [ring-logger-timbre "0.7.6" :exclusions [com.taoensso/encore com.taoensso/timbre]]
    ;; Web routing https://github.com/weavejester/compojure
    [compojure "1.6.2"]
    ;; For google oauth2
    ;; NB: commons-codec pulled in by oc.lib
    [stuarth/clj-oauth2 "0.3.2" :exclusions [commons-codec]]
    ;; Security library https://github.com/funcool/buddy
    ;; NB: commons-codec pulled in by oc.lib
    ;; NB: buddy/buddy-core pulled in by buddy-auth
    ;; NB: buddy/buddy-sign pulled in by buddy-auth
    [buddy "2.0.0" :exclusions [commons-codec buddy/buddy-core buddy/buddy-sign]]
    ;; Authentication for ring https://github.com/funcool/buddy-auth
    ;; NB: funcool/cuerdas pulled in by oc.lib
    [buddy/buddy-auth "2.2.0" :exclusions [funcool/cuerdas]]
    ;; Pretty-print clj and EDN https://github.com/kkinnear/zprint
    [zprint "1.0.1"]
    ;; Not used directly, dependency of oc.lib and org.julienxx/clj-slack https://github.com/dakrone/clj-http
    ;; NB: org.apache.httpcomponents/httpclient pulled in by oc.lib
    ;; NB: commons-codec pulled in by oc.lib
    ;; NB: riddley pulled in by oc.lib via aleph → manifold
    [clj-http "3.10.3" :exclusions [org.apache.httpcomponents/httpclient commons-codec riddley]]
    ;; Not used directly, dependency of oc.lib and org.julienxx/clj-slack https://github.com/clojure/data.json
    [org.clojure/data.json "1.0.0"]
    ;; Not used directly, dependency of data.json and clj-slack https://github.com/clojure/tools.logging
    [org.clojure/tools.logging "1.1.0"]
    
    
    ;; General data-binding functionality for Jackson: works on core streaming API https://github.com/FasterXML/jackson-databind
    [com.fasterxml.jackson.core/jackson-databind "2.11.2"]

    ;; Library for OC projects https://github.com/open-company/open-company-lib
    ;; ************************************************************************
    ;; ****************** NB: don't go under 0.17.29-alpha60 ******************
    ;; ***************** (JWT schema changes, more info here: *****************
    ;; ******* https://github.com/open-company/open-company-lib/pull/82) ******
    ;; ************************************************************************
    [open-company/lib "0.20.3-alpha1" :exclusions [clj-http org.bouncycastle/bcpkix-jdk15on com.fasterxml.jackson.core/jackson-databind org.clojure/tools.reader]]
    ;; ************************************************************************
    ;; NB: clj-http pulled in manually
    ;; NB: org.clojure/data.json pulled in manually
    ;; NB: org.clojure/tools.logging pulled in manually
    ;; NB: org.bouncycastle/bcpkix-jdk15on pulled in via buddy-auth
    ;; In addition to common functions, brings in the following common dependencies used by this project:
    ;; defun - Erlang-esque pattern matching for Clojure functions https://github.com/killme2008/defun
    ;; if-let - More than one binding for if/when macros https://github.com/LockedOn/if-let
    ;; Component - Component Lifecycle https://github.com/stuartsierra/component
    ;; Liberator - WebMachine (REST API server) port to Clojure https://github.com/clojure-liberator/liberator
    ;; RethinkDB - RethinkDB client for Clojure https://github.com/apa512/clj-rethinkdb
    ;; Schema - Data validation https://github.com/Prismatic/schema
    ;; Timbre - Pure Clojure/Script logging library https://github.com/ptaoussanis/timbre
    ;; Amazonica - A comprehensive Clojure client for the AWS API. https://github.com/mcohen01/amazonica
    ;; Sentry - Interface to Sentry error reporting https://github.com/getsentry/sentry-clj
    ;; http-kit - HTTP client and server https://github.com/http-kit/http-kit
    ;; Cheshire - JSON encoding / decoding https://github.com/dakrone/cheshire
    ;; clj-jwt - A Clojure library for JSON Web Token(JWT) https://github.com/liquidz/clj-jwt
    ;; clj-time - Date and time lib https://github.com/clj-time/clj-time
    ;; Environ - Get environment settings from different sources https://github.com/weavejester/environ
    ;; clj-slack - Clojure Slack REST API https://github.com/julienXX/clj-slack
  ]

  :plugins [
    [lein-ring "0.12.5"]
    [lein-environ "1.2.0"] ; Get environment settings from different sources https://github.com/weavejester/environ
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
        ;; Example-based testing https://github.com/marick/Midje
        ;; NB: clj-time is pulled in by oc.lib
        ;; NB: joda-time is pulled in by oc.lib via clj-time
        ;; NB: commons-codec pulled in by oc.lib
        [midje "1.9.9" :exclusions [joda-time clj-time commons-codec]]
        ;; Test Ring requests https://github.com/weavejester/ring-mock
        [ring-mock "0.1.5"]
      ]
      :plugins [
        ;; Example-based testing https://github.com/marick/lein-midje
        [lein-midje "3.2.2"]
        ;; Linter https://github.com/jonase/eastwood
        [jonase/eastwood "0.3.11"]
        ;; Static code search for non-idiomatic code https://github.com/jonase/kibit
        ;; NB: rewrite-clj is pulled in manually
        ;; NB: org.clojure/tools.reader pulled in manually
        [lein-kibit "0.1.8" :exclusions [org.clojure/clojure rewrite-clj org.clojure/tools.reader]]
        ;; Dependency of lein-kibit and lein-zprint https://github.com/xsc/rewrite-clj
        ;; NB: org.clojure/tools.reader pulled in manually
        [rewrite-clj "0.6.1" :exclusions [org.clojure/tools.reader]]
        ;; Not used directly, dependency of lein-kibit and rewrite-clj https://github.com/clojure/tools.reader
        [org.clojure/tools.reader "1.3.3"]
      ]
    }

    ;; Dev environment and dependencies
    :dev [:qa {
      :env ^:replace {
        :db-name "open_company_auth_dev"
        :liberator-trace "true" ; liberator debug data in HTTP response headers
        :open-company-auth-passphrase "this_is_a_dev_secret" ; JWT secret
        :hot-reload "true" ; reload code when changed on the file system
        :open-company-slack-client-id "CHANGE-ME"
        :open-company-slack-client-secret "CHANGE-ME"
        :aws-access-key-id "CHANGE-ME"
        :aws-secret-access-key "CHANGE-ME"
        :aws-sqs-bot-queue "CHANGE-ME"
        :aws-sqs-email-queue "CHANGE-ME"
        :aws-sqs-slack-router-auth-queue "CHANGE-ME"
        :aws-sqs-payments-queue "CHANGE-ME" ; Queue to send team size changes to (optional)
        :aws-sqs-expo-queue "CHANGE-ME"
        :aws-sqs-notify-queue "CHANGE-ME"
        :aws-lambda-expo-prefix "CHANGE-ME"
        :aws-sns-auth-topic-arn "" ; SNS topic to publish notifications (optional)
        :log-level "debug"
      }
      :plugins [
        ;; Check for code smells https://github.com/dakrone/lein-bikeshed
        ;; NB: org.clojure/tools.cli is pulled in by lein-kibit
        [lein-bikeshed "0.5.2" :exclusions [org.clojure/tools.cli]]
        ;; Runs bikeshed, kibit and eastwood https://github.com/itang/lein-checkall
        [lein-checkall "0.1.1"]
        ;; pretty-print the lein project map https://github.com/technomancy/leiningen/tree/master/lein-pprint
        [lein-pprint "1.3.2"]
        ;; Check for outdated dependencies https://github.com/xsc/lein-ancient
        [lein-ancient "1.0.0-RC3"]
        ;; Catch spelling mistakes in docs and docstrings https://github.com/cldwalker/lein-spell
        [lein-spell "0.1.0"]
        ;; Dead code finder https://github.com/venantius/yagni
        [venantius/yagni "0.1.7" :exclusions [org.clojure/clojure]]
        ;; Pretty-print clj and EDN https://github.com/kkinnear/lein-zprint
        ;; NB: rewrite-clj is pulled in manually
        ;; NB: rewrite-cljs not needed
        [lein-zprint "1.0.1" :exclusions [org.clojure/clojure rewrite-clj rewrite-cljs]]
      ]
    }]

    ;; Production environment
    :prod {
      :env {
        :db-name "open_company_auth"
        :hot-reload "false"
      }
    }

    :repl-config [:dev {
      :dependencies [
        ;; Network REPL https://github.com/clojure/tools.nrepl
        [org.clojure/tools.nrepl "0.2.13"]
        ;; Pretty printing in the REPL (aprint ...) https://github.com/razum2um/aprint
        [aprint "0.1.3"]
      ]
      ;; REPL injections
      :injections [
        (require '[aprint.core :refer (aprint ap)]
                 '[clojure.stacktrace :refer (print-stack-trace)]
                 '[clj-time.core :as t]
                 '[clj-time.format :as format]
                 '[clojure.string :as s]
                 '[rethinkdb.query :as r]
                 '[schema.core :as schema]
                 '[oc.lib.db.common :as db-common]
                 '[oc.lib.schema :as lib-schema]
                 '[oc.lib.jwt :as jwt]
                 '[oc.auth.lib.jwtoken :as jwtoken]
                 '[oc.auth.config :as config]
                 '[oc.auth.resources.user :as u]
                 '[oc.auth.resources.team :as team]
                 '[oc.auth.resources.slack-org :as slack-org]
                 '[oc.auth.resources.maintenance :as maint])
      ]
    }]
  }

  :repl-options {
    :welcome (println (str "\n" (slurp (clojure.java.io/resource "ascii_art.txt")) "\n"
                      "OpenCompany Auth REPL\n"
                      "\nReady to do your bidding... I suggest (go) or (go <port>) or (go-db) as your first command.\n"))
    :init-ns dev
                 :timeout 1200000
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
    ;; constant-test - just seems mostly ill-advised, logical constants are useful in something like a `->cond`
    ;; wrong-arity - unfortunate, but it's failing on 3/arity of sqs/send-message
    ;; implicit-dependencies - uhh, just seems dumb
    :exclude-linters [:constant-test :wrong-arity :implicit-dependencies]
    ;; Enable some linters that are disabled by default
    :add-linters [:unused-namespaces :unused-private-vars] ; :unused-locals]

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
