# 📘 Guide d'Enrichissement du Dictionnaire Tesseract (`tessdata/`)

> **Objectif** : Ce fichier sert de référence unique pour les contributeurs humains et les assistants IA (LLM) afin d'alimenter, corriger et optimiser les fichiers de dictionnaire utilisés par Tesseract OCR et le service `QAService.java`.

## 📁 Structure des fichiers

```
src/main/resources/tessdata/
├── fra.user-legal-pattern      # Phrases, structures et formules juridiques récurrentes
├── fra.user-legal-words        # Mots-clés juridiques isolés (noms, verbes, concepts)
├── fra.user-minister-pattern   # Noms complets + titres des ministres & hauts fonctionnaires
├── fra.user-president-pattern  # Noms des Présidents de la République / Chefs d'État
├── fra.user-region-words       # Départements, communes, villes, régions du Bénin/Dahomey
└── fra.user-sigle              # Acronymes officiels (institutions, lois, organismes)
```

## 📏 Règles de formatage (STRICTES)

| Règle                 | Détail                                                                                               |
| --------------------- | ---------------------------------------------------------------------------------------------------- |
| **Encodage**          | `UTF-8` sans BOM                                                                                     |
| **Structure**         | 1 entrée par ligne. Pas de lignes vides. Pas d'espaces en fin de ligne.                              |
| **Accents & Césures** | Conserver l'orthographe officielle (ex: `Ministre`, `Cotonou`, `Constitution`)                       |
| **Commentaires**      | Autorisés avec `#` en début de ligne. Ignorés par le parser.                                         |
| **Doublons**          | Vérifier avant ajout. Le parser utilise `HashSet` → les doublons sont ignorés mais polluent le repo. |
| **Casse**             | Respecter la casse officielle. `QAService` normalise en minuscules sans accents pour la comparaison. |

## 🔍 Détail par fichier

### `fra.user-legal-pattern`

- **But** : Améliorer la détection de structures fixes dans les textes officiels.
- **Format** : Phrases ou expressions complètes, exactement comme elles apparaissent dans les lois.
- **Exemples** :
  ```text
  RÉPUBLIQUE DU BÉNIN
  Fraternité-Justice-Travail
  LOI N°
  VU LA CONSTITUTION
  LE PRÉSIDENT DE LA RÉPUBLIQUE DÉCRÈTE
  Fait à Cotonou, le
  ```
- **Sources** : JORB, site du SGG, modèles de décrets/lois.

### `fra.user-legal-words`

- **But** : Lexique juridique de base pour corriger les erreurs OCR courantes (`Loi` → `Loi`, `Gouvernement` → `Gouvernement`).
- **Format** : Mots simples ou composés séparés par des tirets/espaces.
- **Exemples** :
  ```text
  Juridiction
  Promulguée
  Sceaux
  Délibéré
  internationale
  ```

### `fra.user-minister-pattern`

- **But** : Identifier les signataires dans le footer et valider les noms via `QAService`.
- **Format** : `Prénom(s) NOM` (majuscules sur le nom de famille recommandé).
- **Exemples** :
  ```text
  Patrice TALON
  Sévérin Maxime QUENUM
  Aurélien AGBÉNONCI
  ```
- **Mise à jour** : À chaque remaniement gouvernemental.

### `fra.user-president-pattern`

- **But** : Reconnaissance des chefs d'État historiques et actuels.
- **Exemples** :
  ```text
  Mathieu KÉRÉKOU
  Boni YAYI
  Nicéphore D. SOGLO
  ```

### `fra.user-region-words`

- **But** : Détection des localités dans les textes juridiques et corrections OCR (`Cotonou` vs `CCTONOU`).
- **Exemples** :
  ```text
  Cotonou
  Porto-Novo
  Abomey-Calavi
  Parakou
  ```

### `fra.user-sigle`

- **But** : Acronymes institutionnels ou techniques.
- **Exemples** :
  ```text
  SGG
  JORB
  WACA
  ReSIP
  ```

## 🤖 Prompt LLM prêt à l'emploi

Copie ce bloc dans ton contexte ou utilise-le comme instruction pour un assistant IA :

````text
Tu es un expert en linguistique juridique béninoise et en optimisation OCR Tesseract.
MISSION : Analyser le journal d'erreurs QA (`errors.log`) ou un texte OCR bruité, puis générer des entrées valides pour les fichiers `tessdata/`.

RÈGLES :
1. Retourne UNIQUEMENT un bloc de code par fichier concerné.
2. Respecte strictement : 1 ligne = 1 entrée, UTF-8, pas d'espaces finaux.
3. Vérifie l'orthographe officielle via des sources gouvernementales béninoises.
4. Si un terme est déjà présent, ne le remets PAS.
5. Préfixe les nouveaux termes avec `# NOUVEAU (YYYY-MM-DD)` si demandé.

FORMAT DE RÉPONSE ATTENDU :
### fra.user-legal-words
```
terme1
terme2
```
### fra.user-minister-pattern
```
NOM Prénom
```
````

## ✅ Workflow de validation

1. **Détection** : Le `QAService` logue les mots inconnus dans `errors.log` avec le préfixe `⚠️ LEXIQUE: X mots inconnus`.
2. **Extraction** : Identifier les termes manquants ou mal reconnus.
3. **Vérification** : Croiser avec une source officielle (JORB, décret de nomination, constitution).
4. **Ajout** : Insérer dans le fichier `tessdata/` approprié.
5. **Commit** : `chore(tessdata): ajout termes ministériels & correction OCR`
6. **Replay** : Relancer `LlmExtractorRunner` ou `ScriptExtractorRunner` avec `--force` pour valider la baisse des erreurs QA.

## 🛡️ Bonnes pratiques

- 🚫 **Ne jamais** mettre de phrases entières dans `*.words`. Utiliser `*-pattern` ou `*-legal-words` selon le cas.
- 🔍 **Tester** après ajout : `mvn compile exec:java -Dexec.mainClass="org.law.runner.LlmExtractorRunner" -Dexec.args="--force loi-cible.pdf"`
- 📊 **Monitorer** : Le taux de succès QA doit augmenter. Si `errors.log` diminue, le dictionnaire est efficace.
- 🔄 **Maintenance** : Prévoir un script ou un cron mensuel pour synchroniser avec les publications du Journal Officiel.

---

> 💡 _Ce fichier peut être injecté en contexte système à un LLM pour qu'il propose des corrections ciblées, génère des listes de termes juridiques manquants, ou nettoie automatiquement les fichiers existants._
