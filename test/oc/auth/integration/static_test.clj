(ns oc.auth.integration.static-test
  (:require [midje.sweet :refer :all]
            [cheshire.core :as json]
            [oc.auth.lib.utils :as test-utils]
            [oc.auth.lib.test-setup :as ts]))

(with-state-changes [(before :contents (ts/setup-system!))
                     (after :contents (ts/teardown-system!))]

  (facts "Test static endpoints"
    
    (fact "By pinging"
      (let [resp (test-utils/api-request :get "/ping" {})]
        (:status resp) => 200))

    (facts "By testing errors"
      (test-utils/api-request :get "/---error-test---" {}) => (throws Exception)
      (let [resp (test-utils/api-request :get "/---500-test---" {})]
        (:status resp) => 500))
    
    (fact "By requesting auth-settings anonymously"
      (let [resp (test-utils/api-request :get "/" {})
            body (json/parse-string (:body resp))]
        (:status resp) => 200
        (test-utils/response-mime-type resp) => "application/json"
        (contains? body "links") => true
        ; (contains? (body "slack") "links") => true
        ; (contains? body "email") => true
        ; (contains? (body "email") "links") => true)
        ))
    
    (future-fact "by requesting auth-settings with an invalid JWToken")

    (future-fact "by requesting auth-settings with an old JWToken")

    (future-fact "by requesting auth-settings with a Slack JWToken")

    (future-fact "by requesting auth-settings with an Email JWToken")))