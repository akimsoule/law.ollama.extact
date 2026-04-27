package org.law.config;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Service centralisé de configuration pour l'application.
 * Charge les paramètres depuis config.properties et les variables d'environnement.
 */
public class Config {

    private static final Logger LOGGER = LoggerFactory.getLogger(Config.class);

    
    private static final String CONFIG_FILE = "config.properties";
    private final Properties properties;
    
    private static class Holder {
        static final Config INSTANCE = new Config();
    }
    
    private Config() {
        this.properties = new Properties();
        loadProperties();
    }
    
    public static Config getInstance() {
        return Holder.INSTANCE;
    }
    
    /**
     * Charge les propriétés depuis le fichier de configuration.
     */
    private void loadProperties() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (input == null) {
                LOGGER.error("[ConfigurationService] Fichier " + CONFIG_FILE + " non trouvé dans le classpath");
                return;
            }
            properties.load(input);
            LOGGER.info("[ConfigurationService] Configuration chargée depuis " + CONFIG_FILE);
        } catch (IOException ex) {
            LOGGER.error("[ConfigurationService] Erreur chargement configuration: " + ex.getMessage());
        }
    }
    
    /**
     * Récupère une propriété avec fallback vers les variables d'environnement.
     */
    public String getProperty(String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            // Fallback vers les variables d'environnement
            value = System.getenv(key.toUpperCase().replace('.', '_'));
            if (value != null) {
                LOGGER.info("[ConfigurationService] Utilisation variable d'environnement pour: " + key);
            }
        }
        return value;
    }
    
    /**
     * Récupère une propriété avec valeur par défaut.
     */
    public String getProperty(String key, String defaultValue) {
        String value = getProperty(key);
        return (value != null && !value.trim().isEmpty()) ? value : defaultValue;
    }
    
    /**
     * Récupère une propriété entière.
     */
    public int getIntProperty(String key, int defaultValue) {
        try {
            String value = getProperty(key);
            return value != null ? Integer.parseInt(value) : defaultValue;
        } catch (NumberFormatException e) {
            LOGGER.error("[ConfigurationService] Erreur parsing entier pour " + key + ", utilisation défaut: " + defaultValue);
            return defaultValue;
        }
    }
    
    /**
     * Récupère une propriété booléenne.
     */
    public boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = getProperty(key);
        if (value == null) return defaultValue;
        return Boolean.parseBoolean(value);
    }
    
    /**
     * Récupère une propriété double.
     */
    public double getDoubleProperty(String key, double defaultValue) {
        try {
            String value = getProperty(key);
            return value != null ? Double.parseDouble(value) : defaultValue;
        } catch (NumberFormatException e) {
            LOGGER.error("[ConfigurationService] Erreur parsing double pour " + key + ", utilisation défaut: " + defaultValue);
            return defaultValue;
        }
    }
    
    // Méthodes spécifiques pour DeepSeek
    public String getDeepSeekApiKey() {
        return getProperty("deepseek.api.key");
    }
    
    public String getDeepSeekDefaultModel() {
        return getProperty("deepseek.default.model", "deepseek-chat");
    }
    
    public int getDeepSeekBatchSize() {
        return getIntProperty("deepseek.batch.size", 10);
    }
    
    public double getDeepSeekTemperature() {
        return getDoubleProperty("deepseek.temperature", 0.1);
    }
    
    public int getDeepSeekMaxTokens() {
        return getIntProperty("deepseek.max.tokens", 4096);
    }
    
    public int getDeepSeekMaxRetries() {
        return getIntProperty("deepseek.max.retries", 3);
    }
    
    public long getDeepSeekRetryDelayMs() {
        return getIntProperty("deepseek.retry.delay.ms", 1000);
    }
    
    public int getDeepSeekTimeoutSeconds() {
        return getIntProperty("deepseek.timeout.seconds", 60);
    }
        
    // Méthodes spécifiques pour le traitement
    public boolean getRefreshOcr() {
        return getBooleanProperty("processing.refresh.ocr", false);
    }
    
    public int getMinYear() {
        return getIntProperty("processing.min.year", 1960);
    }
    
    public int getMaxYear() {
        return getIntProperty("processing.max.year", 2026);
    }
    
    /**
     * Affiche la configuration actuelle.
     */
    public void printConfiguration() {
        LOGGER.info("[ConfigurationService] Configuration actuelle:");
        LOGGER.info("  - DeepSeek Default Model: " + getDeepSeekDefaultModel());
        LOGGER.info("  - DeepSeek Batch Size: " + getDeepSeekBatchSize());
        LOGGER.info("  - DeepSeek Temperature: " + getDeepSeekTemperature());
        LOGGER.info("  - DeepSeek Max Retries: " + getDeepSeekMaxRetries());
        LOGGER.info("  - Refresh OCR: " + getRefreshOcr());
        LOGGER.info("  - Min Year: " + getMinYear());
        LOGGER.info("  - Max Year: " + getMaxYear());
        
        // Vérifier la clé API
        String apiKey = getDeepSeekApiKey();
        if (apiKey == null || apiKey.trim().isEmpty() || apiKey.equals("your_deepseek_api_key_here")) {
            LOGGER.error("[ConfigurationService] ⚠️  Clé API DeepSeek non configurée !");
            LOGGER.error("[ConfigurationService] Définissez 'deepseek.api.key' dans config.properties ou DEEPSEEK_API_KEY en environnement");
        } else {
            LOGGER.info("[ConfigurationService] ✅ Clé API DeepSeek configurée");
        }
    }
}
