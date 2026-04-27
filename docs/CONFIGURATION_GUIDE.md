# 📋 Guide de Configuration - Variables d'Environnement

## ✅ **Configuration Centralisée Implémentée**

Votre projet utilise maintenant un système de configuration centralisé avec `config.properties` et support des variables d'environnement.

---

## 🔧 **Fichier de Configuration**

### **`src/main/resources/config.properties`**
```properties
# Configuration for Neo4j
neo4j.uri=neo4j+s://00870b27.databases.neo4j.io
neo4j.user=neo4j
neo4j.password=_hPOi5IeFbiFujbUYm9RDr3xIjaxDDyd35NXB7_Tn9Y

# Configuration for DeepSeek API
deepseek.api.key=your_deepseek_api_key_here
deepseek.default.model=deepseek-chat
deepseek.batch.size=10
deepseek.temperature=0.1
deepseek.max.tokens=4096

# Configuration for Processing
processing.refresh.ocr=false
processing.min.year=1960
processing.max.year=2026
processing.use.batch.deepseek=true

# Configuration for Models
model.default.temperature=0.1
model.max.tokens=4096
```

---

## 🔑 **Configuration de la Clé API DeepSeek**

### **Méthode 1: Fichier de Configuration (Recommandé)**
1. Éditez `src/main/resources/config.properties`
2. Remplacez `your_deepseek_api_key_here` par votre clé API :
```properties
deepseek.api.key=sk- votre_clé_api_ici
```

### **Méthode 2: Variable d'Environnement**
```bash
export DEEPSEEK_API_KEY="sk-votre_clé_api_ici"
```

### **Méthode 3: Variable d'Environnement (Format Alternatif)**
```bash
export deepseek.api.key="sk-votre_clé_api_ici"
```

---

## ⚙️ **Paramètres Configurables**

### **🚀 Paramètres DeepSeek**
| Paramètre | Défaut | Description |
|----------|--------|-------------|
| `deepseek.api.key` | requis | Clé API DeepSeek |
| `deepseek.default.model` | deepseek-chat | Modèle principal |
| `deepseek.batch.size` | 10 | Taille du batch |
| `deepseek.temperature` | 0.1 | Température LLM |
| `deepseek.max.tokens` | 4096 | Tokens max par requête |

### **📊 Paramètres de Traitement**
| Paramètre | Défaut | Description |
|----------|--------|-------------|
| `processing.refresh.ocr` | false | Régénérer OCR |
| `processing.min.year` | 1960 | Année minimale |
| `processing.max.year` | 2026 | Année maximale |
| `processing.use.batch.deepseek` | true | Mode batch activé |

---

## 🎯 **Utilisation**

### **Démarrage avec Configuration**
```bash
# Utilise les paramètres de config.properties
mvn exec:java -Dexec.mainClass="org.law.Main"

# Override des paramètres avec arguments
mvn exec:java -Dexec.mainClass="org.law.Main" -Dexec.args="--minYear=2020 --maxYear=2026"
```

### **Vérification de la Configuration**
Au démarrage, le système affiche :
```
[ConfigurationService] Configuration actuelle:
  - DeepSeek Default Model: deepseek-chat
  - DeepSeek Batch Size: 10
  - DeepSeek Temperature: 0.1
  - DeepSeek Max Tokens: 4096
  - Use Batch DeepSeek: true
[ConfigurationService] ✅ Clé API DeepSeek configurée
```

---

## 🔒 **Sécurité**

### **⚠️ Important**
- **Ne jamais** commiter votre clé API dans le dépôt Git
- **Utiliser** les variables d'environnement en production
- **Protéger** le fichier `config.properties` avec `.gitignore`

### **🛡️ Bonnes Pratiques**
```bash
# Pour le développement (local)
echo "deepseek.api.key=votre_clé_dev" >> config.properties

# Pour la production
export DEEPSEEK_API_KEY="votre_clé_production"
```

---

## 🔄 **Priorité de Configuration**

1. **Arguments CLI** (priorité maximale)
2. **Variables d'environnement**
3. **Fichier config.properties** (priorité par défaut)

### **Exemple de Priorité**
```bash
# CLI > Env > Config
export DEEPSEEK_API_KEY="env_key"
mvn exec:java -Dexec.args="--batch-deepseek"
# Résultat: utilise "env_key" + mode batch activé
```

---

## 🎛️ **Personnalisation Avancée**

### **Mode Économique**
```properties
deepseek.default.model=deepseek-chat
deepseek.batch.size=10
deepseek.temperature=0.05
```

### **Mode Performance**
```properties
deepseek.default.model=deepseek-chat
deepseek.batch.size=10
deepseek.max.tokens=4096
```

### **Mode Développement**
```properties
deepseek.temperature=0.1
processing.min.year=2020
processing.max.year=2021
```

---

## 🚀 **Déploiement**

### **Docker**
```dockerfile
ENV DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY}
ENV processing.use.batch.deepseek=true
```

### **Production**
```bash
export DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY}"
export deepseek.batch.size="10"
export processing.use.batch.deepseek="true"
mvn exec:java -Dexec.mainClass="org.law.Main"
```

---

## ✅ **Vérification**

### **Test de Configuration**
```bash
# Test rapide
mvn compile

# Test avec affichage config
mvn exec:java -Dexec.mainClass="org.law.Main" -Dexec.args="--minYear=2020 --maxYear=2020"
```

### **Messages d'Erreur**
```
[ConfigurationService] ⚠️ Clé API DeepSeek non configurée !
[ConfigurationService] Définissez 'deepseek.api.key' dans config.properties ou DEEPSEEK_API_KEY en environnement
```

---

## 🎉 **Conclusion**

Votre système est maintenant **configurable, flexible et production-ready** avec :

- ✅ **Configuration centralisée**
- ✅ **Support variables d'environnement**
- ✅ **Priorité des paramètres**
- ✅ **Sécurité intégrée**
- ✅ **Facile à déployer**

**Configurez votre clé API DeepSeek et lancez le traitement !** 🚀
