package org.law.service.parse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.law.model.HeaderObject;
import org.law.model.LawNode;
import org.law.model.LawSection;
import org.law.utils.BoolString;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Algorithmic parser to build a legal text tree from a law document.
 * 
 * Algorithm:
 * 1. Extract body text
 * 2. Use BodyParser to extract articles
 * 3. Detect all legal markers (TITRE, CHAPITRE, SECTION, ARTICLE) hierarchically
 * 4. Build a hierarchical tree with a stack
 * 5. Attach normative text only to articles
 */
public class LawNodeParser implements BoolString {


    // Patterns to detect legal structures (including OCR variants where 'I' may be read as 'l')
    private static final Pattern PATTERN_LIVRE = Pattern.compile("^\\s*LIVRE\\s+([IVXlL0-9]+|PREMIER|1er|1ère)\\s*[:-]?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_TITRE = Pattern.compile("^\\s*TITRE\\s+([IVXlL0-9]+|PREMIER|1er|1ère)\\s*[:-]?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_CHAPITRE = Pattern.compile("^\\s*CHAPITRE\\s+([IVXlL0-9]+|PREMIER|1er|1ère)\\s*[:-]?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_SECTION = Pattern.compile("^\\s*SECTION\\s+([0-9lL]+|PREMIER|PREMIERE|1er|1ère)\\s*[:-]?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_ARTICLE_NUMBER = Pattern.compile("Article\\s+(\\d+|1er|1ère|premier|premiere)");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parses a law text and returns the legal tree
     */
    public LawNode parse(LawSection lawSection) {
        // Initialization
        LawNode rootNode = LawNode.builder()
                .type(LawNode.NodeType.LAW)
                .build();

        @SuppressWarnings("unchecked")
        Map<String, String> metadata = objectMapper.convertValue(lawSection.getHeaderObject(), Map.class);
        rootNode.setMetadata(metadata);

        String[] bodylines = lawSection.getBody().split("\n");
        // Step 1: Build hierarchical tree using stack-based algorithm
        Deque<LawNode> stack = new LinkedList<>();
        stack.push(rootNode);
        LawNode lastStructureNode = rootNode;
        LawNode lastArticleNode = null;
        StringBuilder pendingText = new StringBuilder();

        // Index counters for each node type
        int livreIndex = 0;
        int titreIndex = 0;
        int chapitreIndex = 0;
        int sectionIndex = 0;
        int articleIndex = 0;

        for (String line : bodylines) {
            String rawLine = line;
            line = line.trim();
            if (line.isEmpty()) continue;

            // Check for LIVRE
            java.util.regex.Matcher m = PATTERN_LIVRE.matcher(line);
            if (m.matches()) {
                appendText(lastArticleNode != null ? lastArticleNode : lastStructureNode, pendingText.toString());
                pendingText.setLength(0);
                lastArticleNode = null;

                LawNode node = LawNode.builder()
                        .type(LawNode.NodeType.BOOK)
                        .build();
                Map<String, String> bookMetadata = new HashMap<>();
                bookMetadata.put("number", m.group(1));
                bookMetadata.put("text", rawLine);
                bookMetadata.put("index", String.valueOf(++livreIndex));
                node.setMetadata(bookMetadata);
                adjustStack(stack, LawNode.NodeType.BOOK);
                if (!stack.isEmpty()) stack.peek().addChild(node);
                stack.push(node);
                lastStructureNode = node;
                continue;
            }

            // Check for TITRE
            m = PATTERN_TITRE.matcher(line);
            if (m.matches()) {
                appendText(lastArticleNode != null ? lastArticleNode : lastStructureNode, pendingText.toString());
                pendingText.setLength(0);
                lastArticleNode = null;

                LawNode node = LawNode.builder()
                        .type(LawNode.NodeType.TITLE)
                        .build();
                Map<String, String> titleMetadata = new HashMap<>();
                titleMetadata.put("number", m.group(1));
                titleMetadata.put("text", rawLine);
                titleMetadata.put("index", String.valueOf(++titreIndex));
                node.setMetadata(titleMetadata);
                adjustStack(stack, LawNode.NodeType.TITLE);
                if (!stack.isEmpty()) stack.peek().addChild(node);
                stack.push(node);
                lastStructureNode = node;
                continue;
            }

            // Check for CHAPITRE
            m = PATTERN_CHAPITRE.matcher(line);
            if (m.matches()) {
                appendText(lastArticleNode != null ? lastArticleNode : lastStructureNode, pendingText.toString());
                pendingText.setLength(0);
                lastArticleNode = null;

                LawNode node = LawNode.builder()
                        .type(LawNode.NodeType.CHAPTER)
                        .build();
                Map<String, String> chapterMetadata = new HashMap<>();
                chapterMetadata.put("number", m.group(1));
                chapterMetadata.put("text", rawLine);
                chapterMetadata.put("index", String.valueOf(++chapitreIndex));
                node.setMetadata(chapterMetadata);
                adjustStack(stack, LawNode.NodeType.CHAPTER);
                if (!stack.isEmpty()) stack.peek().addChild(node);
                stack.push(node);
                lastStructureNode = node;
                continue;
            }

            // Check for SECTION
            m = PATTERN_SECTION.matcher(line);
            if (m.matches()) {
                appendText(lastArticleNode != null ? lastArticleNode : lastStructureNode, pendingText.toString());
                pendingText.setLength(0);
                lastArticleNode = null;

                LawNode node = LawNode.builder()
                        .type(LawNode.NodeType.CHAPTER)  // Use CHAPTER for SECTION
                        .build();
                Map<String, String> sectionMetadata = new HashMap<>();
                sectionMetadata.put("number", m.group(1));
                sectionMetadata.put("text", rawLine);
                sectionMetadata.put("index", String.valueOf(++sectionIndex));
                node.setMetadata(sectionMetadata);
                adjustStack(stack, LawNode.NodeType.CHAPTER);
                if (!stack.isEmpty()) stack.peek().addChild(node);
                stack.push(node);
                lastStructureNode = node;
                continue;
            }

            // Check for ARTICLE
            if (stratLookLikeArticle(line)) {
                appendText(lastArticleNode != null ? lastArticleNode : lastStructureNode, pendingText.toString());
                pendingText.setLength(0);

                String articleNumber = extractArticleNumber(line);
                
                LawNode articleNode = LawNode.builder()
                        .type(LawNode.NodeType.ARTICLE)
                        .build();
                
                Map<String, String> articleMetadata = new HashMap<>();
                articleMetadata.put("number", articleNumber);
                articleMetadata.put("text", line);
                articleMetadata.put("index", String.valueOf(++articleIndex));
                articleNode.setMetadata(articleMetadata);
                
                adjustStack(stack, LawNode.NodeType.ARTICLE);
                if (!stack.isEmpty()) stack.peek().addChild(articleNode);
                lastArticleNode = articleNode;
                continue;
            }

            // Accumulate free text until next detected node
            pendingText.append(rawLine).append("\n");
        }

        // Flush any trailing text to the last article or structure node
        appendText(lastArticleNode != null ? lastArticleNode : lastStructureNode, pendingText.toString());

        return rootNode;
    }

    /**
     * Parses a list of text lines using BodyParser for article extraction
     * Builds a hierarchical tree with LIVRE, TITRE, CHAPITRE, SECTION structures
     */
//    public LawNode parse(List<String> lines, String title) {
//
//
//        if (lines == null || lines.isEmpty()) {
//            return rootNode;
//        }
//
//        String fullText = String.join("\n", lines);
//
//
//        // Step 2: Extract articles using BodyParser
//        List<String> extractedArticles = bodyParser.extractArticles(fullText);
//
//        // Step 3: Add articles to the tree
//        // Articles are attached to the deepest level that can contain them
//        for (String articleText : extractedArticles) {
//            // Extract article number
//            String number = extractArticleNumber(articleText);
//
//            // Remove "Article X :" from the beginning to get clean content
//            String cleanContent = articleText;
//            int colonIndex = articleText.indexOf(':');
//            if (colonIndex != -1 && colonIndex < 20) {
//                cleanContent = articleText.substring(colonIndex + 1).trim();
//            }
//
//            // Title is first 80 chars of clean content
//            String articleTitle = cleanContent.substring(0, Math.min(80, cleanContent.length()));
//
//            LawNode newArticle = LawNode.builder()
//                .type(LawNode.NodeType.ARTICLE)
//                .number(number)
//                .title(articleTitle)
//                .text(cleanContent)
//                .build();
//
//            // Find the deepest node that can contain this article
//            findDeepestAndAttach(rootNode, newArticle);
//        }
//
//        return rootNode;
//    }

    /**
     * Recursively finds the deepest node that can contain this article
     * and attaches it there
     */
    private void findDeepestAndAttach(LawNode current, LawNode article) {
        // Try to find a leaf-level structure child that can contain articles
        LawNode bestParent = current;
        
        // Look for the deepest structure that can contain articles
        for (LawNode child : current.getChildren()) {
            if (isStructure(child.getType())) {
                // This is a structure node, recurse to find deeper containers
                findDeepestAndAttach(child, article);
                return;
            }
        }
        
        // No child structures, attach to current node if it can contain articles
        if (canContainArticle(current.getType())) {
            current.addChild(article);
        }
    }

    /**
     * Checks if a node type is a structure (not an article)
     */
    private boolean isStructure(LawNode.NodeType type) {
        return type == LawNode.NodeType.BOOK || 
               type == LawNode.NodeType.TITLE || 
               type == LawNode.NodeType.CHAPTER;
    }

    /**
     * Checks if a node type can contain articles directly
     */
    private boolean canContainArticle(LawNode.NodeType type) {
        return type == LawNode.NodeType.LAW || 
               type == LawNode.NodeType.BOOK || 
               type == LawNode.NodeType.TITLE || 
               type == LawNode.NodeType.CHAPTER;
    }

    /**
     * Adjusts the stack based on containment rules
     */
    private void adjustStack(Deque<LawNode> stack, LawNode.NodeType newType) {
        while (stack.size() > 1) {
            LawNode top = stack.peek();
            if (canContain(top.getType(), newType)) {
                break;
            }
            stack.pop();
        }
    }

    /**
     * Checks if a parent type can contain a child type
     */
    private boolean canContain(LawNode.NodeType parent, LawNode.NodeType child) {
        if (parent == LawNode.NodeType.LAW) {
            return child != LawNode.NodeType.LAW;
        }
        if (parent == LawNode.NodeType.BOOK) {
            return child == LawNode.NodeType.TITLE || child == LawNode.NodeType.CHAPTER || child == LawNode.NodeType.ARTICLE;
        }
        if (parent == LawNode.NodeType.TITLE) {
            return child == LawNode.NodeType.CHAPTER || child == LawNode.NodeType.ARTICLE;
        }
        if (parent == LawNode.NodeType.CHAPTER) {
            return child == LawNode.NodeType.ARTICLE;
        }
        return false;
    }

    /**
     * Extract article number from article text
     */
    private String extractArticleNumber(String articleFirstLine) {
        // Try to extract number from "Article X" or similar patterns
        java.util.regex.Matcher matcher = PATTERN_ARTICLE_NUMBER.matcher(articleFirstLine);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "1";
    }

    /**
     * Cleans a line (multiple spaces, invisible characters, etc.)
     */
    private String clean(String line) {
        if (line == null) {
            return "";
        }
        // Remove multiple spaces
        line = line.replaceAll("\\s+", " ");
        // Trim
        line = line.trim();
        // Remove control characters
        line = line.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
        return line;
    }

    /**
     * Displays the tree in text form (for debug)
     */
    public String displayTree(LawNode root) {
        return displayTreeRecursive(root, 0);
    }

    private void appendText(LawNode node, String text) {
        if (node == null) {
            return;
        }
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.isEmpty()) {
            return;
        }
        String existing = node.getMetadataValue("text");
        if (existing == null || existing.isEmpty()) {
            node.addMetadata("text", cleaned);
        } else {
            node.addMetadata("text", existing + "\n" + cleaned);
        }
    }

    private String displayTreeRecursive(LawNode node, int depth) {
        StringBuilder sb = new StringBuilder();
        String indentation = "  ".repeat(depth);
        
        sb.append(indentation).append("├─ ");
        sb.append(node.getType());
        
        String number = node.getMetadataValue("number");
        if (number != null && !number.isEmpty()) {
            sb.append(" ").append(number);
        }
        
        String title = node.getMetadataValue("title");
        if (title != null && !title.isEmpty()) {
            sb.append(" : ").append(title);
        }
        
        if (node.getType() == LawNode.NodeType.ARTICLE) {
            String text = node.getMetadataValue("text");
            if (text != null && !text.isEmpty()) {
                String preview = text.substring(0, Math.min(50, text.length())).replace("\n", " ");
                sb.append(" [").append(preview).append("...]");
            }
        }
        sb.append("\n");

        for (LawNode child : node.getChildren()) {
            sb.append(displayTreeRecursive(child, depth + 1));
        }

        return sb.toString();
    }
}
