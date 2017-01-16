(ns oc.auth.resources.team
  "Team stored in RethinkDB."
  (:require [oc.lib.rethinkdb.common :as common]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]))

;; ----- RethinkDB metadata -----

(def table-name "teams")
(def primary-key :team-id)

;; ----- Schema -----

(def Team {
  :team-id lib-schema/UniqueID
  (schema/optional-key :name) schema/Str
  :admins [lib-schema/UniqueID]
  :email-domains [lib-schema/NonBlankString]
  :slack-orgs [lib-schema/NonBlankString]
  :created-at lib-schema/ISO8601
  :updated-at lib-schema/ISO8601})