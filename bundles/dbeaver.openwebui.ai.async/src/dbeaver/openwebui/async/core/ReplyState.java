/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.regex.Pattern;

/**
 * State of an answer that a background task writes into an Open WebUI chat.
 *
 * @param exists  the message is present in the chat
 * @param text    answer text (reasoning removed when requested)
 * @param done    the server marked the message as finished
 * @param error   error text written by the server, or {@code null}
 */
public record ReplyState(boolean exists, String text, boolean done, String error) {

    /** Open WebUI ≤ 0.9 keeps reasoning inside the content as a collapsible block. */
    private static final Pattern DETAILS_REASONING = Pattern.compile(
        "<details\\s+type=\"(?:reasoning|code_interpreter|tool_calls)\"[^>]*>.*?</details>\\s*", Pattern.DOTALL);
    private static final Pattern DETAILS_REASONING_OPEN = Pattern.compile(
        "<details\\s+type=\"(?:reasoning|code_interpreter|tool_calls)\"[^>]*>.*\\z", Pattern.DOTALL);
    private static final Pattern THINK = Pattern.compile("<think>.*?</think>\\s*", Pattern.DOTALL);
    private static final Pattern THINK_OPEN = Pattern.compile("<think>.*\\z", Pattern.DOTALL);

    public static final ReplyState MISSING = new ReplyState(false, "", false, null);

    public static ReplyState of(JsonObject chatResponse, String messageId, boolean hideThinking) {
        JsonObject m = ChatHistory.findMessage(chatResponse, messageId);
        if (m == null) {
            return MISSING;
        }
        String text = ChatHistory.messageText(m);
        if (hideThinking) {
            text = stripReasoning(text);
        }
        boolean done = m.has("done") && m.get("done").isJsonPrimitive() && m.get("done").getAsBoolean();
        return new ReplyState(true, text, done, errorText(m.get("error")));
    }

    /**
     * Removes finished reasoning blocks and a block that is still being streamed, so that the text
     * shown so far is always a prefix of the final text.
     */
    public static String stripReasoning(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String t = DETAILS_REASONING.matcher(text).replaceAll("");
        t = DETAILS_REASONING_OPEN.matcher(t).replaceAll("");
        t = THINK.matcher(t).replaceAll("");
        t = THINK_OPEN.matcher(t).replaceAll("");
        return t.stripLeading();
    }

    public boolean isFinished() {
        return done || error != null;
    }

    private static String errorText(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonPrimitive()) {
            String s = e.getAsString();
            return s.isBlank() || "false".equals(s) ? null : s;
        }
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            for (String key : new String[] {"content", "message", "detail"}) {
                if (o.has(key) && o.get(key).isJsonPrimitive()) {
                    return o.get(key).getAsString();
                }
            }
            return o.toString();
        }
        return e.toString();
    }
}
