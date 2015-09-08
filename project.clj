(defproject open-company-auth "0.0.1-SNAPSHOT"
  :description "Handles auth calls from the web app and the callback from slack"
  :url "https://open-company.io"
  :license {
    :name "Mozilla Public License, version 2.0"
    :url "http://open-company.io/license"}
  
  :min-lein-version "2.5.1" ; highest version supported by Travis-CI as of 7/5/2015

  ;; JVM memory
  :jvm-opts ^:replace ["-Xms512m" "-Xmx2048m" "-server"]

  :dependencies [
    [org.clojure/clojure "1.8.0-alpha4"] ; Lisp on the JVM http://clojure.org/documentation
    [ring/ring-devel "1.4.0"] ; Web application library https://github.com/ring-clojure/ring
    [ring/ring-core "1.4.0"] ; Web application library https://github.com/ring-clojure/ring
    [compojure "1.4.0"] ; A concise routing library for Ring/Clojure https://github.com/weavejester/compojure
    [http-kit "2.1.18"] ; http-kip https://github.com/http-kit/http-kit
    [org.clojure/data.json "0.2.6"] ; data.JSON https://github.com/clojure/data.json
    [javax.servlet/servlet-api "2.5"] ; required by ring https://github.com/ring-clojure/ring#upgrade-notice
    [cheshire "5.5.0"] ; Used to print JSON responses https://github.com/dakrone/cheshire
    [org.julienxx/clj-slack "0.5.0"] ; Clj Slack REST API https://github.com/julienXX/clj-slack
    [raven-clj "1.3.1"] ; Clojure interface to Sentry error reporting https://github.com/sethtrain/raven-clj
    [environ "1.0.0"] ; Get environment settings from different sources https://github.com/weavejester/environ
    [jumblerg/ring.middleware.cors "1.0.1"] ; CORS library https://github.com/jumblerg/ring.middleware.cors
    [clj-jwt "0.1.0"] ; Clojure library for JSON Web Token(JWT) https://github.com/liquidz/clj-jwt
  ]

  :plugins [
    [lein-ring "0.9.6"]
    [lein-environ "1.0.0"] ; Get environment settings from different sources https://github.com/weavejester/environ
  ]

  :profiles {
    :dev {
      :env {
        :hot-reload true
        :passphrase "this_is_a_dev_secret"}

      :dependencies [
        [javax.servlet/servlet-api "2.5"]
        [ring-mock "0.1.5"]]}
    ;; Production environment
    :prod {
      :env {
        :hot-reload false
      }}
  }

  :aliases{
    "start" ["do" "run"] ; start a development server
    "build" ["do" "clean," "deps," "compile"] ; clean and build code
  }

  ;; ----- Web Application -----

  :ring {
    :handler open-company-auth.core/app
    :reload-paths ["src"] ; work around issue https://github.com/weavejester/lein-ring/issues/68
    :port 3003
  }

  :resource-paths ["resources" ]

  :main open-company-auth.core
)
