(ns oc.auth.integration.static-test
  (:require [midje.sweet :refer :all]
            [cheshire.core :as json]
            [oc.auth.lib.utils :as test-utils]
            [oc.auth.lib.test-setup :as ts]))

(with-state-changes [(before :contents (ts/setup-system!))
                     (after :contents (ts/teardown-system!))]

  (facts "Test static endpoints"
    
    (fact "by requesting HATEOAS /"
      (let [resp (test-utils/api-request :get "/" {})]
        (:status resp) => 200))
    
    (fact "by requesting auth-settings anonymously"
      (let [resp (test-utils/api-request :get "/auth-settings" {})
            body (json/parse-string (:body resp))]
        (:status resp) => 200
        (test-utils/response-mime-type resp) => "application/json"
        (contains? body "slack") => true
        (contains? (body "slack") "basic-scopes-url") => true
        (contains? (body "slack") "refresh-url") => true
        (contains? body "email") => true
        (contains? (body "email") "refresh-url") => true))
    
    (future-fact "by requesting auth-settings with an invalid JWToken")

    (future-fact "by requesting auth-settings with an old JWToken")

    (future-fact "by requesting auth-settings with a Slack JWToken")

    (future-fact "by requesting auth-settings with an Email JWToken")

    (fact "by requesting token debugging with /test-token"
      (let [resp (test-utils/api-request :get "/test-token" {})
            body (json/parse-string (:body resp))]
        (:status resp) => 200
        (contains? body "jwt-token") => true
        (contains? body "jwt-verified") => true
        (contains? body "jwt-decoded") => true
        (body "jwt-verified") => true))))