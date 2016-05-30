(ns open-company-auth.store
  (:require [alandipert.enduro :as end]
            [amazonica.aws.s3 :as s3]
            [open-company-auth.config :as config]
            [taoensso.timbre :as timbre]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

(defn- get-value [creds bucket key]
  (-> (s3/get-object creds bucket key) :input-stream slurp edn/read-string))

(defn- store-value [creds bucket key value]
  (time
   (let [str-val (pr-str value)]
     (s3/put-object creds bucket key
                    (io/input-stream (.getBytes str-val))
                    {:content-length (count str-val)}))))

(deftype S3Backend [creds bucket key]
  end/IDurableBackend
  (-commit! [this value]
    (store-value creds bucket key value))
  (-remove! [this]
    (s3/delete-object creds bucket key)))

(defn s3-atom
  #=(end/with-options-doc "Creates and returns a S3-backed atom.
  If the location denoted by the combination of bucket and key exists,
  it is read and becomes the initial value.
  Otherwise, the initial value is init and the bucket denoted by table-name is updated.")
  [init aws-creds bucket key & opts]
  (end/atom*
   (or (and (s3/does-object-exist aws-creds bucket key)
            (get-value aws-creds bucket key))
       (do
         (store-value aws-creds bucket key init)
         init))
   (S3Backend. aws-creds bucket key)
   (apply hash-map opts)))

(defonce db
  (delay
   (s3-atom
    {}
    {:access-key config/aws-access-key-id
     :secret-key config/aws-secret-access-key}
    config/secrets-bucket
    config/secrets-key)))

(defn store! [k v]
  (if (= v (get @@db k))
    (timbre/infof "Same value for %s already stored: %s" k v)
    (do (timbre/info "Storing:" k v)
        (end/swap! @db assoc k v))))

(defn retrieve [& ks]
  (if-let [v (get-in @@db ks)]
    (do (timbre/info "Retrieved secrets for" ks) v)
    (timbre/info "No secrets found for" ks)))

(comment
  (def aws-credentials {:access-key config/aws-access-key-id
                        :secret-key config/aws-secret-access-key})

  (def x (s3-atom {:hello :world} aws-credentials config/secrets-bucket "store"))

  (deref x)

  (time (end/swap! x assoc :my "pleasure")))