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
    [org.clojure/clojure "1.9.0-alpha9"] ; Lisp on the JVM http://clojure.org/documentation
    [defun "0.3.0-alapha"] ; Erlang-esque pattern matching for Clojure functions https://github.com/killme2008/defun
    [ring/ring-devel "1.6.0-beta3"] ; Web application library https://github.com/ring-clojure/ring
    [ring/ring-core "1.6.0-beta3"] ; Web application library https://github.com/ring-clojure/ring
    [compojure "1.5.1"] ; A concise routing library for Ring/Clojure https://github.com/weavejester/compojure
    [commons-codec "1.10" :exclusions [[org.clojure/clojure]]] ; Dependency of compojure, ring-core, and midje http://commons.apache.org/proper/commons-codec/
    [http-kit "2.2.0-beta1"] ; Web server http://http-kit.org/
    [cheshire "5.6.3"] ; JSON encoder/decoder https://github.com/dakrone/cheshire
    [org.julienxx/clj-slack "0.5.4"] ; Clojure Slack REST API https://github.com/julienXX/clj-slack
    [raven-clj "1.4.2"] ; Clojure interface to Sentry error reporting https://github.com/sethtrain/raven-clj
    [environ "1.0.3"] ; Get environment settings from different sources https://github.com/weavejester/environ
    [jumblerg/ring.middleware.cors "1.0.1"] ; CORS library https://github.com/jumblerg/ring.middleware.cors
    [clj-jwt "0.1.1"] ; Clojure library for JSON Web Token (JWT) https://github.com/liquidz/clj-jwt
    [org.clojure/tools.cli "0.3.5"] ; command-line parsing https://github.com/clojure/tools.cli
    [com.taoensso/timbre "4.6.0-alpha7"] ; logging https://github.com/ptaoussanis/timbre
    [alandipert/enduro "1.2.0"] ; Durable atoms https://github.com/alandipert/enduro
    [amazonica "0.3.66"] ;; AWS S3 https://github.com/mcohen01/amazonica
  ]

  :plugins [
    [lein-ring "0.9.7"]
    [lein-environ "1.0.3"] ; Get environment settings from different sources https://github.com/weavejester/environ
  ]

  :profiles {
    ;; QA environment and dependencies
    :qa {
      :env {
        :hot-reload "false"
        :open-company-auth-passphrase "this_is_a_qa_secret" ; JWT secret
      }
      :dependencies [
        [midje "1.9.0-alpha3"] ; Example-based testing https://github.com/marick/Midje
        [ring-mock "0.1.5"] ; Test Ring requests https://github.com/weavejester/ring-mock
      ]
      :plugins [
        [lein-midje "3.2"] ; Example-based testing https://github.com/marick/lein-midje
        [jonase/eastwood "0.2.3"] ; Clojure linter https://github.com/jonase/eastwood
        [lein-kibit "0.1.2"] ; Static code search for non-idiomatic code https://github.com/jonase/kibit
      ]
    }

    ;; Dev environment and dependencies
    :dev [:qa {
      :env ^:replace {
        :open-company-auth-passphrase "this_is_a_dev_secret" ; JWT secret
        :hot-reload "true" ; reload code when changed on the file system
        :open-company-slack-client-id "CHANGE-ME"
        :open-company-slack-client-secret "CHANGE-ME"
        :aws-access-key-id "CHANGE-ME"
        :aws-secret-access-key "CHANGE-ME"
        :aws-secrets-bucket "CHANGE-ME"
      }
      :plugins [
        [lein-bikeshed "0.2.0"] ; Check for code smells https://github.com/dakrone/lein-bikeshed
        [lein-checkall "0.1.1"] ; Runs bikeshed, kibit and eastwood https://github.com/itang/lein-checkall
        [lein-pprint "1.1.2"] ; pretty-print the lein project map https://github.com/technomancy/leiningen/tree/master/lein-pprint
        [lein-ancient "0.6.7"] ; Check for outdated dependencies https://github.com/xsc/lein-ancient
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
                 '[open-company-auth.config :as config]
                 '[open-company-auth.store :as store])
      ]
    }]

    ;; Production environment
    :prod {
      :env {
        :hot-reload "false"
      }
    }
  }

  :aliases{
    "start" ["do" "run"] ; start a development server
    "start!" ["with-profile" "prod" "do" "build," "run"] ; start a server in production
    "build" ["do" "clean," "deps," "compile"] ; clean and build code
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
    :add-linters [:unused-namespaces :unused-private-vars :unused-locals]

    ;; Exclude testing namespaces
    :tests-paths ["test"]
    :exclude-namespaces [:test-paths]
  }

  ;; ----- Web Application -----

  :ring {
    :handler open-company-auth.app/app
    :reload-paths ["src"] ; work around issue https://github.com/weavejester/lein-ring/issues/68
    :port 3003
  }

  :resource-paths ["resources" ]

  :main open-company-auth.app
)
