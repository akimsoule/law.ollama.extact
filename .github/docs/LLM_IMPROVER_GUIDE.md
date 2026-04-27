# 🤖 LLM Improver Guide - `law.ollama.extact`

> **Rôle de ce document** : Directive stricte pour tout assistant IA chargé d'analyser, corriger ou faire évoluer ce projet.
> **Objectif** : Améliorer la qualité, la performance et la maintenabilité sans jamais compromettre l'intégrité des pipelines d'extraction ni la priorité LLM.

---

## 🏗️ 1. Architecture Snapshot

| Composant                  | Fichier                        | Rôle                                                                                         |
| -------------------------- | ------------------------------ | -------------------------------------------------------------------------------------------- |
| **Extraction Script**      | `ScriptExtractorRunner.java`   | Pipeline Java pur (OCR → Parser → JsonService). Fallback fiable.                             |
| **Extraction LLM**         | `LlmExtractorRunner.java`      | Pipeline DeepSeek API (`deepseek-v4-flash`). Sortie JSON strict. Priorité absolue.           |
| **Fusion & Ingestion**     | `DatabaseIngestRunner.java`    | Charge `llm_output/` en premier, comble avec `script_output/`, vectorise, pousse dans Neo4j. |
| **Contrôle Qualité**       | `QAService.java`               | Valide le schéma JSON + compte les mots inconnus via `tessdata/`. Log dans `errors.log`.     |
| **Dictionnaire Dynamique** | `src/main/resources/tessdata/` | Fichiers `*.user-*` chargés à chaud. Source de vérité lexicale pour Tesseract & QA.          |

---

## 🚫 2. Règles d'Or (NON NÉGOCIABLES)

1. **Priorité LLM Intouchable** : Le Script ne peut JAMAIS écraser un JSON présent dans `llm_output/`.
2. **Idempotence Obligatoire** : Tout runner doit skipper les fichiers existants sauf si `--force` est passé.
3. **Schéma JSON Fixe** :
   ```json
   {
     "metadata": {
       "lawNumber": "",
       "lawDate": "",
       "lawObject": "",
       "signatories": []
     },
     "articles": [{ "index": "", "content": "" }]
   }
   ```
4. **Zéro Secret en Dur** : Clés API, chemins sensibles, URLs → Variables d'environnement ou `Config.java`.
5. **Isolation des Runners** : Pas de dépendance croisée entre `ScriptExtractorRunner` et `LlmExtractorRunner`.
6. **Logs Structurés** : Les échecs QA et crashes doivent alimenter `errors.log`. Le pipeline ne doit pas crasher silencieusement.

---

## 🎯 3. Axes d'Amélioration Prioritaires

| Domaine              | Action Attendue                                                                                                                     | Métrique de Succès                                      |
| -------------------- | ----------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------- |
| **🚀 Performance**   | Paralléliser les appels API (`ExecutorService`), optimiser le chargement `tessdata/` (cache singleton), réduire les I/O redondants. | `-30%` temps batch, `+50%` throughput API               |
| **🛡️ Robustesse QA** | Ajouter validation JSON Schema (`networknt`), seuil d'alerte lexicale, reporting par article dans `errors.log`.                     | `0` JSON invalide en base, traçabilité complète         |
| **⚙️ Configuration** | Externaliser `MODEL`, `MAX_TOKENS`, `TEMPERATURE`, chemins de sortie dans `config.properties` ou `.env`.                            | Zéro recompilation pour changer de modèle ou de dossier |
| **🧪 Tests**         | JUnit 5 pour `QAService`, `OcrService` (mock PDF), `LlmExtractorRunner` (mock `HttpClient`).                                        | `>80%` coverage sur la logique métier                   |
| **📊 Observabilité** | Structurer `errors.log` (JSON ou CSV), ajouter métriques tokens/coûts, logs SLF4J unifiés.                                          | Monitoring facile, débogage en `<2min`                  |

---

## 🔧 4. Protocole d'Intervention LLM

Avant de proposer une modification, l'IA DOIT suivre ces étapes :

1. **🔍 Identifier le scope** : Quel composant est concerné ? Quel est l'impact sur les autres ?
2. **✅ Vérifier les contraintes** : Respecte-t-il les Règles d'Or ? Préserve-t-il l'idempotence et la priorité LLM ?
3. **📐 Proposer un diff minimal** : Code ciblé, backward-compatible, sans refactoring inutile.
4. **🧪 Définir la validation** : Comment tester ? Quel mock utiliser ? Quelle commande Maven lancer ?
5. **📦 Documenter l'impact** : Performance, mémoire, coûts API, compatibilité future.

