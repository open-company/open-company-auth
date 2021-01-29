(ns oc.auth.api.entry-point
  "Liberator API for HATEOAS entry point to auth service."
  (:require [compojure.core :as compojure :refer (GET OPTIONS)]
            [liberator.core :refer (defresource)]
            [cheshire.core :as json]
            [oc.lib.db.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.lib.schema :as lib-schema]
            [oc.auth.config :as config]
            [oc.auth.representations.user :as user-rep]
            [oc.auth.representations.team :as team-rep]
            [oc.auth.resources.user :as user-res]
            [oc.auth.resources.team :as team-res]
            [oc.auth.representations.email-auth :as email-auth]
            [oc.auth.representations.google-auth :as google-auth]
            [oc.auth.representations.slack-auth :as slack-auth]))

;; ----- Representations -----

(defn- render-entry-point [conn {:keys [user invite-token-team] :as _ctx}]
  (cond
    invite-token-team
    (json/generate-string
      (team-rep/invite-token-settings invite-token-team)
      {:pretty config/pretty?})
    user
    ;; auth'd settings
    (json/generate-string
      (user-rep/authed-settings (merge user (user-res/get-user conn (:user-id user))))
      {:pretty config/pretty?})
    :else
    ;; not auth'd, give them both email and Slack settings
    (json/generate-string
      {:links (conj (concat email-auth/auth-settings
                            slack-auth/auth-settings
                            google-auth/auth-settings)
                    user-rep/refresh-link)}
      {:pretty config/pretty?})))

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

(defresource entry-point [conn]
  ;; Extend the usual anonymous resource to allow auth via team invite-token
  :initialize-context (fn [ctx]
                        (let [bearer (-> ctx :request :headers api-common/get-token)
                              is-team-token? (lib-schema/valid? lib-schema/UUIDStr bearer)
                              jwtoken (when-not is-team-token? (api-common/read-token (:request ctx) config/passphrase))]
                          (if is-team-token?
                            {:jwtoken false
                             :invite-token bearer}
                            jwtoken)))
  :authorized? (fn [ctx]
                (if (= (-> ctx :request :request-method) :options)
                  true ; allows allow options
                  (let [invite-token (:invite-token ctx)
                        team (when invite-token (team-res/get-team-by-invite-token conn invite-token))]
                    (cond
                      ;; Return the team associated with the invite token if it exists
                      (and invite-token team)
                      {:invite-token-team team}
                      ;; Return false if the team is not to return a 401 to the client
                      (and invite-token (not team))
                      false
                      ;; Else do the usual
                      :else
                      (api-common/allow-anonymous ctx)))))
  :handle-unauthorized api-common/handle-unauthorized
  :handle-forbidden  (fn [ctx] (if (:jwtoken ctx) (api-common/forbidden-response) (api-common/unauthorized-response)))

  :allowed-methods [:options :get]

  :allowed? (fn [ctx] (api-common/allow-anonymous ctx))
  
  ;; Media type client accepts
  :available-media-types ["application/json"]
  :handle-not-acceptable (fn [_] (api-common/only-accept 406 "application/json"))

  ;; Exceptions handling
  :handle-exception api-common/handle-exception

  ;; Responses
  :handle-ok (fn [ctx] (render-entry-point conn ctx)))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
     (OPTIONS "/" [] (pool/with-pool [conn db-pool] (entry-point conn)))
     (GET "/" [] (pool/with-pool [conn db-pool] (entry-point conn))))))