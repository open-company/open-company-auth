(ns oc.auth.util.jwtoken
  "Commandline client to create a JWToken for OpenCompany API use."
  (:require [clojure.string :as s]
            [clj-time.core :as t]
            [clj-time.format :as format]
            [clojure.tools.cli :refer (parse-opts)]
            [oc.auth.config :as config]
            [oc.lib.jwt :as jwt])
  (:gen-class))

(def cli-options
  [["-h" "--help"]])

(defn usage [options-summary]
  (s/join \newline
     ["This program creates an OpenCompany JWToken for command-line API usage (cURL)."
      ""
      "Usage: lein run -m oc.auth.util.jwtoken -- ./opt/identities/camus.edn"
      ""
      "Identity data: an EDN file with the user properties."
      ""
      "Please refer to the file(s) in ./opt/identities for more information on the file format."
      ""]))

(defn data-msg []
  "\nThe data file must be an EDN file with identity information.\n")

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (s/join \newline errors)))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    ;; Handle help and error conditions
    (cond
      (:help options) (exit 0 (usage summary))
      (not= (count arguments) 1) (exit 1 (usage summary))
      errors (exit 1 (error-msg errors)))
    (try
      (let [payload (assoc (read-string (slurp (first arguments)))
                      :expire (format/unparse (format/formatters :date-time) (t/plus (t/now) (t/years 20))))
            token (jwt/generate payload config/passphrase)]
        (println "\nPassphrase:" config/passphrase)
        (println "\nIdentity:" payload)
        (println "\nJWToken:" token)
        (println (str "\nTo use this JWToken from cURL:\ncurl --header \"Authorization: Bearer " token "\"\n")))
      (catch Exception e
        (println e)
        (exit 1 (data-msg))))))