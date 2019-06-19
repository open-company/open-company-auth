(ns oc.auth.lib.oauth
  (:require [clojure.edn :as edn])
  (:import [java.util Base64]))

(defn encode-state-string
  [data]
  (let [encoder    (Base64/getUrlEncoder)
        data-bytes (-> data pr-str .getBytes)]
    (.encodeToString encoder data-bytes)))

(defn decode-state-string
  [state-str]
  (let [decoder       (Base64/getUrlDecoder)
        decoded-bytes (.decode decoder state-str)
        decoded-str   (String. decoded-bytes)]
    (edn/read-string decoded-str)))