> ⚠️ **Interdiction** : Modifier `DatabaseIngestRunner::putIfAbsent` ou `LlmExtractorRunner`'s logique de cache sans justification explicite et validation croisée.

---

## 📚 5. Gestion de `tessdata/` & QA

Le dossier `tessdata/` est **dynamique**. L'IA doit traiter les alertes QA ainsi :

```text
[2026-04-27] ERREUR QA SUR loi-2023.pdf
  - ⚠️ LEXIQUE: 12 mots inconnus dans l'article 3
```

**Workflow attendu :**

1. Extraire les mots inconnus depuis `errors.log`.
2. Croiser avec des sources officielles (JORB, décrets, constitution).
3. Proposer l'ajout dans le fichier `tessdata/` adéquat (`legal-words`, `minister-pattern`, etc.).
4. Si le mot est légitime mais absent, mettre à jour le dictionnaire. Si c'est une erreur OCR, proposer une correction dans `correction/correct.csv`.
5. Ne JAMAIS ignorer une alerte QA sans trace de décision.

---

## 💰 6. Optimisation des coûts DeepSeek

1. **Contrôle des modèles** : `MODEL` doit être configurable et exploiter les versions les plus économiques disponibles sans réduire la qualité de sortie.
2. **Batching et simultanéité** : Grouper les requêtes lorsque possible, exécuter les appels API avec un `ExecutorService` et limiter le nombre de threads concurrents pour éviter les pics de coûts.
3. **Retry / backoff intelligents** : Ne relancer que les erreurs transitoires (`429`, `5xx`) et limiter les retries à un seuil défini.
4. **Suivi des tokens** : Logger `inputTokens`, `outputTokens`, `costEstimate` par requête pour analyser les usages et identifier les requêtes trop chères.
5. **Validation locale en amont** : Prétraiter et nettoyer le texte avant envoi pour éviter d’encoder du bruit inutile dans les prompts.

**Métriques attendues :**

- Réduction du coût API par document
- Ratio `successful / total` sans hausse significative des erreurs
- Mesure de tokens par document et coût estimé sur `errors.log`

---

## 📦 7. Préparation du dataset d'entraînement

1. **Normalisation du JSON** : Vérifier que chaque sortie respecte le schéma fixe avant ingestion.
2. **Annotations de qualité** : Conserver les documents source, le JSON LLM, les corrections QA, et l'historique des décisions dans un dossier `training/` ou `llm_audit/`.
3. **Étiquetage clair** : Ajouter des tags `source=llm`, `source=script`, `qaStatus=approved|review|rejected` dans les métadonnées lorsque possible.
4. **Séparation des jeux** : Isoler les documents d'entraînement des documents de production pour éviter la fuite de données.
5. **Contrôle de cohérence** : Générer un rapport de qualité `.csv` ou `.json` indiquant l’état de chaque document validé, les erreurs QA et les corrections appliquées.

**Métriques attendues :**

- Pourcentage de documents conformes au schéma `JSON`
- Nombre de corrections QA appliquées par batch
- Taille et couverture des jeux `train/validation/test`

---

## ✅ 8. Checklist de Validation (Avant Commit)

- [ ] Respect de la priorité LLM > Script
- [ ] Idempotence vérifiée (`--force` fonctionne, cache skip OK)
- [ ] Schéma JSON préservé
- [ ] Aucun secret/chemin hardcodé
- [ ] Gestion d'erreurs API (retry, backoff, log)
- [ ] Tests unitaires ajoutés/mis à jour
- [ ] Documentation du changement (`CHANGELOG.md` ou commentaire code)
- [ ] Compatible Java 17 & dépendances Maven actuelles

---

## 📤 9. Format de Réponse Attendu (Pour l'IA)

````markdown
## 🎯 Cible

[Nom du fichier / Composant]

## 🔍 Problème / Opportunité

[Description concise]

## ✅ Solution Proposée

[Explication technique + impact]

## 📝 Diff / Code

```java
// Chemin: src/main/java/...
// Remplacer/ajouter: ...
```
````

## 🧪 Validation

```bash
mvn test -Dtest=...
# OU
mvn compile exec:java -Dexec.mainClass="..." -Dexec.args="--force loi-test.pdf"
```

## ⚠️ Risques / Notes

[Performance, coût API, breaking changes, dépendances]

```

---

> 💡 **Objectif final** : Préparer un dataset propre, structuré et validé pour l'entraînement futur d'un modèle custom d'extraction juridique. Toute amélioration doit servir cet objectif sans compromettre la production actuelle.
```
