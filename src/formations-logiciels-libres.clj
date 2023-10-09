#!/usr/bin/env bb

;; Copyright (c) DINUM, Bastien Guerry
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSES/LICENSE.EPL-2.0.txt

(println "Generating formations-logiciels-libres.json...")

(spit "formations-logiciels-libres.json"
      (json/generate-string
       (yaml/parse-string
        ;; (slurp "formations-logiciels-libres.yml")
        (slurp "https://git.sr.ht/~codegouvfr/codegouvfr-outils/blob/main/data/formations-logiciels-libres.yml")
        )
       {:pretty true}))

(println "Generating formations-logiciels-libres.json... done")
