package org.law.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Classe de configuration pour charger les propriétés depuis un fichier
 * config.properties.
 */
public class Config {

    private static final String CONFIG_FILE = "config.properties";
    private static final Properties properties = new Properties();

    static {
        try (InputStream input = Config.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (input == null) {
                throw new IOException("Fichier " + CONFIG_FILE + " non trouvé dans les ressources");
            }
            properties.load(input);
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement de " + CONFIG_FILE, e);
        }
    }

    private Config() {
        // Classe utilitaire, ne pas instancier
    }

    /**
     * Récupère une propriété par clé.
     *
     * @param key la clé de la propriété
     * @return la valeur de la propriété
     * @throws IllegalArgumentException si la propriété n'existe pas
     */
    public static String getProperty(String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("Propriété '" + key + "' non trouvée dans " + CONFIG_FILE);
        }
        return value;
    }

    /**
     * Récupère une propriété par clé avec une valeur par défaut.
     *
     * @param key          la clé de la propriété
     * @param defaultValue la valeur par défaut
     * @return la valeur de la propriété ou la valeur par défaut si elle n'existe
     *         pas
     */
    public static String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

}
