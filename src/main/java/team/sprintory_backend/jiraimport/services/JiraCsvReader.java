package team.sprintory_backend.jiraimport.services;

import org.springframework.stereotype.Component;
import team.sprintory_backend.exceptionHandler.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Strict RFC-4180-style parser. Jira's CSV repeats header names and has multiline descriptions. */
@Component
public class JiraCsvReader {
    public record Issue(String issueId, String key, String projectKey, String type, String title,
                        String status, String priority, String assigneeId, String assigneeName,
                        String parentId, String parentKey, String sprint, String description,
                        String points, String originalEstimate, String rank, List<String> labels,
                        List<String> comments, String created, String updated) {
    }

    public record Parsed(List<Issue> issues, String projectKey) {
    }

    public Parsed parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 10_000_000) {
            throw new BadRequestException("Jira CSV must be between 1 byte and 10 MB.");
        }
        String csv = new String(bytes, StandardCharsets.UTF_8);
        if (csv.startsWith("\uFEFF")) csv = csv.substring(1);
        List<List<String>> rows = split(csv);
        if (rows.size() < 2) throw new BadRequestException("The Jira CSV contains no issues.");
        List<String> header = rows.remove(0);
        Map<String, List<Integer>> positions = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            positions.computeIfAbsent(header.get(i).trim(), ignored -> new ArrayList<>()).add(i);
        }
        for (String required : List.of("Issue id", "Issue key", "Project key", "Issue Type", "Summary",
                "Status", "Custom field (Rank)")) {
            if (!positions.containsKey(required)) throw new BadRequestException("Missing Jira CSV field: " + required);
        }
        if (rows.size() > 10_000) throw new BadRequestException("A single import supports at most 10,000 issues.");
        List<Issue> issues = new ArrayList<>();
        String projectKey = null;
        for (List<String> row : rows) {
            if (row.size() != header.size()) {
                throw new BadRequestException("Jira CSV row " + (issues.size() + 2) + " has " + row.size()
                        + " fields; expected " + header.size() + ".");
            }
            String sourceProject = one(row, positions, "Project key").trim();
            if (projectKey == null) projectKey = sourceProject;
            if (!projectKey.equals(sourceProject)) throw new BadRequestException("CSV contains multiple Jira projects.");
            String key = one(row, positions, "Issue key").trim();
            String id = one(row, positions, "Issue id").trim();
            if (key.isEmpty() || id.isEmpty()) throw new BadRequestException("Jira issue key/id is missing.");
            List<String> labels = new ArrayList<>();
            for (String label : all(row, positions, "Labels")) {
                if (!label.isBlank()) labels.add(label.trim());
            }
            issues.add(new Issue(id, key, sourceProject, one(row, positions, "Issue Type").trim(),
                    one(row, positions, "Summary").trim(), one(row, positions, "Status").trim(),
                    one(row, positions, "Priority").trim(), one(row, positions, "Assignee Id").trim(),
                    one(row, positions, "Assignee").trim(), one(row, positions, "Parent").trim(),
                    one(row, positions, "Parent key").trim(), one(row, positions, "Sprint").trim(),
                    one(row, positions, "Description"),
                    one(row, positions, "Custom field (Story point estimate)").trim(),
                    one(row, positions, "Original estimate").trim(),
                    one(row, positions, "Custom field (Rank)").trim(),
                    List.copyOf(labels), all(row, positions, "Comment").stream()
                            .filter(c -> !c.isBlank()).toList(),
                    one(row, positions, "Created"), one(row, positions, "Updated")));
        }
        if (projectKey == null || projectKey.isBlank()) throw new BadRequestException("Jira project key is empty.");
        return new Parsed(List.copyOf(issues), projectKey);
    }

    private static String one(List<String> row, Map<String, List<Integer>> positions, String name) {
        List<Integer> indexes = positions.get(name);
        return indexes == null || indexes.isEmpty() ? "" : row.get(indexes.get(0));
    }

    private static List<String> all(List<String> row, Map<String, List<Integer>> positions, String name) {
        return positions.getOrDefault(name, List.of()).stream().map(row::get).toList();
    }

    /** Handles escaped double-quotes, embedded newlines, CRLF, and empty final columns. */
    static List<List<String>> split(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;
        boolean atFieldStart = true;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        value.append('"'); i++;
                    } else {
                        quoted = false; closedQuote = true;
                    }
                } else { value.append(c); }
            } else if (c == '"' && atFieldStart) {
                quoted = true; atFieldStart = false;
            } else if (c == ',' || c == '\n' || c == '\r') {
                row.add(value.toString()); value.setLength(0);
                atFieldStart = true; closedQuote = false;
                if (c != ',') {
                    rows.add(row); row = new ArrayList<>();
                    if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                }
            } else {
                if (closedQuote) throw new BadRequestException("Invalid characters after a closing CSV quote.");
                if (c == '"') throw new BadRequestException("Unexpected quote in Jira CSV.");
                value.append(c); atFieldStart = false;
            }
        }
        if (quoted) throw new BadRequestException("Unterminated quoted field in Jira CSV.");
        if (!row.isEmpty() || value.length() > 0 || !atFieldStart) {
            row.add(value.toString()); rows.add(row);
        }
        return rows;
    }
}
