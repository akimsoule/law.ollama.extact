package org.law.service.embed;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Service centralisé pour générer des embeddings localement.
 * Utilise le modèle all-MiniLM-L6-v2-q (LangChain4J) sans dépendance API
 * externe.
 * Dimension: 384
 */
public class Embedder {

    private final EmbeddingModel embeddingModel;
    private static final int MAX_CHUNK_SIZE = 512; // all-MiniLM-L6-v2 max sequence length

    public Embedder() {
        this.embeddingModel = new AllMiniLmL6V2QuantizedEmbeddingModel();
    }

    /**
     * Génère un embedding pour un texte donné.
     * Si le texte est trop long, il est divisé en chunks et les vecteurs sont
     * moyennés.
     *
     * @param text le texte à embedder
     * @return le vecteur d'embedding (dimension 384)
     */
    public double[] embed(String text) {
        // Si le texte est trop long, le diviser en chunks
        if (text.length() > MAX_CHUNK_SIZE) {
            System.out.println("Texte trop long (" + text.length() + " caractères). Découpage en chunks...");
            List<String> chunks = splitIntoChunks(text, MAX_CHUNK_SIZE);
            return averageEmbeddings(chunks);
        }

        return embedSingleChunk(text);
    }

    /**
     * Génère un embedding pour un texte court.
     */
    private double[] embedSingleChunk(String text) {
        dev.langchain4j.data.embedding.Embedding embedding = embeddingModel.embed(text).content();
        float[] floatVector = embedding.vector();

        double[] doubleVector = new double[floatVector.length];
        for (int i = 0; i < floatVector.length; i++) {
            doubleVector[i] = floatVector[i];
        }

        return doubleVector;
    }

    /**
     * Divise un texte en chunks de taille maximale spécifiée.
     */
    private List<String> splitIntoChunks(String text, int maxSize) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = text.split("(?<=[.!?])\\s+");

        StringBuilder chunk = new StringBuilder();
        for (String sentence : sentences) {
            if ((chunk.length() + sentence.length() + 1) > maxSize && !chunk.isEmpty()) {
                chunks.add(chunk.toString().trim());
                chunk = new StringBuilder();
            }
            if (!chunk.isEmpty()) {
                chunk.append(" ");
            }
            chunk.append(sentence);
        }

        if (!chunk.isEmpty()) {
            chunks.add(chunk.toString().trim());
        }

        System.out.println("Texte divisé en " + chunks.size() + " chunks");
        return chunks;
    }

    /**
     * Calcule la moyenne des embeddings de plusieurs chunks.
     */
    private double[] averageEmbeddings(List<String> chunks) {
        if (chunks.isEmpty()) {
            throw new RuntimeException("Aucun chunk à vectoriser");
        }

        List<double[]> embeddings = new ArrayList<>();
        for (String chunk : chunks) {
            double[] embedding = embedSingleChunk(chunk);
            embeddings.add(embedding);
        }

        // Calculer la moyenne
        double[] averageEmbedding = new double[embeddings.get(0).length];
        for (double[] embedding : embeddings) {
            for (int i = 0; i < embedding.length; i++) {
                averageEmbedding[i] += embedding[i];
            }
        }

        for (int i = 0; i < averageEmbedding.length; i++) {
            averageEmbedding[i] /= embeddings.size();
        }

        System.out.println("Embeddings de " + embeddings.size() + " chunks moyennés");
        return averageEmbedding;
    }
}
