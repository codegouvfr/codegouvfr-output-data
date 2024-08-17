Specs pour un seul fichier source pour code.gouv.fr/sources
=======

# À ce jour

La liste des dépôts exposée via [code.gouv.fr/sources](https://code.gouv.fr/sources/) est construite à partir de deux fichiers :

-   <https://git.sr.ht/~codegouvfr/codegouvfr-fetch-data/blob/main/platforms.csv>
-   <https://git.sr.ht/~codegouvfr/codegouvfr-sources/blob/main/comptes-organismes-publics.yml>

# Deux types de forges

Nous distinguons deux types de forges :

-   Les forges « ouvertes » : ce sont des forges ouvertes à d'autres acteurs que les seules administrations publiques et où nous devons collecter les dépôts de certains groupes seulement.
-   Les forges du secteur public : ce sont toutes les autres forges où
    les dépôts de tous les groupes doivent être collectés.

Parmi les forges ouvertes :

-   github.com
-   gitlab.com
-   framagit.org
-   gitlab.adullact.net
-   gitlab.ow2.org

Parmi les forges du secteur public :

-   git.ademe.fr
-   git.beta.pole-emploi.fr
-   dci-gitlab.cines.fr
-   gitlab-fil.univ-lille.fr

# Proposition

    github.com:
      # "general_purpose" says the forge is not only for public sector code
      general_purpose: true
      
      # forge is one of ["gitlab", "github", "sourcehut", "gitea", "gogs", "forgejo"]
      forge: github
    
      # groups is a list of groups/organizations
      groups:
        groupe1:
          # pso is the public sector organization's name
          pso: Ministry of blaba
          # pso_id is the org's sha1 in https://lannuaire.service-public.fr
          pso_id: 100
          # floss_policy is a URL pointing to the FLOSS policy
          floss_policy: https://www.etalab.gouv.fr/accompagnement-logiciels-libres
        groupe2:
        groupe3:
    
    gitlab.com:
      forge: gitlab
      # ignored_since is a iso-8601 date which is
      # the starting date when to *not* collect data (404, 403, etc.) 
      ignored_since: 2024-08-09
      groups:
        groupe1:
          pso: blabla
          pso_id: 1
    
    # for public_sector forges, no need to list groups
    forge.univ-lyon1.fr:
      forge: gitlab
      pso: blabla
      pso_id: 2
      floss_policy: https://www.etalab.gouv.fr/accompagnement-logiciels-libres

# Remarques et questions

-   Pas besoin du protocole `https`, on suppose que c'est toujours `https` et on ignore les autres.
-   Doit-on mettre un titre à la forge (pour l'UI de data.code.gouv.fr)?
