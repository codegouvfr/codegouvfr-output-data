[![Software License](https://img.shields.io/badge/Licence-EPL%2C%20Licence%20Ouverte-orange.svg?style=flat-square)](https://git.sr.ht/~codegouvfr/codegouvfr-outils/tree/main/item/LICENSE.txt)

# Présentation

Ce dépôt contient des scripts exécutables répondant à divers besoins
de la mission logiciels libres.

# Prérequis

Vous avez besoin de `babashka` et de `bbin` qui permettent d'installer
facilement des scripts écrits en Clojure.

1. [babashka](https://github.com/babashka/babashka#installation)
2. [bbin](https://github.com/babashka/bbin#installation)

# Outils

## `catalogue-gouvtech-libre`

Extrait les logiciels libres de l'ancien catalogue GouvTech.

`bbin install https://git.sr.ht/~codegouvfr/codegouvfr-outils/blob/main/src/catalogue-gouvtech-libre.clj`

## `sill-prestataires`

Fusionner les données des prestataires SILL venant de l'ADULLACT et du
CNLL.

`bbin install https://git.sr.ht/~codegouvfr/codegouvfr-outils/blob/main/src/sill-prestataires.clj`

## `annuaire-service-public-enrichi`

Enrichi le `.json` de l'annuaire des services publics en ajoutant les
ancêtres directs et lointains.

`bbin install https://git.sr.ht/~codegouvfr/codegouvfr-outils/blob/main/src/annuaire-service-public-enrichi.clj`

## `formations-logiciels-libres`

Produit un fichier `.json` avec les formations logiciels libres.

`bbin install https://git.sr.ht/~codegouvfr/codegouvfr-outils/blob/main/src/formations-logiciels-libres.clj`

## `spit-faq-from-json-documentation`

Prend la version `json` de la documentation et en publie la FAQ en
`json`.

`bbin install https://git.sr.ht/~codegouvfr/codegouvfr-outils/blob/main/src/spit-faq-from-json-documentation.clj`

# Contribuer

Vous pouvez contribuer en envoyant vos correctifs (*patches*) à `~codegouvfr/dev@lists.sr.ht`.  Pensez à configurer votre copie locale du dépôt ainsi :

    git config format.subjectPrefix 'PATCH codegouvfr-outils'

Les messages de commit sont de préférence rédigés en anglais.

Vous pouvez aussi créer un compte sur <https://sr.ht> et nous envoyer votre nom d'utilisateur, nous vous donnerons accès en écriture et vous pourrez publier vos contribution directement sur la branch `main`.

Nous n'acceptons les contributions que si elles sont signées (*signed off*) du vrai nom du contributeur.  En signant ses contributions, le contributeur accepte le [developer certificate of origin](https://developercertificate.org).

Si vous le souhaitez, vous pouvez aussi envoyer vos suggestions directement à l'adresse `contact@code.gouv.fr`.

# Soutenir l'écosystème Clojure(script)

Si vous aimez Clojure(script), vous pouvez soutenir l'écosystème en faisant un don à [clojuriststogether.org](https://www.clojuriststogether.org).

# Licence

2023 DINUM, Bastien Guerry.

Le code est publié sous licence [EPL 2.0 license](LICENSES/LICENSE.EPL-2.0.txt).

Les données sont publiées sous licence [Etalab 2.0 license](LICENSES/LICENSE.Etalab-2.0.md) ou, au choix, [Creative Commons BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.fr).
