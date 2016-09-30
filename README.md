# [OpenCompany](https://opencompany.com/) Authentication Service

[![MPL License](http://img.shields.io/badge/license-MPL-blue.svg?style=flat)](https://www.mozilla.org/MPL/2.0/)
[![Build Status](https://travis-ci.org/open-company/open-company-auth.svg)](https://travis-ci.org/open-company/open-company-auth)
[![Dependency Status](https://www.versioneye.com/user/projects/562129c236d0ab0021000a0e/badge.svg?style=flat)](https://www.versioneye.com/user/projects/562129c236d0ab0021000a0e)
[![Roadmap on Trello](http://img.shields.io/badge/roadmap-trello-blue.svg?style=flat)](https://trello.com/b/3naVWHgZ/open-company-development)

## Background

> I've come to learn there is a virtuous cycle to transparency and a very vicious cycle of obfuscation.

> -- [Jeff Weiner](https://www.linkedin.com/in/jeffweiner08)

Employees and investors, co-founders and execs, they all want more transparency from their startups, but there's no consensus about what it means to be transparent. OpenCompany is a platform that simplifies how key business information is shared with stakeholders.

When information about growth, finances, ownership and challenges is shared transparently, it inspires trust, new ideas and new levels of stakeholder engagement. OpenCompany makes it easy for founders to engage with employees and investors, creating a sense of ownership and urgency for everyone.

[OpenCompany](https://opencompany.com/) is GitHub for the rest of your company.

To maintain transparency, OpenCompany information is always accessible and easy to find. Being able to search or flip through prior updates empowers everyone. Historical context brings new employees and investors up to speed, refreshes memories, and shows how the company is evolving over time.

Transparency expectations are changing. Startups need to change as well if they are going to attract and retain savvy employees and investors. Just as open source changed the way we build software, transparency changes how we build successful startups with information that is open, interactive, and always accessible. The OpenCompany platform turns transparency into a competitive advantage.

Like the open companies we promote and support, the [OpenCompany](https://opencompany.com/) platform is completely transparent. The company supporting this effort, OpenCompany, LLC, is an open company. The [platform](https://github.com/open-company/open-company-web) is open source software, and open company data is [open data](https://en.wikipedia.org/wiki/Open_data) accessible through the [platform API](https://github.com/open-company/open-company-api).

To get started, head to: [OpenCompany](https://opencompany.com/)


## Overview

The OpenCompany Authentication Service handles authenticating users against Slack and email/pass, and creates a [JSON Web Token](https://jwt.io/) for them, which can then be used with the other OpenCompany services to assert the users identity.


## Local Setup

Users of the [OpenCompany](https://opencompany.com/) platform should get started by going to [OpenCompany](https://opencompany.com/). The following local setup is for developers wanting to work on the platform's Authentication software.

Most of the dependencies are internal, meaning [Leiningen](https://github.com/technomancy/leiningen) will handle getting them for you. There are a few exceptions:

* [Java 8](http://www.oracle.com/technetwork/java/javase/downloads/index.html) - a Java 8 JRE is needed to run Clojure
* [Leiningen](https://github.com/technomancy/leiningen) - Clojure's build and dependency management tool
* [RethinkDB](http://rethinkdb.com/) v2.3.5+ - a multi-modal (document, key/value, relational) open source NoSQL database

#### Java

Chances are your system already has Java 8+ installed. You can verify this with:

```console
java -version
```

If you do not have Java 8+ [download it](http://www.oracle.com/technetwork/java/javase/downloads/index.html) and follow the installation instructions.

#### Leiningen

Leiningen is easy to install:

1. Download the latest [lein script from the stable branch](https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein).
1. Place it somewhere that's on your $PATH (`env | grep PATH`). `/usr/local/bin` is a good choice if it is on your PATH.
1. Set it to be executable. `chmod 755 /usr/local/bin/lein`
1. Run it: `lein` This will finish the installation.

Then let Leiningen install the rest of the dependencies:

```console
git clone https://github.com/open-company/open-company-api.git
cd open-company-api
lein deps
```

#### RethinkDB

RethinkDB is easy to install with official and community supported packages for most operating systems.

##### RethinkDB for Mac OS X via Brew

Assuming you are running Mac OS X and are a [Homebrew](http://mxcl.github.com/homebrew/) user, use brew to install RethinkDB:

```console
brew update && brew install rethinkdb
```

If you already have RethinkDB installed via brew, check the version:

```console
rethinkdb -v
```

If it's older, then upgrade it with:

```console
brew update && brew upgrade rethinkdb && brew services restart rethinkdb
```


Follow the instructions provided by brew to run RethinkDB every time at login:

```console
ln -sfv /usr/local/opt/rethinkdb/*.plist ~/Library/LaunchAgents
```

And to run RethinkDB now:

```console
launchctl load ~/Library/LaunchAgents/homebrew.mxcl.rethinkdb.plist
```

Verify you can access the RethinkDB admin console:

```console
open http://localhost:8080/
```

After installing with brew:

* Your RethinkDB binary will be at `/usr/local/bin/rethinkdb`
* Your RethinkDB data directory will be at `/usr/local/var/rethinkdb`
* Your RethinkDB log will be at `/usr/local/var/log/rethinkdb/rethinkdb.log`
* Your RethinkDB launchd file will be at `~/Library/LaunchAgents/homebrew.mxcl.rethinkdb.plist`

##### RethinkDB for Mac OS X (Binary Package)

If you don't use brew, there is a binary installer package available for Mac OS X from the [Mac download page](http://rethinkdb.com/docs/install/osx/).

After downloading the disk image, mounting it (double click) and running the rethinkdb.pkg installer, you need to manually create the data directory:

```console
sudo mkdir -p /Library/RethinkDB
sudo chown <your-own-user-id> /Library/RethinkDB
mkdir /Library/RethinkDB/data
```

And you will need to manually create the launchd config file to run RethinkDB every time at login. From within this repo run:

```console
cp ./opt/com.rethinkdb.server.plist ~/Library/LaunchAgents/com.rethinkdb.server.plist
```

And to run RethinkDB now:

```console
launchctl load ~/Library/LaunchAgents/com.rethinkdb.server.plist
```

Verify you can access the RethinkDB admin console:

```console
open http://localhost:8080/
```

After installing with the binary package:

* Your RethinkDB binary will be at `/usr/local/bin/rethinkdb`
* Your RethinkDB data directory will be at `/Library/RethinkDB/data`
* Your RethinkDB log will be at `/var/log/rethinkdb.log`
* Your RethinkDB launchd file will be at `~/Library/LaunchAgents/com.rethinkdb.server.plist`


##### RethinkDB for Linux

If you run Linux on your development environment (good for you, hardcore!) you can get a package for you distribution or compile from source. Details are on the [installation page](http://rethinkdb.com/docs/install/).

##### RethinkDB for Windows

RethinkDB [isn't supported on Windows](https://github.com/rethinkdb/rethinkdb/issues/1100) directly. If you are stuck on Windows, you can run Linux in a virtualized environment to host RethinkDB.

#### Required Secrets

A secret is shared between the [OpenCompany API](https://github.com/open-company/open-company-api) and the Auth service for creating and validating [JSON Web Tokens](https://jwt.io/).

A [Slack App](https://api.slack.com/apps) needs to be created for OAuth authentication. For local development, create a Slack app with a Redirect URI of `http://localhost:3003/slack-oauth` and get the client ID and secret from the Slack app you create.

An [AWS S3](https://aws.amazon.com/s3/) bucket is used to cache bot tokens. Setup an S3 bucket and key/secret access to the bucket using the AWS Web Console or API.

Make sure you update the section in `project.clj` that looks like this to contain your actual JWT, Slack, and AWS S3 secrets:

```clojure
;; Dev environment and dependencies
:dev [:qa {
  :env ^:replace {
    :open-company-auth-passphrase "this_is_a_dev_secret" ; JWT secret
    :open-company-slack-client-id "CHANGE-ME"
    :open-company-slack-client-secret "CHANGE-ME"
    :aws-access-key-id "CHANGE-ME"
    :aws-secret-access-key "CHANGE-ME"
    :aws-secrets-bucket "CHANGE-ME"
  }
```

You can also override these settings with environmental variables in the form of `OPEN_COMPANY_AUTH_PASSPHRASE` and
`AWS_ACCESS_KEY_ID`, etc. Use environmental variables to provide production secrets when running in production.

## Usage

Users of the [OpenCompany](https://opencompany.com/) platform should get started by going to [OpenCompany](https://opencompany.com/). The following usage is for developers wanting to work on the platform's Auth application software.

**Make sure you've updated `project.clj` as described above.**

To start a production Auth server:

```console
lein start!
```

Or to start a development Auth server:

```console
lein start
```

Open your browser to [http://localhost:3003/test-token](http://localhost:3003/test-token) and check that it's working and the JWT works.

To clean all compiled files:

```console
lein clean
```

To create a production build run:

```console
lein build
```

### Sample JWToken

To create a sample [JSON Web Token](https://jwt.io/) for use in development without going through a full auth cycle, create an identity EDN file
formated like the ones in ```/opt/identities``` or use one of the identity EDN files provided, and run the utility:

```console
lein run -m oc.auth.util.jwtoken -- ./opt/identities/camus.edn
```

## Technical Design

### Authentication

Users can onboard and authenticate using Slack or email/password as an authentication option. Authentication via Slack
uses Slack's OAuth provider. Authentication via email uses this OpenCompany Auth Service and the
[Buddy](https://github.com/funcool/buddy) security library.

### Authorization

A Slack organization provides a built-in "in-group" of users, all the users that have access to the Slack
organization. The Slack organization works as the OpenCompany `org-id` that authorizes users to the company. The
OpenCompany `org-id` is assigned to the Slack organization ID of the creator of the company at creation time.
Subsequently anyone with that same Slack organization ID in their JWToken can access the company.

Using email for authentication complicates authorization a bit. In some cases for email, the email domain
(e.g. @opencompany.com) acts as the same sort of "in-group". This is not always sufficient however:

* Sometimes this group will be too narrow (e.g. someone who is in the group, but does not have an email address from
the group domain)
* Sometimes this group will be too broad (e.g. use by a department at a large company where thousands of people or more
have email in the domain)
* Finally, consider a smaller or looser organization (e.g. a new startup, church group, school group, etc.) still
using gmail or other public email addresses as their work email, so there is no "in-group" domain.

Because of these possible limitations, email authorization is based on email domain and/or email invitations from
already authorized users.

### Authentication Flow

Based on local settings in the OpenCompany Web application, a GET request is made to this authentication service at
`/` to retrieve authentication settings. An unauthenticated response looks like:

```json
{
  "slack" : {
    "basic-scopes-url" : "https://slack.com/oauth/authorize?client_id=6895731204.51562819040&redirect_uri=https://staging-auth.opencompany.com/slack-oauth&state=open-company-auth&scope=identity.basic,identity.email,identity.avatar,identity.team",
    "extended-scopes-url" : "https://slack.com/oauth/authorize?client_id=6895731204.51562819040&redirect_uri=https://staging-auth.opencompany.com/slack-oauth&state=open-company-auth&scope=bot,users:read",
    "redirectURI" : "/slack-oauth",
    "state" : "open-company-auth",
  }, 
  "email" : {
    "links" : [
      {
        "rel" : "authenticate",
        "method" : "GET",
        "href" : "https://auth.opencompany.com/email-auth",
        "type" : "text/plain"
      },
      {
        "rel" : "create",
        "method" : "POST",
        "href": "https://auth.opencompany.com/email/users",
        "type" : "application/vnd.open-company.user.v1+json"
      }
    ]
  }
}
```

A response with an authenticated user is limited to just the refresh URL appropriate for that user:

```json
{
  "links" :[
    {
      "rel" : "refresh",
      "method" : "GET",
      "href" : "https://auth.opencompany.com/slack/refresh-token",
      "type" : "text/plain"
    },
    {
      "rel" : "users",
      "method" : "GET",
      "href" : "https://auth.opencompany.com/slack/users",
      "type" : "application/vnd.collection+vnd.open-company.user+json;version=1"
    }
  ]
}
```

or:

```json
{
  "links" :[
    {
      "rel" : "refresh",
      "method" : "GET",
      "href" : "https://auth.opencompany.com/email/refresh-token",
      "type" : "text/plain"
    },
    {
      "rel" : "create",
      "method" : "POST",
      "href": "https://auth.opencompany.com/email/users"
      "type" : "application/vnd.open-company.user.v1+json"
    },
    {
      "rel" : "users",
      "method" : "GET",
      "href" : "https://auth.opencompany.com/email/users",
      "type" : "application/vnd.collection+vnd.open-company.user+json;version=1"
    }
  ]
}
```

#### Slack Authentication Flow

Upon clicking the "Sign in with Slack" button, the user is redirected to the `extended-scopes-url` to authenticate with
Slack. The Slack app is configured to call back to this authentication service at `/slack-oauth` with an authentication
success or failure.

If the authentication was successful, the authentication service creates a JWToken and redirects the user back to the
web application at: `/login?jwt=<JWToken>`

If the authentication wasn't successful, the authentication service redirects the user back to the web application at 
`/login?access=denied`. From there, subsequent attempts to authenticate using the `basic-scopes-url` that does not
authorize the bot can occur.

The main implication of a successful Slack authentication is the creation of a trusted JWToken that is then used to
authorize all subsequent access to the API.

![Slack Auth Diagram](https://cdn.rawgit.com/open-company/open-company-web/mainline/docs/slack-auth-success.svg)

#### Email Authentication Flow

Upon clicking the "Sign in with Email" link, a `GET` request is made to the `auth-url` with HTTP Basic authentication
(always over HTTPS) to authenticate with an email address and password. If the email/pass authentication succeeds,
there is a 200 response with a JWToken returned in the body of the response. If the email/pass authentication fails,
there is a 401 response.

The main implication of a successful email/pass authentication is the creation of a trusted JWToken that is then used
to authorize all subsequent access to the API.

![Email Auth Diagram](https://cdn.rawgit.com/open-company/open-company-web/mainline/docs/email-auth-success.svg)

#### JWToken Expiration / Refresh

The JWToken contains an expiration field (24 hours for Slack users w/ an auth'd bot, and 2 hours for everyone else).
An expired JWToken does not authorize access to services. Before each use the JWToken is checked for expiration and if
it's expired, the user is...

TBD.

#### Email Onboarding

Unlike Slack, which doesn't need to differentiate between initial onboarding and subsequent authorizations, the
onboarding of a new email user takes a different path. 

To onboard a new email user, POST the following to: `/email/users`

Headers:

```
Content-Type: application/vnd.open-company.user.v1+json
Accept: text/plain
```

Body:

```json
{
  "email": "camus@combat.org",
  "password": "Ssshhh!#&@!",
  "first-name": "Albert",
  "last-name": "Camus",
}
```

If successful, the `201` response will contain a `Location` header with the location of the newly created user,
as well as a JWToken for the user in the body.


A new user request can be one of 4 cases:

* already existing user (return code status of `409 Conflict`)
* user known by email domain (e.g. @opencompany.com)
* user known by one or more open invitations
* unknown user and unknown email domain (brand new)

TBD.

#### Email Validation

TBD.

#### Email User Management

Authenticated users can enumerate the users with the same `org-id` with a GET request to `/slack/users` or  `/email/users` respectively:

```json
{
  "collection" : {
    "version" : "1.0",
    "href" : "/email/users",
    "org-id": "email:1234-5678"
    "links" : [
      {
        "rel" : "self",
        "method" : "GET",
        "href" : "/email/users",
        "type" : "application/vnd.collection+vnd.open-company.user+json;version=1"
      }],
    "users" : [
      {
        "user-id": "email-6789-0123",
        "real-name": "Simone de Beauvoir",
        "avatar": "https://en.wikipedia.org/wiki/File:Simone_de_Beauvoir.jpg",
        "email": "simone@lyceela.org",
        "status": "active",
        "links" : [
          {
            "rel" : "self",
            "method" : "GET",
            "href" : "/email/users/email-1234-5678",
            "type" : "application/vnd.collection+vnd.open-company.user+json;version=1"
          },
          {
            "rel" : "delete",
            "method" : "DELETE",
            "href" : "/email/users/email-1234-5678"
          }
        ]
      },
      {
        "user-id": "email-abcd-efgh",
        "real-name": "Albert Camus",
        "avatar": "http://www.brentonholmes.com/wp-content/uploads/2010/05/albert-camus1.jpg",
        "email": "albert@combat.org",
        "status": "pending",
        "links" : [
          {
            "rel" : "self",
            "method" : "GET",
            "href" : "/email/users/email-abcd-efgh",
            "type" : "application/vnd.collection+vnd.open-company.user+json;version=1"
          },
          {
            "rel" : "invite",
            "method" : "POST",
            "href" : "/email/users/email-abcd-efgh/invite"
          },
          {
            "rel" : "delete",
            "method" : "DELETE",
            "href" : "/email/users/email-abcd-efgh"
          }
        ]
      }      
    ]
  }
}
```

#### Email Invitations

To invite a new email user, with an authenticated user, POST their email address to: `/email/users`

Headers:

```
Authorization: Bearer <JWToken>
Content-Type: application/vnd.open-company.user.v1+json
Accept: application/vnd.open-company.user.v1+json
```

Body:

```json
{
  "email": "camus@combat.org"
}
```

If successful, the `201` response will contain a `Location` header with the location of the newly created user,
as well as a JSON body with user properties and links.

```json
{
  "user-id": "email-abcd-efgh",
  "real-name": "",
  "avatar": "",
  "email": "albert@combat.org",
  "status": "pending",
  "links" : [
    {
      "rel" : "self",
      "method" : "GET",
      "href" : "/email/users/email-abcd-efgh",
      "type" : "application/vnd.collection+vnd.open-company.user+json;version=1"
    },
    {
      "rel" : "invite",
      "method" : "POST",
      "href" : "/email/users/email-abcd-efgh/invite"
    },
    {
      "rel" : "delete",
      "method" : "DELETE",
      "href" : "/email/users/email-abcd-efgh"
    }
  ]
}
```

To re-invite a user that's failed to heed their invitation, POST an empty body to the `rel` `invite` link from the
user (see above). The response will be the same as an initial invite.

#### Password Reset

TBD.


```


#### User Storage

Users are stored as documents in the `users` table of the `open_company_auth` RethinkDB database.

In the case of a Slack user, `owner` and `admin` properties have to do with their privelages in the
Slack organization. An example stored Slack user:

```json
{
  "user-id": "slack:U06SCTYJR",
  "org-id": "slack:T07SBMH80",
  "name": "camus",
  "real-name": "Albert Camus",
  "first-name": "Albert",
  "last-name": "Camus",
  "avatar": "http://...",
  "email": "albert@combat.org",
  "owner": "false",
  "admin": "true"
}
```

In the case of as email user, there are no `owner` and `admin` properties.

```json
{
  "user-id": "email:4567-a8f6",
  "org-id": "email:bb92-67ga",
  "name": "albert",
  "real-name": "Albert Camus",
  "first-name": "Albert",
  "last-name": "Camus",
  "avatar": "http://...",
  "email": "albert@combat.org",
}
```

#### JWTokens

JSON Web Tokens are created as authorization tokens for authorized users. They consist of the user storage data
(see above) as well as an expiration timestamp (in milliseconds since the epoch) for the token.

An example JWToken payload:

```json
{
  "user-id": "slack:U06SCTYJR",
  "org-id": "slack:T07SBMH80",
  "name": "camus",
  "real-name": "Albert Camus",
  "first-name": "Albert",
  "last-name": "Camus",
  "avatar": "http://...",
  "email": "albert@combat.org",
  "owner": "false",
  "admin": "true",
  "exprire": 1474975206974,
  "auth-source": "slack"
}
```

and:

```json
{
  "user-id": "email:4567-a8f6",
  "org-id": "email:bb92-67ga",
  "name": "camus",
  "real-name": "Albert Camus",
  "first-name": "Albert",
  "last-name": "Camus",
  "avatar": "http://...",
  "email": "albert@combat.org",
  "exprire": 1474975206974,
  "auth-source": "email"
}
```
## Testing

Tests are run in continuous integration of the `master` and `mainline` branches on [Travis CI](https://travis-ci.org/open-company/open-company-auth):

[![Build Status](http://img.shields.io/travis/open-company/open-company-auth.svg?style=flat)](https://travis-ci.org/open-company/open-company-auth)

To run the tests locally:

```console
lein test!
```


## Participation

Please note that this project is released with a [Contributor Code of Conduct](https://github.com/open-company/open-company-auth/blob/mainline/CODE-OF-CONDUCT.md). By participating in this project you agree to abide by its terms.


## License

Distributed under the [Mozilla Public License v2.0](http://www.mozilla.org/MPL/2.0/).

Copyright © 2015-2016 OpenCompany, LLC