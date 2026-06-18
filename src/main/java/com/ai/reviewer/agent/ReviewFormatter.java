package com.ai.reviewer.agent;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class ReviewFormatter {

    public String format(ReviewResult result) {
        if (!result.success() && result.rawMarkdownFallback() != null) {
            return "### 🤖 AI Code Review Summary\n\n" + result.rawMarkdownFallback();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("### 🤖 AI Code Review Summary\n\n");

        if (result.summary() != null && !result.summary().isBlank()) {
            sb.append(result.summary()).append("\n\n");
        }

        if (result.issues() == null || result.issues().isEmpty()) {
            sb.append("✅ No issues found! Excellent work.");
            return sb.toString();
        }

        List<ReviewIssue> errors = result.issues().stream()
                .filter(i -> i.severity() != null && "Error".equalsIgnoreCase(i.severity().trim()))
                .toList();

        List<ReviewIssue> warnings = result.issues().stream()
                .filter(i -> i.severity() != null && "Warning".equalsIgnoreCase(i.severity().trim()))
                .toList();

        List<ReviewIssue> suggestions = result.issues().stream()
                .filter(i -> i.severity() != null && "Suggestion".equalsIgnoreCase(i.severity().trim()))
                .toList();

        List<ReviewIssue> others = result.issues().stream()
                .filter(i -> i.severity() == null 
                        || (!"Error".equalsIgnoreCase(i.severity().trim()) 
                            && !"Warning".equalsIgnoreCase(i.severity().trim()) 
                            && !"Suggestion".equalsIgnoreCase(i.severity().trim())))
                .toList();

        if (errors.isEmpty() && warnings.isEmpty() && suggestions.isEmpty() && others.isEmpty()) {
            sb.append("✅ No issues found! Excellent work.");
            return sb.toString();
        }

        if (!errors.isEmpty()) {
            sb.append("#### 🔴 Errors\n");
            for (ReviewIssue issue : errors) {
                appendIssue(sb, issue);
            }
            sb.append("\n");
        }

        if (!warnings.isEmpty()) {
            sb.append("#### 🟡 Warnings\n");
            for (ReviewIssue issue : warnings) {
                appendIssue(sb, issue);
            }
            sb.append("\n");
        }

        if (!suggestions.isEmpty()) {
            sb.append("#### 🔵 Suggestions\n");
            for (ReviewIssue issue : suggestions) {
                appendIssue(sb, issue);
            }
            sb.append("\n");
        }

        if (!others.isEmpty()) {
            sb.append("#### ℹ️ Other Issues\n");
            for (ReviewIssue issue : others) {
                appendIssue(sb, issue);
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    private void appendIssue(StringBuilder sb, ReviewIssue issue) {
        sb.append("- ");
        if (issue.file() != null && !issue.file().isBlank()) {
            sb.append("`").append(issue.file()).append("`");
            if (issue.line() != null && issue.line() > 0) {
                sb.append(":").append(issue.line());
            }
            sb.append(" — ");
        }
        sb.append(issue.message()).append("\n");
    }
}
