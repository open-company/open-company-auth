# [OpenCompany](https://opencompany.io) Authentication Service

[![MPL License](http://img.shields.io/badge/license-MPL-blue.svg?style=flat)](https://www.mozilla.org/MPL/2.0/)
[![Build Status](https://travis-ci.org/open-company/open-company-auth.svg)](https://travis-ci.org/open-company/open-company-auth)
[![Dependency Status](https://www.versioneye.com/user/projects/562129c236d0ab0021000a0e/badge.svg?style=flat)](https://www.versioneye.com/user/projects/562129c236d0ab0021000a0e)
[![Roadmap on Trello](http://img.shields.io/badge/roadmap-trello-blue.svg?style=flat)](https://trello.com/b/3naVWHgZ/open-company-development)

## Overview

> I've come to learn there is a virtuous cycle to transparency and a very vicious cycle of obfuscation.

> -- [Jeff Weiner](https://www.linkedin.com/in/jeffweiner08)

Employees and investors, co-founders and execs, they all want more transparency from their startups, but there's no consensus about what it means to be transparent. OpenCompany is a platform that simplifies how key business information is shared with stakeholders.

When information about growth, finances, ownership and challenges is shared transparently, it inspires trust, new ideas and new levels of stakeholder engagement. OpenCompany makes it easy for founders to engage with employees and investors, creating a sense of ownership and urgency for everyone.

[OpenCompany](https://opencompany.io) is GitHub for the rest of your company.

To maintain transparency, OpenCompany information is always accessible and easy to find. Being able to search or flip through prior updates empowers everyone. Historical context brings new employees and investors up to speed, refreshes memories, and shows how the company is evolving over time.

Transparency expectations are changing. Startups need to change as well if they are going to attract and retain savvy employees and investors. Just as open source changed the way we build software, transparency changes how we build successful startups with information that is open, interactive, and always accessible. The OpenCompany platform turns transparency into a competitive advantage.

Like the open companies we promote and support, the [OpenCompany](https://opencompany.io) platform is completely transparent. The company supporting this effort, Transparency, LLC, is an open company. The [platform](https://github.com/open-company/open-company-web) is open source software, and open company data is [open data](https://en.wikipedia.org/wiki/Open_data) accessible through the [platform API](https://github.com/open-company/open-company-api).

To get started, head to: [OpenCompany](https://opencompany.io)


## Local Setup

Users of the [OpenCompany](https://opencompany.io) platform should get started by going to [OpenCompany](https://opencompany.io). The following local setup is for developers wanting to work on the platform's Auth application software.

Most of the dependencies are internal, meaning [Leiningen](https://github.com/technomancy/leiningen) will handle getting them for you. There are a few exceptions:

* [Java 8](http://www.oracle.com/technetwork/java/javase/downloads/index.html) - a Java 8 JRE is needed to run Clojure
* [Leiningen](https://github.com/technomancy/leiningen) - Clojure's build and dependency management tool

Chances are your system already has Java 8 installed. You can verify this with:

```console
java -version
```

If you do not have Java 8 [download it](http://www.oracle.com/technetwork/java/javase/downloads/index.html) and follow the installation instructions.

Leiningen is easy to install:

1. Download the latest [lein script from the stable branch](https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein).
1. Place it somewhere that's on your $PATH (`env | grep PATH`). `/usr/local/bin` is a good choice if it is on your PATH.
1. Set it to be executable. `chmod 755 /usr/local/bin/lein`
1. Run it: `lein` This will finish the installation.

Then let Leiningen install the rest of the dependencies:

```console
git clone https://github.com/open-company/open-company-auth.git
cd open-company-auth
lein deps
```


## Usage

Users of the [OpenCompany](https://opencompany.io) platform should get started by going to [OpenCompany](https://opencompany.io). The following usage is for developers wanting to work on the platform's Auth application software.

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

## Sample JWToken

To create a sample JWToken for use in development without going through a full auth cycle, create an identity EDN file
formated like the ones in ```/opt/identities``` or use one of the identity EDN files provided, and run the utility:

```console
lein run -m open-company-auth.util.jwtoken -- ./opt/identities/camus.edn
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

Copyright © 2015-2016 Transparency, LLC
