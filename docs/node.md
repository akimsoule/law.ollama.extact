# 📘 Parsing algorithmique des textes de loi du Bénin

## Construction d’un arbre juridique (Loi → Structure → Articles)

---

## 🎯 Objectif

Transformer un **texte de loi brut** (issu d’un PDF après OCR et nettoyage)
en un **arbre Java** représentant fidèlement la **structure juridique** :

- Loi simple : `LOI → ARTICLE`
- Loi complexe : `LOI → LIVRE → TITRE → CHAPITRE → ARTICLE`

Cet arbre servira ensuite à l’indexation, au RAG ou à la migration Neo4j.

---

## 🧠 Principe général

1. Lire le texte **ligne par ligne**
2. Détecter les **marqueurs juridiques**
3. Construire un **arbre hiérarchique** avec une pile
4. Attacher le **texte normatif uniquement aux articles**

---

## 🧱 Modèle conceptuel (arbre)

```
LawNode
 ├─ type      : enum { LOI, LIVRE, TITRE, CHAPITRE, ARTICLE }
 ├─ numero    : String
 ├─ intitule  : String
 ├─ texte     : String
 └─ enfants   : List<LawNode>
```

---

## 🧾 Données

### Entrée

- Liste de lignes de texte
- Texte normalisé :
  - césures corrigées
  - espaces multiples supprimés
  - en-têtes/pieds de page supprimés

### Sortie

- Un arbre Java dont la racine est un nœud `LOI`

---

## 🔍 Détection des structures juridiques

```
SI ligne commence par "LIVRE"
    ALORS type = LIVRE

SI ligne commence par "TITRE"
    ALORS type = TITRE

SI ligne commence par "CHAPITRE"
    ALORS type = CHAPITRE

SI ligne commence par "ARTICLE"
    ALORS type = ARTICLE
```

---

## 🧠 Algorithme principal (langage algorithmique)

### Initialisation

```
CRÉER noeudRacine de type LOI
INITIALISER pile ← pile vide
EMPILER noeudRacine dans pile
INITIALISER articleCourant ← NULL
```

---

### Parcours du texte

```
POUR CHAQUE ligne DANS lignes

    NETTOYER ligne

    SI ligne est vide
        CONTINUER
    FIN

    SI ligne détecte un LIVRE
        CRÉER noeud LIVRE
        AJUSTER pile
        ATTACHER LIVRE au sommet
        EMPILER LIVRE
        articleCourant ← NULL

    SINON SI ligne détecte un TITRE
        CRÉER noeud TITRE
        AJUSTER pile
        ATTACHER TITRE
        EMPILER TITRE
        articleCourant ← NULL

    SINON SI ligne détecte un CHAPITRE
        CRÉER noeud CHAPITRE
        AJUSTER pile
        ATTACHER CHAPITRE
        EMPILER CHAPITRE
        articleCourant ← NULL

    SINON SI ligne détecte un ARTICLE
        CRÉER noeud ARTICLE
        AJUSTER pile
        ATTACHER ARTICLE
        articleCourant ← ARTICLE

    SINON
        SI articleCourant ≠ NULL
            AJOUTER ligne AU texte de articleCourant
        FIN
    FIN

FIN
```

---

## 🧩 Ajustement de la pile (hiérarchie)

### Règles de contenance

```
LOI        → LIVRE, TITRE, CHAPITRE, ARTICLE
LIVRE      → TITRE, CHAPITRE, ARTICLE
TITRE      → CHAPITRE, ARTICLE
CHAPITRE   → ARTICLE
ARTICLE    → (aucun enfant)
```

---

### Fonction AJUSTER pile

```
TANT QUE sommet de pile ne peut PAS contenir le nouveau nœud
    DÉPILER
FIN
```

---

## 🧱 Fonction ATTACHER

```
AJOUTER nouveau nœud à la liste enfants du sommet de pile
```

---

## 📌 Cas gérés

- Lois simples sans titres ni chapitres
- Lois complexes à plusieurs niveaux
- Absence de certains niveaux
- Texte d’article sur plusieurs lignes
- Ordre juridique strict conservé

---

## ⚠️ Hors périmètre volontaire

- Découpage des alinéas
- Annexes et tableaux
- Signataires et métadonnées
- Interprétation juridique

---

## 🧠 Invariants garantis

```
- Chaque ARTICLE a exactement un parent
- Aucun texte normatif hors ARTICLE
- L’ordre original du texte est conservé
- L’arbre reflète la structure légale réelle
```

---

## 🔑 Résumé

> Lire → Détecter → Ajuster la pile → Attacher → Accumuler le texte

---

## 🚀 Étape suivante possible

- Ajout des ALINÉAS
- Tolérance OCR avancée
- Mapping automatique vers Neo4j
- Indexation RAG optimisée
