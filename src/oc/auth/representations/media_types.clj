(ns oc.auth.representations.media-types)

(def team-media-type "application/vnd.open-company.team.v1+json")
(def team-collection-media-type "application/vnd.collection+vnd.open-company.team+json;version=1")

(def admin-media-type "application/vnd.open-company.team.admin.v1")
(def invite-media-type "application/vnd.open-company.team.invite.v1")
(def email-domain-media-type "application/vnd.open-company.team.email-domain.v1+json")
(def slack-org-media-type "application/vnd.open-company.team.slack-org.v1")

(def user-media-type "application/vnd.open-company.user.v1+json")
(def user-collection-media-type "application/vnd.collection+vnd.open-company.user+json;version=1")

(def slack-channel-collection-media-type "application/vnd.collection+vnd.open-company.slack-channels+json;version=1")

(def payment-customer-media-type "application/vnd.open-company.customer.v1+json")
(def payment-checkout-session-media-type "application/vnd.open-company.checkout.session.v1+json")

(def expo-push-token-media-type "application/json")