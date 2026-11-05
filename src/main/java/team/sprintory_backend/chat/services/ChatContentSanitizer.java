package team.sprintory_backend.chat.services;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;

import java.util.Set;

/**
 * TipTap JSON allowlist. Untrusted HTML, arbitrary attrs/marks and dangerous link schemes are
 * never persisted. Mention display text is always derived from the current user record.
 */
@Component
@RequiredArgsConstructor
public class ChatContentSanitizer {
    private static final int MAX_NODES = 400;
    private static final int MAX_CHARS = 10000;
    private static final Set<String> CONTAINERS = Set.of(
            "doc", "paragraph", "heading", "bulletList", "orderedList", "listItem", "blockquote", "codeBlock");
    private static final Set<String> LEAVES = Set.of("text", "hardBreak", "mention");
    private static final Set<String> MARKS = Set.of("bold", "italic", "underline", "strike", "code", "link");

    private final ObjectMapper mapper;
    private final ProjectMemberRepository members;
    private final UserRepository users;

    public JsonNode sanitize(Long projectId, JsonNode input) {
        if (input == null || !input.isObject() || !"doc".equals(input.path("type").asText())) {
            throw invalid();
        }
        Counter limit = new Counter();
        ObjectNode clean = sanitizeNode(projectId, input, limit, 0);
        if (limit.chars == 0) {
            throw new BadRequestException("Chat message cannot be empty.");
        }
        return clean;
    }

    private ObjectNode sanitizeNode(Long projectId, JsonNode node, Counter counter, int depth) {
        if (depth > 15 || ++counter.nodes > MAX_NODES || !node.isObject() || !node.path("type").isTextual()) {
            throw invalid();
        }
        String type = node.path("type").asText();
        if (!CONTAINERS.contains(type) && !LEAVES.contains(type)) {
            throw invalid();
        }
        if (hasExtra(node, "type", "content", "text", "marks", "attrs")) {
            throw invalid();
        }
        ObjectNode clean = mapper.createObjectNode().put("type", type);
        if (type.equals("text")) {
            if (!node.path("text").isTextual() || node.has("content") || node.has("attrs")) throw invalid();
            String value = node.get("text").asText();
            counter.chars += value.length();
            if (counter.chars > MAX_CHARS) throw invalid();
            clean.put("text", value);
            if (node.has("marks")) clean.set("marks", sanitizeMarks(node.get("marks")));
            return clean;
        }
        if (node.has("marks") || node.has("text")) throw invalid();
        if (type.equals("mention")) {
            JsonNode attrs = node.path("attrs");
            if (!attrs.isObject() || hasExtra(attrs, "id", "label")) throw invalid();
            String rawId = attrs.path("id").asText("");
            long userId;
            try {
                userId = Long.parseLong(rawId);
            } catch (NumberFormatException e) {
                throw invalid();
            }
            if (userId < 1 || !members.existsByProjectIdAndUserId(projectId, userId)) {
                throw new BadRequestException("Mention must reference a current project member.");
            }
            User user = users.findById(userId).orElseThrow(ChatContentSanitizer::invalid);
            String label = user.getFirstName() + " " + user.getLastName();
            clean.set("attrs", mapper.createObjectNode().put("id", String.valueOf(userId)).put("label", label));
            counter.chars += label.length();
            if (counter.chars > MAX_CHARS) throw invalid();
            if (node.has("content")) throw invalid();
            return clean;
        }
        if (type.equals("hardBreak")) {
            if (node.has("content") || node.has("attrs")) throw invalid();
            counter.chars++;
            return clean;
        }
        if (type.equals("heading")) {
            JsonNode attrs = node.path("attrs");
            if (!attrs.isObject() || hasExtra(attrs, "level") || !attrs.path("level").isInt()) throw invalid();
            int level = attrs.path("level").intValue();
            if (level < 1 || level > 3) throw invalid();
            clean.set("attrs", mapper.createObjectNode().put("level", level));
        } else if (node.has("attrs")) throw invalid();
        if (node.has("content")) {
            JsonNode children = node.get("content");
            if (!children.isArray()) throw invalid();
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode child : children) out.add(sanitizeNode(projectId, child, counter, depth + 1));
            clean.set("content", out);
        }
        return clean;
    }

    private ArrayNode sanitizeMarks(JsonNode marks) {
        if (!marks.isArray() || marks.size() > 8) throw invalid();
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode mark : marks) {
            if (!mark.isObject() || !mark.path("type").isTextual() || hasExtra(mark, "type", "attrs")) throw invalid();
            String type = mark.path("type").asText();
            if (!MARKS.contains(type)) throw invalid();
            ObjectNode clean = mapper.createObjectNode().put("type", type);
            if (type.equals("link")) {
                JsonNode attrs = mark.path("attrs");
                if (!attrs.isObject() || hasExtra(attrs, "href", "target") || !attrs.path("href").isTextual()) throw invalid();
                String href = attrs.path("href").asText().trim();
                if (href.length() > 2048 || !(href.startsWith("https://") || href.startsWith("http://")
                        || href.startsWith("mailto:"))) throw invalid();
                clean.set("attrs", mapper.createObjectNode().put("href", href));
            } else if (mark.has("attrs")) throw invalid();
            result.add(clean);
        }
        return result;
    }

    private static boolean hasExtra(JsonNode object, String... allowed) {
        Set<String> keys = Set.of(allowed);
        var iter = object.propertyNames().iterator();
        while (iter.hasNext()) if (!keys.contains(iter.next())) return true;
        return false;
    }

    private static BadRequestException invalid() {
        return new BadRequestException("Invalid or unsafe chat document.");
    }

    private static class Counter {
        int nodes;
        int chars;
    }
}
