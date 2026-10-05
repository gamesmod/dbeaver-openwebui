/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns DBeaver conversation messages into the chain of an Open WebUI chat.
 * Only user and assistant text is mirrored; function calls, warnings and errors stay in DBeaver.
 */
public final class TurnMapper {

    private TurnMapper() {
    }

    /** A DBeaver chat message reduced to what is mirrored. */
    public record Item(boolean user, String content, long timestampSec) {
    }

    /**
     * @param conversationId DBeaver conversation id
     * @param items          messages in order (only USER and ASSISTANT ones)
     */
    public static List<ChatHistory.Turn> map(String conversationId, List<Item> items, JobStore store) {
        List<ChatHistory.Turn> turns = new ArrayList<>();
        for (Item item : items) {
            String content = item.content();
            if (content == null || content.isBlank()) {
                continue;
            }
            String role = item.user() ? "user" : "assistant";
            if (!item.user()) {
                // Placeholder of a background request: the server owns the answer
                var job = store.findByPlaceholder(content);
                if (job.isPresent()) {
                    turns.add(new ChatHistory.Turn(job.get().assistantId, role, null, item.timestampSec()));
                    continue;
                }
                String serverId = store.serverAnswerId(conversationId, content);
                if (serverId == null) {
                    String resolved = store.resolveText(content);
                    if (resolved != null && !resolved.equals(content)) {
                        serverId = store.serverAnswerId(conversationId, resolved);
                    }
                }
                if (serverId != null) {
                    turns.add(new ChatHistory.Turn(serverId, role, null, item.timestampSec()));
                    continue;
                }
            }
            String id = ChatHistory.messageId(conversationId, turns.size(), role, content);
            turns.add(new ChatHistory.Turn(id, role, content, item.timestampSec()));
        }
        return turns;
    }
}
