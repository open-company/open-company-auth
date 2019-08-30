(ns oc.auth.lib.oauth
  (:require [clojure.edn :as edn])
  (:import [java.util Base64]
           [java.net URLDecoder]
           [java.nio.charset StandardCharsets]))

(defn encode-state-string
  [data]
  (let [encoder    (Base64/getUrlEncoder)
        data-bytes (-> data pr-str .getBytes)]
    (.encodeToString encoder data-bytes)))

(defn decode-state-string
  [state-str]
  (let [url-decoded   (URLDecoder/decode state-str (.name StandardCharsets/UTF_8))
        b64-decoder   (Base64/getDecoder)
        decoded-bytes (.decode b64-decoder url-decoded)
        decoded-str   (String. decoded-bytes)]
    (edn/read-string decoded-str)))
