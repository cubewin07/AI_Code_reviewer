package com.ai.reviewer.service;

import com.ai.reviewer.dto.NineRouterDto.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class PromptBuilder {

    /** Overhead tokens per message (role, formatting, separators). */
    private static final int MESSAGE_OVERHEAD_TOKENS = 20;

    /** Max characters for a single observation/tool output before truncation. */
    private static final int DEFAULT_MAX_OBSERVATION_CHARS = 8000;

    /** Initial user context (PR description, event summary) gets a larger cap. */
    private static final int INITIAL_CONTEXT_MAX_CHARS = 16000;

    private static final String TRUNCATION_MARKER = "\n... [content truncated due to size limit] ...";

    // ──────────────────────────── Token Estimation ────────────────────────────

    /**
     * Estimates token count using the char / 4 heuristic.
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (text.length() + 3) / 4;
    }

    /**
     * Estimates total token count for a list of messages, including per-message overhead.
     */
    public int estimateTokens(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ChatMessage msg : messages) {
            total += estimateTokens(msg.content()) + MESSAGE_OVERHEAD_TOKENS;
        }
        return total;
    }

    // ──────────────────────────── Observation Truncation ────────────────────────────

    /**
     * Truncates a single observation/tool output to a maximum character limit.
     * Keeps the beginning of the content (headers and first lines are typically most informative).
     */
    public String truncateObservation(String content, int maxChars) {
        if (content == null || content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars) + TRUNCATION_MARKER;
    }

    // ──────────────────────────── Multi-Turn Compaction ────────────────────────────

    /**
     * Builds an OpenAI-compatible messages array from a system prompt and multi-turn
     * conversation history, applying intelligent compaction to fit within the token budget.
     *
     * <p>The expected conversation structure for a ReAct agent is:
     * <pre>
     *   system:    "You are a code reviewer…"
     *   user:      "Review this PR …" (initial context)
     *   assistant: "Thought: I need to see the diff\nAction: get_diff\n…"
     *   user:      "Observation: [diff output]"
     *   assistant: "Thought: File X looks problematic…\nAction: get_file_content\n…"
     *   user:      "Observation: [file content]"
     *   …
     * </pre>
     *
     * <p>Compaction phases (applied in order until the budget is met):
     * <ol>
     *   <li><b>Phase 1 — Observation caps:</b> Truncate individual observation messages
     *       (user messages after the first) to {@value DEFAULT_MAX_OBSERVATION_CHARS} chars,
     *       and the initial user context to {@value INITIAL_CONTEXT_MAX_CHARS} chars.</li>
     *   <li><b>Phase 2 — Sliding window:</b> Drop the oldest assistant→user (thought→observation)
     *       pairs while preserving the system prompt and initial user context message.</li>
     *   <li><b>Phase 3 — Aggressive truncation:</b> Proportionally truncate all remaining
     *       non-system messages to fit within the budget.</li>
     * </ol>
     *
     * @param systemPrompt         the system instruction (never dropped)
     * @param conversationHistory   the ordered list of user/assistant messages (excluding system)
     * @param maxTokensBudget       the maximum token count for the entire messages array
     * @return compacted list of {@link ChatMessage} ready for the LLM API
     */
    public List<ChatMessage> compactConversation(
            String systemPrompt,
            List<ChatMessage> conversationHistory,
            int maxTokensBudget) {

        if (systemPrompt == null) systemPrompt = "";
        if (conversationHistory == null) conversationHistory = List.of();

        List<ChatMessage> result = new ArrayList<>();
        result.add(new ChatMessage("system", systemPrompt));
        result.addAll(conversationHistory);

        int totalTokens = estimateTokens(result);
        if (totalTokens <= maxTokensBudget) {
            return result;
        }

        // ── Phase 1: Cap individual observation sizes ──
        log.info("Compaction Phase 1: Capping observation sizes. Current tokens: {}, budget: {}",
                totalTokens, maxTokensBudget);
        result = applyObservationCaps(result);
        totalTokens = estimateTokens(result);
        if (totalTokens <= maxTokensBudget) {
            return result;
        }

        // ── Phase 2: Sliding window — drop oldest thought+observation pairs ──
        log.info("Compaction Phase 2: Sliding window. Current tokens: {}, budget: {}",
                totalTokens, maxTokensBudget);
        result = applySlidingWindow(result, maxTokensBudget);
        totalTokens = estimateTokens(result);
        if (totalTokens <= maxTokensBudget) {
            return result;
        }

        // ── Phase 3: Aggressive proportional truncation ──
        log.info("Compaction Phase 3: Aggressive truncation. Current tokens: {}, budget: {}",
                totalTokens, maxTokensBudget);
        result = applyAggressiveTruncation(result, maxTokensBudget);

        return result;
    }

    /**
     * Phase 1: Truncate observation messages to character caps.
     * The initial user context message gets a larger cap; subsequent user messages
     * (tool observations) get a standard cap.
     */
    private List<ChatMessage> applyObservationCaps(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>();
        boolean firstUserSeen = false;

        for (ChatMessage msg : messages) {
            if ("user".equals(msg.role())) {
                if (!firstUserSeen) {
                    firstUserSeen = true;
                    result.add(new ChatMessage(msg.role(),
                            truncateObservation(msg.content(), INITIAL_CONTEXT_MAX_CHARS)));
                } else {
                    result.add(new ChatMessage(msg.role(),
                            truncateObservation(msg.content(), DEFAULT_MAX_OBSERVATION_CHARS)));
                }
            } else {
                result.add(msg);
            }
        }
        return result;
    }

    /**
     * Phase 2: Drop the oldest assistant+user pairs (after system + first user message)
     * until the conversation fits within the budget or only the minimum messages remain.
     * A context marker is inserted so the LLM knows earlier context was removed.
     */
    private List<ChatMessage> applySlidingWindow(List<ChatMessage> messages, int maxTokensBudget) {
        if (messages.size() <= 2) {
            return messages;
        }

        // Preserve: system (index 0) + first user message (index 1)
        List<ChatMessage> preserved = new ArrayList<>();
        preserved.add(messages.get(0));
        preserved.add(messages.get(1));

        List<ChatMessage> droppable = new ArrayList<>(messages.subList(2, messages.size()));
        int totalDropped = 0;

        while (!droppable.isEmpty() && estimateTokens(combine(preserved, droppable)) > maxTokensBudget) {
            // Drop first message (should be an assistant thought)
            ChatMessage dropped = droppable.remove(0);
            totalDropped++;
            log.debug("Sliding window: dropped {} message ({} chars)", dropped.role(),
                    dropped.content() != null ? dropped.content().length() : 0);

            // If the next message is a user observation, drop it too (they are a pair)
            if (!droppable.isEmpty() && "user".equals(droppable.get(0).role())) {
                dropped = droppable.remove(0);
                totalDropped++;
                log.debug("Sliding window: dropped paired {} message ({} chars)", dropped.role(),
                        dropped.content() != null ? dropped.content().length() : 0);
            }
        }

        // Insert a context marker so the LLM knows earlier messages were removed
        if (totalDropped > 0) {
            String marker = String.format("[%d earlier messages were dropped to fit token budget]", totalDropped);
            droppable.add(0, new ChatMessage("user", marker));
        }

        List<ChatMessage> result = new ArrayList<>(preserved);
        result.addAll(droppable);
        return result;
    }

    /**
     * Phase 3: Proportionally truncate all non-system messages to fit within budget.
     * Each message is reduced by the same ratio, with a minimum of 100 chars preserved.
     */
    private List<ChatMessage> applyAggressiveTruncation(List<ChatMessage> messages, int maxTokensBudget) {
        int systemTokens = estimateTokens(messages.get(0).content()) + MESSAGE_OVERHEAD_TOKENS;
        int remainingBudget = maxTokensBudget - systemTokens;

        if (remainingBudget <= 0) {
            log.warn("System prompt alone exceeds token budget. Returning system-only.");
            return List.of(messages.get(0));
        }

        // Calculate total tokens of non-system messages
        int nonSystemTokens = 0;
        for (int i = 1; i < messages.size(); i++) {
            nonSystemTokens += estimateTokens(messages.get(i).content()) + MESSAGE_OVERHEAD_TOKENS;
        }

        double ratio = Math.min(1.0, (double) remainingBudget / nonSystemTokens);

        List<ChatMessage> result = new ArrayList<>();
        result.add(messages.get(0)); // system prompt unchanged

        for (int i = 1; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            int allowedChars = Math.max(100, (int) (msg.content().length() * ratio));
            result.add(new ChatMessage(msg.role(), truncateObservation(msg.content(), allowedChars)));
        }

        return result;
    }

    private List<ChatMessage> combine(List<ChatMessage> a, List<ChatMessage> b) {
        List<ChatMessage> combined = new ArrayList<>(a);
        combined.addAll(b);
        return combined;
    }

    // ──────────────────────────── Simple Two-Message Builder ────────────────────────────

    /**
     * Simple builder: assembles system prompt + scratchpad into a two-message array,
     * trimming the scratchpad if the combined token estimate exceeds the specified budget.
     *
     * <p>For multi-turn ReAct conversations, prefer
     * {@link #compactConversation(String, List, int)}.
     */
    public List<ChatMessage> buildPrompt(String systemPrompt, String scratchpad, int maxTokensBudget) {
        if (systemPrompt == null) {
            systemPrompt = "";
        }
        if (scratchpad == null) {
            scratchpad = "";
        }

        int systemPromptTokens = estimateTokens(systemPrompt);
        int totalBudgetForScratchpad = maxTokensBudget - systemPromptTokens - MESSAGE_OVERHEAD_TOKENS;

        String finalScratchpad = scratchpad;
        if (totalBudgetForScratchpad <= 0) {
            log.warn("System prompt is too large ({} tokens) for the token budget ({} tokens). Truncating scratchpad completely.",
                    systemPromptTokens, maxTokensBudget);
            finalScratchpad = "";
        } else {
            int scratchpadTokens = estimateTokens(scratchpad);
            if (scratchpadTokens > totalBudgetForScratchpad) {
                log.info("Scratchpad tokens ({}) exceed budget ({}). Trimming context...",
                        scratchpadTokens, totalBudgetForScratchpad);

                String prefix = "... [truncated due to token limit] ...\n";
                int prefixTokens = estimateTokens(prefix);
                int adjustedAllowedChars = (totalBudgetForScratchpad - prefixTokens) * 4;

                if (adjustedAllowedChars > 0 && adjustedAllowedChars < scratchpad.length()) {
                    finalScratchpad = prefix + scratchpad.substring(scratchpad.length() - adjustedAllowedChars);
                } else {
                    finalScratchpad = prefix;
                }
            }
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", systemPrompt));
        messages.add(new ChatMessage("user", finalScratchpad));
        return messages;
    }
}
