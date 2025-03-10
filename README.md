[![img](https://img.shields.io/badge/code.gouv.fr-contributif-blue.svg)](https://code.gouv.fr/documentation/#/publier)
[![Software License](https://img.shields.io/badge/Licence-EPL.svg)](https://githut.com/codegouvfr/codegouvfr-cli/tree/main/item/LICENSES/LICENSE.EPL-2.0.txt)

# Presentation

This repository contains various scripts related to
[code.gouv.fr](https://code.gouv.fr.)

# Prerequisites

The scripts are written in [Clojure](https://clojure.org) with
[Babashka](https://babashka.org) and we recommend installing them with
[bbin](https://github.com/babashka/bbin).

-   [Install Clojure](https://clojure.org/guides/install_clojure)
-   [Install babashka](https://github.com/babashka/babashka#installation)
-   [Install bbin](https://github.com/babashka/bbin#installation)

This should take care of installing all you need:

    ~$ brew install babashka/brew/bbin

# Installation

Once `bbin` is installed in your environment, run this in a terminal:

- `bbin install https://raw.githubusercontent.com/codegouvfr/codegouvfr-cli/refs/heads/main/src/codegouvfr-output-data.clj`
- `bbin install https://raw.githubusercontent.com/codegouvfr/codegouvfr-cli/refs/heads/main/src/faq-server-dsfr.clj`
- `bbin install https://raw.githubusercontent.com/codegouvfr/codegouvfr-cli/refs/heads/main/src/subscribe-dsfr.clj`

# [Contributing](CONTRIBUTING.md)

# Support the Clojure(script) ecosystem

If you like Clojure(script), you can support the ecosystem by making a donation to [clojuriststogether.org](https://www.clojuriststogether.org).

# Licence

2023-2025 DINUM, Bastien Guerry.

The code is published under the [EPL 2.0 license](LICENSES/LICENSE.EPL-2.0.txt).
