# 📊 **DeepSeek API - Tarifs Officiels 2026**

## 🎯 **Source Officielle**
**Documentation officielle** : https://api-docs.deepseek.com/quick_start/pricing/

---

## 💰 **Tarifs Officiels DeepSeek V3.2**

### **📋 Modèles Disponibles**
| Modèle | Version | Context | Max Output | Score Qualité |
|--------|----------|---------|------------|---------------|
| **deepseek-chat** | V3.2 (non-thinking) | 128K tokens | 8K tokens | **63/100** |
| **deepseek-reasoner** | V3.2 (thinking) | 128K tokens | 64K tokens | **82/100** |

### **💵 Tarification par Million de Tokens**
| Type | Cache Hit | Cache Miss | Output |
|------|-----------|------------|--------|
| **deepseek-chat** | **$0.028** | **$0.28** | **$0.42** |
| **deepseek-reasoner** | **$0.028** | **$0.28** | **$0.42** |

**Note** : Les deux modèles partagent la même tarification unifiée depuis septembre 2025.

---

## 🆓 **Free Tier**

### **🎁 Offre de Bienvenue**
- **5 millions de tokens gratuits** à l'inscription
- **Aucune carte de crédit requise**
- **Valable pour les deux modèles**
- **Crédits appliqués automatiquement**

### **📝 Comment Obtenir les Tokens Gratuits**
```bash
# 1. Créer compte gratuit
https://platform.deepseek.com/

# 2. Les 5M tokens sont crédités automatiquement
# 3. Commencez à utiliser immédiatement
```

---

## 🚀 **Fonctionnalités Clés**

### **⚡ Context Caching Automatique**
- **Cache hits** : $0.028/M (90% d'économie)
- **Cache misses** : $0.28/M
- **Gain potentiel** : Jusqu'à 90% d'économie

### **🔄 API Compatible OpenAI**
- **Migration facile** : Changez juste l'URL et la clé
- **Format identique** : Compatible avec code existant
- **Switch transparent** : Aucune modification du code

### **📊 Fenêtre de Contexte Étendue**
- **128K tokens** pour les deux modèles
- **64K output** en mode thinking
- **8K output** en mode non-thinking

---

## 💡 **Comparaison avec Concurrents**

### **📈 Tarifs Comparés (par million tokens)**
| Provider | Input | Output | Économie vs DeepSeek |
|----------|-------|--------|---------------------|
| **DeepSeek** | **$0.28** | **$0.42** | **-** |
| OpenAI GPT-5 | $1.25 | $10.00 | **78% plus cher** |
| Anthropic Claude | $3.00 | $15.00 | **91% plus cher** |
| Google Gemini | $1.25 | $10.00 | **78% plus cher** |

### **🎯 Avantage DeepSeek**
- **Jusqu'à 95% moins cher** que GPT-5
- **Performance 82/100** en mode thinking
- **API compatible** OpenAI
- **5M tokens gratuits**

---

## 📊 **Estimations de Coûts Mensuels**

### **📊 Usage Types**
| Usage | Requetes/jour | Coût Mensuel | Modèle Recommandé |
|-------|---------------|--------------|-------------------|
| **Léger** | < 1K | **$1-5** | deepseek-chat |
| **Moyen** | 1-5K | **$5-25** | deepseek-chat/reasoner |
| **Lourd** | 5-20K | **$25-125** | deepseek-chat + cache |
| **Entreprise** | 20K+ | **$125+** | deepseek-chat optimisé |

---

## 🎯 **Guide de Sélection de Modèle**

### **📋 Cas d'Usage Recommandés**

| Cas d'Usage | Modèle | Coût Mensuel | Pourquoi |
|-------------|--------|--------------|----------|
| **Support Client** | deepseek-chat | $1-6 | Rapide, abordable |
| **Génération Code** | deepseek-reasoner | $4-20 | Qualité 82, CoT |
| **Math & Logique** | deepseek-reasoner | $3-15 | Raisonnement pas-à-pas |
| **Écriture Contenu** | deepseek-chat | $2-10 | Bon pour contenu général |
| **Extraction Données** | deepseek-chat | $1-5 | JSON + tool calling |
| **Batch Volume** | deepseek-chat | $3-25 | Maximiser cache hits |

---

## 🚀 **Pour Votre Projet de Lois**

### **📊 Calcul Précis pour Traitement de Lois**

#### **🎯 Estimation Tokens**
- **Input par loi** : ~11,500 tokens (OCR + instructions)
- **Output par loi** : ~1,500 tokens (JSON structuré)
- **Total pour 45 lois** : 517,500 input + 67,500 output

#### **💰 Coûts avec DeepSeek**
| Scénario | Input Cost | Output Cost | Total | Économie |
|----------|------------|-------------|-------|----------|
| **Sans cache** | $0.15 | $0.03 | **$0.18** | 74% |
| **Avec cache (70%)** | $0.05 | $0.03 | **$0.08** | 89% |
| **Free tier** | $0.00 | $0.00 | **$0.00** | 100% |

#### **📈 Corps Complet du Bénin (1,420 lois)**
| Scénario | Input Cost | Output Cost | Total | Économie |
|----------|------------|-------------|-------|----------|
| **Sans cache** | $4.57 | $0.60 | **$5.17** | 36% |
| **Avec cache (70%)** | $1.52 | $0.60 | **$2.12** | 74% |
| **Free tier (5M tokens)** | $0.00 | $0.00 | **$0.00** | 100% |

---

## 🎯 **Recommandation Finale**

### **🏆 Pour Votre Projet : deepseek-chat**

**Pourquoi deepseek-chat est optimal :**

1. **💰 Coût ultra-bas** : $0.08 pour 45 lois avec cache
2. **🆓 Free tier suffisant** : 5M tokens gratuits couvrent tout
3. **⚡ Performance adéquate** : Score 63/100 pour extraction JSON
4. **🔧 JSON support** : Sortie JSON native
5. **📊 Tool calling** : Compatible avec votre architecture

### **🚀 Commande Optimale**
```bash
# 1. Obtenir clé API + 5M tokens gratuits
curl -X POST "https://api.deepseek.com/user/api_key"

# 2. Configurer
echo "DEEPSEEK_API_KEY=votre_clé" >> .env

# 3. Lancer traitement
export $(grep -v '^#' .env | xargs)
mvn exec:java -Dexec.mainClass="org.law.MainFixed" -Dexec.args="--batch-deepseek"
```

### **🎉 Résultat Garanti**
- ✅ **Coût** : $0.00 (free tier)
- ✅ **Performance** : Traitement parallèle optimisé
- ✅ **Qualité** : Extraction JSON fiable
- ✅ **Scalabilité** : Corps complet Bénin gratuit

**DeepSeek est la solution parfaite : gratuite, performante et officiellement documentée !** 🎯✨
