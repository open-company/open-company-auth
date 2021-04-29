(ns oc.auth.resources.user
  "User stored in RethinkDB."
  (:require [clojure.string :as s]
            [clojure.set :as clj-set]
            [clojure.walk :refer (keywordize-keys)]
            [rethinkdb.query :as r]
            [defun.core :refer (defun)]
            [if-let.core :refer (when-let*)]
            [schema.core :as schema]
            [buddy.hashers :as hashers]
            [oc.lib.db.common :as db-common]
            [oc.lib.jwt :as jwt]
            [oc.lib.schema :as lib-schema]
            [oc.lib.html :as lib-html]
            [oc.auth.async.payments :as payments]
            [oc.auth.resources.team :as team-res]
            [oc.auth.config :as config]))

;; ----- RethinkDB metadata -----

(def table-name "users")
(def primary-key :user-id)

;; ----- Schema -----

(defn- allowed-name? [name]
  (and (string? name)
       (not (re-matches #".*\d.*" name)) ; don't allow any numeral
       (= (count name) (.codePointCount name 0 (count name))))) ; same # of characters as Unicode points

(def statuses
  "
  Possible user statuses:

  pending - awaiting invite response or email verification, can't login
  unverified - awaiting email verification, but can login
  active - Slack auth'd or verified email or invite
  "
  #{:pending :unverified :active})

(def mediums #{:email :slack :in-app})
(def digest-mediums #{:slack :email})
(defn digest-times
  "Possible values for times are:
  - empty set
  - all combinations of the allowed times"
  [times]
  (let [times-kw (set (map keyword times))]
    (clj-set/subset? times-kw config/premium-digest-times)))

(def DigestTimes
  (schema/maybe (schema/pred digest-times)))

(def TeamDigestDelivery
  {:team-id lib-schema/UniqueID
   :digest-times DigestTimes})

(def DigestDelivery
  [TeamDigestDelivery])

(def QSGChecklist
  {(schema/optional-key :should-show-qsg?) (schema/maybe schema/Bool)
   (schema/optional-key :show-guide?) (schema/maybe schema/Bool)
   (schema/optional-key :invited?) (schema/maybe schema/Bool)
   (schema/optional-key :add-post?) (schema/maybe schema/Bool)
   (schema/optional-key :add-reminder?) (schema/maybe schema/Bool)
   (schema/optional-key :add-section?) (schema/maybe schema/Bool)
   (schema/optional-key :see-digest-sample?) (schema/maybe schema/Bool)
   (schema/optional-key :slack-dismissed?) (schema/maybe schema/Bool)
   (schema/optional-key :carrot-video-dismissed?) (schema/maybe schema/Bool)
   (schema/optional-key :guide-dismissed?) (schema/maybe schema/Bool)
   (schema/optional-key :tooltip-shown?) (schema/maybe schema/Bool)})

(def UserTag schema/Keyword)

(def UserTags
  {(schema/optional-key :tags) (schema/maybe [UserTag])})

(def ^:private UserCommon
  (merge {:user-id lib-schema/UniqueID
          :teams [lib-schema/UniqueID]

          (schema/optional-key :one-time-token) lib-schema/UUIDStr
          (schema/optional-key :expo-push-tokens) [lib-schema/NonBlankStr]

          :email (schema/maybe lib-schema/EmailAddress)
          (schema/optional-key :password-hash) lib-schema/NonBlankStr

          :first-name (schema/pred allowed-name?)
          :last-name (schema/pred allowed-name?)
          :avatar-url (schema/maybe schema/Str)

          :notification-medium (schema/pred #(mediums (keyword %)))
          :reminder-medium (schema/pred #(mediums (keyword %)))
          ;; Digest
          :digest-medium (schema/pred #(digest-mediums (keyword %)))
          (schema/optional-key :digest-delivery) DigestDelivery
          (schema/optional-key :latest-digest-deliveries) [{:org-id lib-schema/UniqueID :timestamp lib-schema/ISO8601}]

          (schema/optional-key :last-token-at) lib-schema/ISO8601
          (schema/optional-key :qsg-checklist) QSGChecklist
          ;; User profile
          (schema/optional-key :title) (schema/maybe schema/Str)
          (schema/optional-key :timezone) (schema/maybe schema/Str) ; want it missing at first so we can default it on the client
          (schema/optional-key :blurb) (schema/maybe schema/Str)
          (schema/optional-key :location) (schema/maybe schema/Str)
          (schema/optional-key :profiles) {schema/Keyword schema/Str}}
          ;; Third party user's data
          lib-schema/SlackUsers
          lib-schema/GoogleUsers
          UserTags))

(def User "User resource as stored in the DB."
  (merge UserCommon {
    :status (schema/pred #(statuses (keyword %)))
    (schema/optional-key :slack-bots) (schema/maybe lib-schema/SlackBots)
    :created-at lib-schema/ISO8601
    :updated-at lib-schema/ISO8601
    (schema/optional-key :activated-at) (schema/maybe lib-schema/ISO8601)}))

(def UserRep "A representation of the user, suitable for creating a JWToken."
  (merge UserCommon {
    :admin (schema/conditional sequential? [lib-schema/UniqueID] :else schema/Bool)
    :premium-teams [lib-schema/UniqueID]
    (schema/optional-key :status) (schema/pred #(statuses (keyword %)))
    (schema/optional-key :slack-id) lib-schema/NonBlankStr
    (schema/optional-key :slack-token) lib-schema/NonBlankStr
    (schema/optional-key :slack-display-name) lib-schema/NonBlankStr
    (schema/optional-key :slack-bots) lib-schema/SlackBots
    (schema/optional-key :google-id) (schema/maybe schema/Str)
    (schema/optional-key :google-domain) (schema/maybe schema/Str)
    (schema/optional-key :google-token) (schema/maybe lib-schema/GoogleToken)
    (schema/optional-key :created-at) lib-schema/ISO8601
    (schema/optional-key :updated-at) lib-schema/ISO8601}))

(def OpenUserRep
  "A representation of the user open, suitable as input to the JWToken that will be cleaned at needs."
  (merge UserRep {schema/Any schema/Any}))

;; ----- Metadata -----

(def reserved-properties
  "Properties of a resource that can't be specified during a create or update."
  #{:user-id :password :password-hash :created-at :udpated-at :activated-at :links :slack-display-name :latest-digest-deliveries})

(def ignored-properties
  "Properties of a resource that are ignored during an update."
  (clojure.set/union reserved-properties #{:teams :one-time-token :status :email}))

;; ----- Utility functions -----


(defun parse-tags
  "Given a user map or a list of them, make sure all the items in the :tags field are keywords."
  ([user :guard :user-id]
   (if (:tags user)
     (update user :tags #(mapv keyword %))
     user))
  ([users :guard sequential?]
   (map parse-tags users))
  ([not-a-user]
   not-a-user))

(defn clean-prop [user-data clean-key]
  (if (get user-data clean-key)
    (update user-data clean-key #(or (lib-html/strip-xss-tags %) ""))
    user-data))

(defn clean-input [user]
  (-> user
      (clean-prop :first-name)
      (clean-prop :last-name)))

(defn clean-props
  "Remove any reserved properties from the user."
  [user]
  (apply dissoc user reserved-properties))

(defn ignore-props
  "Remove any ignored properties from the user."
  [user]
  (apply dissoc user ignored-properties))

(defn- additional-keys? [additional-keys]
  (and (sequential? additional-keys)
       (every? #(or (string? %) (keyword? %)) additional-keys)))

;; ----- Password based authentication -----

(def ^:private crypto-algo :bcrypt+sha512)
(def ^:private trusted-algs #{:bcrypt+sha512}) ; if we change algos (above), add the additional algo to this list
(def ^:private crypto-algo-iterations 12)

(defn- password-hash
  "
  Create a crypto hash from the provided password as a string in the format:

  <algorithm used>$<salt used>$<password hash>
  "
  [password]
  (hashers/derive password {:alg crypto-algo :iterations crypto-algo-iterations}))

(defn password-match?
  "
  Return true if the provided password hashes to the provided password hash. The provided password hash is in
  the format:

  <algorithm used>$<salt used>$<password hash>
  "
  [password current-password-hash]
  (if (s/blank? current-password-hash)
    false
    (hashers/check password current-password-hash {:limit trusted-algs})))

(declare get-user-by-email)
(defn authenticate? [conn email password]
  (if-let [user (get-user-by-email conn email)]
    (password-match? password (:password-hash user))
    false))

;; ----- User CRUD -----

(def user-images
 ["/img/ML/happy_face_red.svg"
  "/img/ML/happy_face_green.svg"
  "/img/ML/happy_face_blue.svg"
  "/img/ML/happy_face_purple.svg"
  "/img/ML/happy_face_yellow.svg"])

(defn random-user-image []
  (first (shuffle (vec user-images))))

(defn- digest-delivery-for-team [team-id]
  {:team-id team-id
   :digest-times [config/default-digest-time]})

(schema/defn ^:always-validate ->user :- User
  "Take a minimal map describing a user and 'fill the blanks' with any missing properties."
  ([user-props password :- lib-schema/NonBlankStr]
    (-> (->user user-props)
      (parse-tags)
      (assoc :password-hash (password-hash password)) ; add hashed password
      (assoc :one-time-token (str (java.util.UUID/randomUUID))))) ; add one-time-token for email verification

  ([user-props]
  {:pre [(map? user-props)]}
  (let [ts (db-common/current-timestamp)]
    (-> user-props
        keywordize-keys
        clean-props
        (clean-input)
        (parse-tags)
        (assoc :user-id (db-common/unique-id))
        (update :teams #(or % []))
        (update :email #(or % ""))
        (update :first-name #(or % ""))
        (update :last-name #(or % ""))
        (update :avatar-url #(or % (random-user-image)))
        (update :digest-medium #(or % :email)) ; lowest common denominator
        (update :notification-medium #(or % :email)) ; lowest common denominator
        (update :reminder-medium #(or % :email)) ; lowest common denominator
        (update :digest-delivery #(cond (seq %)
                                        %
                                        (seq (:teams user-props))
                                        (mapv digest-delivery-for-team (:teams user-props))
                                        :else
                                        []))
        (assoc :status :pending)
        (assoc :created-at ts)
        (assoc :updated-at ts)))))

(defn nux-tags-for-user
  ([user-map]
   (nux-tags-for-user user-map nil))
  ([user-map invitation]
   (cond (and (map? invitation)
              (seq (:user-type invitation)))
         [(keyword (str "nux-" (:user-type invitation)))]
         (seq (:teams user-map))
         [:nux-author]
         :else
         [:nux-first-user])))

(declare tags!)
(schema/defn ^:always-validate create-user!
  "Create a user in the system. Throws a runtime exception if user doesn't conform to the User schema."
  ([conn user :- User]
   {:pre [(db-common/conn? conn)]}
   (create-user! conn user nil (nux-tags-for-user user)))
  ([conn user :- User invite-token-team-id :- (schema/maybe lib-schema/UniqueID)]
   {:pre [(db-common/conn? conn)]}
   (let [tmp-user (update user :teams #(->> (concat % [invite-token-team-id])
                                            (remove nil?)
                                            distinct
                                            vec))
         nux-tag (nux-tags-for-user tmp-user)]
     (create-user! conn user invite-token-team-id nux-tag)))
  ([conn user :- User invite-token-team-id :- (schema/maybe lib-schema/UniqueID) tags]
   {:pre [(db-common/conn? conn)]}
   (let [email (:email user)
         email-domain (last (s/split email #"\@"))
         email-teams (mapv :team-id (team-res/list-teams-by-index conn :email-domains email-domain))
         existing-teams (vec (set (concat email-teams (:teams user))))
         with-invite-token-teams (if invite-token-team-id
                                   (vec (set (conj existing-teams invite-token-team-id)))
                                   existing-teams)
         new-team (when (empty? with-invite-token-teams)
                    (team-res/create-team! conn (team-res/->team {} (:user-id user))))
         teams (if new-team [(:team-id new-team)] with-invite-token-teams)
         user-with-teams (assoc user :teams teams)
         user-with-status (if (or ;; user is creating a new team, no need to pre-verify
                                  new-team
                                  ;; User is signing in via invite token
                                  (and invite-token-team-id
                                       ;; or has no related teams for email domain
                                       (or (empty? email-teams)
                                           ;; or the only email domain team is the same of the invite token
                                           (and (= (count email-teams) 1)
                                                (= (first email-teams) invite-token-team-id)))))
                            (assoc user-with-teams :status :unverified ; let user in even if not verified
                                                   :activated-at (or (:activated-at user-with-teams)
                                                                     (db-common/current-timestamp)))
                            user-with-teams)
         old-digest-delivery (:digest-delivery user)
         missing-digest-delivery-teams (clj-set/difference (set teams) (set (:teams user)))
         missing-digest-delivery (map digest-delivery-for-team (vec missing-digest-delivery-teams))
         new-digest-delivery (concat old-digest-delivery missing-digest-delivery)
         user-with-digest-delivery (assoc user-with-status :digest-delivery new-digest-delivery)
         created-user (->> (db-common/current-timestamp)
                           (db-common/create-resource conn table-name user-with-digest-delivery)
                           (tags! conn tags)
                           (parse-tags))]
     (payments/report-all-seat-usage! conn (:teams user))
     created-user)))

(schema/defn ^:always-validate get-user :- (schema/maybe User)
  "Given the user-id of the user, retrieve them from the database, or return nil if they don't exist."
  [conn user-id :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (parse-tags (db-common/read-resource conn table-name user-id)))

(schema/defn ^:always-validate get-user-by-email :- (schema/maybe User)
  "Given the email address of the user, retrieve them from the database, or return nil if user doesn't exist."
  [conn email :- lib-schema/NonBlankStr]
  {:pre [(db-common/conn? conn)]}
  (->> (clojure.string/lower-case email)
       (db-common/read-resources conn table-name "loweremails")
       (first)
       (parse-tags)))

(schema/defn ^:always-validate get-user-by-token :- (schema/maybe User)
  "Given the one-time-use token of the user, retrieve them from the database, or return nil if user doesn't exist."
  [conn token :- lib-schema/NonBlankStr]
  {:pre [(db-common/conn? conn)]}
  (-> (db-common/read-resources conn table-name "one-time-token" token)
      (first)
      (parse-tags)))

(schema/defn ^:always-validate get-user-by-slack-id :- (schema/maybe User)
  "Given the slack id of the user, retrieve them from the database, or return nil if user doesn't exist."
  [conn slack-team-id :- lib-schema/NonBlankStr slack-id :- lib-schema/NonBlankStr]
  {:pre [(db-common/conn? conn)]}
  (-> (db-common/read-resources conn table-name "user-slack-team-id" [[slack-team-id, slack-id]])
      (first)
      (parse-tags)))

(schema/defn ^:always-validate update-user! :- User
  "
  Given an updated user property map, update the user and return the updated user on success.

  Throws a runtime exception if the merge of the prior user and the updated user property map doesn't conform
  to the User schema.

  NOTE: doesn't update teams, see: `add-team`, `remove-team`
  NOTE: doesn't update one-time token, see: `add-token`, `remove-token`
  NOTE: doesn't handle case of user-id change.
  NOTE: doesn't handle case of status change, see: `activate!`, `unverify!`
  "
  [conn :- lib-schema/Conn user-id :- lib-schema/UniqueID user]
  {:pre [(map? user)]}
  (when-let [original-user (get-user conn user-id)]
    (let [updated-password (:password user)
          hashed-password (when-not (s/blank? updated-password) (password-hash updated-password))
          cleaned-user-data (-> user
                                ignore-props
                                clean-input)
          updated-user (merge original-user cleaned-user-data)
          final-user (if hashed-password (assoc updated-user :password-hash hashed-password) updated-user)]
      (schema/validate User final-user)
      (parse-tags (db-common/update-resource conn table-name primary-key original-user final-user)))))

(schema/defn ^:always-validate activate!
  "Update the user's status to 'active'. Returns the updated user."
  [conn :- lib-schema/Conn user-id :- lib-schema/UniqueID]
  (if-let [original-user (get-user conn user-id)]
    (parse-tags (db-common/update-resource conn table-name primary-key original-user (assoc original-user :status :active
                                                                                                          :activated-at (or (:activated-at original-user)
                                                                                                                            (db-common/current-timestamp)))))
    false))

(schema/defn ^:always-validate  delete-user!
  "Given the user-id of the user, delete it and return `true` on success."
  [conn :- lib-schema/Conn user-id :- lib-schema/UniqueID]
  (try
    ;; Remove admin roles
    (doseq [team-id (jwt/admin-of conn user-id)]
      (team-res/remove-admin conn team-id user-id))
    (let [original-user (get-user conn user-id)
          ;; Remove user
          removed-user (parse-tags (db-common/delete-resource conn table-name user-id))]
      (payments/report-all-seat-usage! conn (:teams original-user))
      removed-user)
    (catch java.lang.RuntimeException _))) ; it's OK if there is no user to delete

(schema/defn ^:always-validate add-token :- (schema/maybe User)
  "
  Given the user-id of the user, and a one-time use token, add the token to the user if they exist.
  Returns the updated user on success, nil on non-existence, and a RethinkDB error map on other errors.
  "
  ([conn user-id :- lib-schema/UniqueID] (add-token conn user-id (str (java.util.UUID/randomUUID))))

  ([conn :- lib-schema/Conn user-id :- lib-schema/UniqueID token :- lib-schema/UUIDStr]
  (when-let [user (get-user conn user-id)]
    (->> (assoc user :one-time-token token)
         (db-common/update-resource conn table-name primary-key user)
         (parse-tags)))))

(schema/defn ^:always-validate remove-token :- (schema/maybe User)
  "
  Given the user-id of the user, and a one-time use token, remove the token from the user if they exist.
  Returns the updated user on success, nil on non-existence, and a RethinkDB error map on other errors.
  "
  [conn :- lib-schema/Conn user-id :- lib-schema/UniqueID]
  (when (get-user conn user-id)
    (parse-tags (db-common/remove-property conn table-name user-id "one-time-token"))))

;; ----- User's set operations -----

(schema/defn ^:always-validate add-team :- (schema/maybe User)
  "
  Given the user-id of the user, and the team-id of the team, add the user to the team if they both exist.
  Returns the updated user on success, nil on non-existence, and a RethinkDB error map on other errors.
  "
  [conn user-id :- lib-schema/UniqueID team-id :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (when-let* [user (get-user conn user-id)
            team (team-res/get-team conn team-id)]
    (let [filtered-digest-delivery (filter #(not= (:team-id %) team-id) (:digest-delivery user))
          old-digest-times (some #(when (= (:team-id %) team-id) %) (:digest-delivery user))
          team-digest-delivery (or old-digest-times
                                   (digest-delivery-for-team team-id))
          digest-delivery (vec (conj filtered-digest-delivery team-digest-delivery))]
      ;; Add a digest-delivery map for the new team
      (db-common/update-resource conn table-name primary-key user (assoc user :digest-delivery digest-delivery))
      (payments/report-team-seat-usage! conn team-id)
      (parse-tags (db-common/add-to-set conn table-name user-id "teams" team-id)))))

(schema/defn ^:always-validate remove-team :- (schema/maybe User)
  "
  Given the user-id of the user, and the team-id of the team, remove the user from the team if they exist.
  Returns the updated user on success, nil on non-existence, and a RethinkDB error map on other errors.
  "
  [conn user-id :- lib-schema/UniqueID team-id :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (when-let [user (get-user conn user-id)]
    (let [filtered-digest-delivery (filterv #(not= (:team-id %) team-id) (:digest-delivery user))]
      ;; Remove digest delivery preference for the removed team
      (db-common/update-resource conn table-name primary-key user (assoc user :digest-delivery filtered-digest-delivery))
      (payments/report-team-seat-usage! conn team-id)
      (parse-tags (db-common/remove-from-set conn table-name user-id "teams" team-id)))))

(defn admin-of [conn user-id] (jwt/admin-of conn user-id)) ; alias

(schema/defn ^:always-validate premium-teams :- [lib-schema/UniqueID]
  [conn :- lib-schema/Conn user-id :- lib-schema/UniqueID]
  (jwt/premium-teams conn user-id))

;; ----- User Tag manager ----

(schema/defn tag! :- (schema/maybe User)
  [conn :- lib-schema/Conn user-id :- lib-schema/UniqueID tag :- UserTag]
  (when (get-user conn user-id)
    (parse-tags (db-common/add-to-set conn table-name user-id "tags" tag))))

(schema/defn tags! :- (schema/maybe User)
  [conn :- lib-schema/Conn tags :- [UserTag] user :- User]
  (if-not (seq tags)
    user
    (do
      (doseq [tag tags]
        (tag! conn (:user-id user) tag))
      (parse-tags (get-user conn (:user-id user))))))

(schema/defn untag! :- (schema/maybe User)
  [conn :- lib-schema/Conn user-id :- lib-schema/UniqueID tag :- UserTag]
  (when (get-user conn user-id)
    (parse-tags (db-common/remove-from-set conn table-name user-id "tags" tag))))

(schema/defn untags! :- (schema/maybe User)
  [conn :- lib-schema/Conn tags :- [UserTag] user :- User]
  (if-not (seq tags)
    user
    (do
      (doseq [tag tags]
        (untag! conn (:user-id user) tag))
      (parse-tags (get-user conn (:user-id user))))))

(schema/defn tag-all-users!
  ([conn :- lib-schema/Conn tag :- UserTag] (tag-all-users! conn tag nil))
  ([conn :- lib-schema/Conn tag :- UserTag filter-fn]
   (as-> (r/table table-name) query
       (if (fn? filter-fn)
         (filter-fn query)
         query)
       (r/update query (r/fn [u]
                        {:tags (-> (r/get-field u :tags)
                                    (r/default [])
                                    (r/set-insert tag))}))
       (r/run query conn))))

(schema/defn tag-all-active-users!
  [conn :- lib-schema/Conn tag :- UserTag]
  (tag-all-users! conn tag
                  #(r/filter % (r/fn [u]
                                 (r/and (r/has-fields u [:last-token-at])
                                        (r/contains ["active" "unverified"] (r/get-field u [:status])))))))

(schema/defn untag-all-users!
  ([conn :- lib-schema/Conn tag :- UserTag] (untag-all-users! conn tag nil))
  ([conn :- lib-schema/Conn tag :- UserTag filter-fn]
   (as-> (r/table table-name) query
         (if (fn? filter-fn)
           (filter-fn query)
           query)
         (r/update query (r/fn [u]
                           {:tags (-> (r/get-field u :tags)
                                     (r/default [])
                                     (r/set-difference [tag]))}))
         (r/run query conn))))

;; ----- Collection of users -----

(defun list-users
  "
  Given an optional team-id, return a list of users.

  Additional fields can be optionally specified."
  ([conn] (list-users conn []))

  ([conn :guard db-common/conn?
    additional-keys :guard additional-keys?]
   (parse-tags
    (db-common/read-resources conn table-name
                              (concat additional-keys [:user-id :email :status :first-name :last-name :avatar-url :teams]))))

  ([conn team-id] (list-users conn team-id []))

  ([conn :guard db-common/conn?
    team-id :guard #(schema/validate lib-schema/UniqueID %)
    additional-keys :guard additional-keys?]
   (parse-tags
    (db-common/read-resources-in-order conn table-name :teams team-id
                                       (concat additional-keys [:user-id :email :status :first-name :last-name :avatar-url :teams])))))

;; ----- Armageddon -----

(defn delete-all-users!
  "Use with caution! Failure can result in partial deletes. Returns `true` if successful."
  [conn]
  {:pre [(db-common/conn? conn)]}
  (db-common/delete-all-resources! conn table-name))
