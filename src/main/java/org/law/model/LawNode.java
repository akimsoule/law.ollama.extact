package org.law.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Representation of a node in the legal text tree.
 * Conceptual model for parsing laws of Benin.
 *
 * Possible hierarchy:
 * LAW → BOOK → TITLE → CHAPTER → ARTICLE
 */
@Data
@Builder
public class LawNode {

    public enum NodeType {
        LAW, BOOK, TITLE, CHAPTER, ARTICLE
    }

    /**
     * Type of the node (LAW, BOOK, TITLE, CHAPTER, ARTICLE)
     */
    private NodeType type;

    /**
     * Children of the current node
     */
    @Builder.Default
    private List<LawNode> children = new ArrayList<>();

    /**
     * Metadata for additional information (header, footer, year, etc.)
     */
    @Builder.Default
    private Map<String, String> metadata = new HashMap<>();

    /**
     * Parent of the current node (bidirectional reference)
     */
    @JsonIgnore
    private LawNode parent;

    /**
     * Adds a child to this node
     */
    public void addChild(LawNode child) {
        child.setParent(this);
        this.children.add(child);
    }

    /**
     * Adds a metadata entry
     */
    public void addMetadata(String key, String value) {
        this.metadata.put(key, value);
    }

    /**
     * Gets a metadata value by key
     */
    public String getMetadataValue(String key) {
        return this.metadata.get(key);
    }

    /**
     * Returns the full path of the node in the tree
     * Ex: "LAW → TITLE 1 → CHAPTER 2 → ARTICLE 5"
     */
    public String getFullPath() {
        StringBuilder sb = new StringBuilder();
        List<LawNode> path = new ArrayList<>();
        
        LawNode current = this;
        while (current != null) {
            path.add(0, current);
            current = current.getParent();
        }

        for (int i = 0; i < path.size(); i++) {
            LawNode node = path.get(i);
            sb.append(node.getType());
            if (i < path.size() - 1) {
                sb.append(" → ");
            }
        }
        
        return sb.toString();
    }

    /**
     * Recursively counts the number of articles in the subtree
     */
    public int countArticles() {
        if (this.type == NodeType.ARTICLE) {
            return 1;
        }
        return this.children.stream()
                .mapToInt(LawNode::countArticles)
                .sum();
    }

    /**
     * Collects all articles from the subtree
     */
    @JsonIgnore
    public List<LawNode> getAllArticles() {
        List<LawNode> articles = new ArrayList<>();
        if (this.type == NodeType.ARTICLE) {
            articles.add(this);
        } else {
            for (LawNode child : this.children) {
                articles.addAll(child.getAllArticles());
            }
        }
        
        // Sort articles by index in ascending order
        articles.sort((a, b) -> {
            String indexA = a.getMetadataValue("index");
            String indexB = b.getMetadataValue("index");
            
            if (indexA == null || indexB == null) {
                return 0;
            }
            
            try {
                int numA = Integer.parseInt(indexA);
                int numB = Integer.parseInt(indexB);
                return Integer.compare(numA, numB);
            } catch (NumberFormatException e) {
                return indexA.compareTo(indexB);
            }
        });
        
        return articles;
    }

    /**
     * Collects all nodes of a given type from the subtree
     */
    @JsonIgnore
    public List<LawNode> getAllOfType(NodeType targetType) {
        List<LawNode> nodes = new ArrayList<>();
        if (this.type == targetType) {
            nodes.add(this);
        }
        for (LawNode child : this.children) {
            nodes.addAll(child.getAllOfType(targetType));
        }

        // Sort by metadata index if present
        nodes.sort((a, b) -> {
            String indexA = a.getMetadataValue("index");
            String indexB = b.getMetadataValue("index");

            if (indexA == null || indexB == null) {
                return 0;
            }

            try {
                int numA = Integer.parseInt(indexA);
                int numB = Integer.parseInt(indexB);
                return Integer.compare(numA, numB);
            } catch (NumberFormatException e) {
                return indexA.compareTo(indexB);
            }
        });

        return nodes;
    }

    /** Convenience accessors for each node type */
    @JsonIgnore
    public List<LawNode> getAllBooks() { return getAllOfType(NodeType.BOOK); }
    @JsonIgnore
    public List<LawNode> getAllTitles() { return getAllOfType(NodeType.TITLE); }
    @JsonIgnore
    public List<LawNode> getAllChapters() { return getAllOfType(NodeType.CHAPTER); }
    @JsonIgnore
    public List<LawNode> getAllSections() { return getAllOfType(NodeType.CHAPTER); /* SECTION mapped to CHAPTER type */ }

    @Override
    public String toString() {
        return String.format("LawNode - %s - %s", type, metadata.get("number"));
    }
}
