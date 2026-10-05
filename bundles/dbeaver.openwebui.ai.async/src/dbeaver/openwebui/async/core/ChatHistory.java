/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Builds the JSON of an Open WebUI chat from a DBeaver conversation.
 * <p>
 * A DBeaver conversation is linear, so the chat is a single branch: every message is the only child of
 * the previous one. Message ids are derived from the conversation id, the position and the text, so
 * repeated synchronization updates the same messages instead of creating new ones; if the user deletes
 * or regenerates messages in DBeaver, the chat in Open WebUI gets a new branch from that point.
 * Open WebUI merges {@code history.messages} on update, so answers written by the server survive.
 */
public final class ChatHistory {

    private ChatHistory() {
    }

    /**
     * One message of the chain.
     *
     * @param id      Open WebUI message id
     * @param role    "user" or "assistant"
     * @param content text; {@code null} means "the server owns this message" (an answer still being generated
     *                or already stored by a background task) — it is referenced as a parent but not sent
     * @param timestampSec Unix time in seconds
     */
    public record Turn(String id, String role, String content, long timestampSec) {
        public boolean isExternal() {
            return content == null;
        }
    }

    /** Stable id of a message: conversation + position in the chain + text. */
    public static String messageId(String conversationId, int position, String role, String content) {
        String key = conversationId + "|" + position + "|" + role + "|" + sha256(content == null ? "" : content);
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Chat JSON for {@code POST /api/v1/chats/new|{id}}.
     *
     * @param pendingAssistantId id of an empty assistant message appended after the last turn
     *                           (the answer the server is going to write), or {@code null}
     */
    public static JsonObject build(String title, String model, List<Turn> turns, String pendingAssistantId) {
        JsonObject messages = new JsonObject();
        JsonArray flat = new JsonArray();
        List<String> chain = new ArrayList<>();
        String parent = null;
        long lastTs = System.currentTimeMillis() / 1000;
        for (Turn t : turns) {
            if (!t.isExternal()) {
                JsonObject m = message(t.id(), parent, t.role(), t.content(), t.timestampSec(), model);
                messages.add(t.id(), m);
                JsonObject f = new JsonObject();
                f.addProperty("id", t.id());
                f.addProperty("role", t.role());
                f.addProperty("content", t.content());
                flat.add(f);
            }
            chain.add(t.id());
            parent = t.id();
            lastTs = t.timestampSec();
        }
        if (pendingAssistantId != null) {
            JsonObject m = message(pendingAssistantId, parent, "assistant", "", Math.max(lastTs, System.currentTimeMillis() / 1000), model);
            m.addProperty("done", false);
            messages.add(pendingAssistantId, m);
            chain.add(pendingAssistantId);
        }
        // children links along the chain (only for messages we send)
        for (int i = 0; i + 1 < chain.size(); i++) {
            JsonObject m = messages.getAsJsonObject(chain.get(i));
            if (m != null) {
                JsonArray children = new JsonArray();
                children.add(chain.get(i + 1));
                m.add("childrenIds", children);
            }
        }
        JsonObject history = new JsonObject();
        history.add("messages", messages);
        if (!chain.isEmpty()) {
            history.addProperty("currentId", chain.getLast());
        }

        JsonObject chat = new JsonObject();
        chat.addProperty("title", title == null || title.isBlank() ? "DBeaver" : title);
        if (model != null) {
            JsonArray models = new JsonArray();
            models.add(model);
            chat.add("models", models);
        }
        chat.add("history", history);
        chat.add("messages", flat);
        chat.addProperty("timestamp", System.currentTimeMillis());
        return chat;
    }

    /** The user message object for {@code user_message} of a background completion request. */
    public static JsonObject userMessage(Turn user, String parentId, String assistantId, String model) {
        JsonObject m = message(user.id(), parentId, "user", user.content(), user.timestampSec(), null);
        JsonArray children = new JsonArray();
        children.add(assistantId);
        m.add("childrenIds", children);
        if (model != null) {
            JsonArray models = new JsonArray();
            models.add(model);
            m.add("models", models);
        }
        return m;
    }

    private static JsonObject message(String id, String parent, String role, String content, long ts, String model) {
        JsonObject m = new JsonObject();
        m.addProperty("id", id);
        if (parent == null) {
            m.add("parentId", com.google.gson.JsonNull.INSTANCE);
        } else {
            m.addProperty("parentId", parent);
        }
        m.add("childrenIds", new JsonArray());
        m.addProperty("role", role);
        m.addProperty("content", content);
        m.addProperty("timestamp", ts);
        if ("assistant".equals(role) && model != null) {
            m.addProperty("model", model);
            m.addProperty("done", true);
        }
        return m;
    }

    /** Text of a stored chat message: {@code content}, or the text items of {@code output} (Open WebUI 0.10+). */
    public static String messageText(JsonObject message) {
        String content = ChatsApi.string(message, "content");
        if (content != null && !content.isEmpty()) {
            return content;
        }
        var output = message.get("output");
        if (output == null || !output.isJsonArray()) {
            return content == null ? "" : content;
        }
        List<String> texts = new ArrayList<>();
        for (var item : output.getAsJsonArray()) {
            if (!item.isJsonObject() || !"message".equals(ChatsApi.string(item.getAsJsonObject(), "type"))) {
                continue;
            }
            var parts = item.getAsJsonObject().get("content");
            if (parts == null || !parts.isJsonArray()) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (var p : parts.getAsJsonArray()) {
                if (p.isJsonObject() && p.getAsJsonObject().has("text")) {
                    sb.append(p.getAsJsonObject().get("text").getAsString());
                }
            }
            if (!sb.toString().isBlank()) {
                texts.add(sb.toString());
            }
        }
        return String.join("\n", texts);
    }

    /** Raw message object of the chat returned by {@code GET /api/v1/chats/{id}}, or {@code null}. */
    public static JsonObject findMessage(JsonObject chatResponse, String messageId) {
        JsonObject chat = chatResponse.has("chat") && chatResponse.get("chat").isJsonObject()
            ? chatResponse.getAsJsonObject("chat") : chatResponse;
        JsonObject history = chat.has("history") && chat.get("history").isJsonObject() ? chat.getAsJsonObject("history") : null;
        if (history != null && history.has("messages") && history.get("messages").isJsonObject()) {
            var m = history.getAsJsonObject("messages").get(messageId);
            if (m != null && m.isJsonObject()) {
                return m.getAsJsonObject();
            }
        }
        var flat = chat.get("messages");
        if (flat != null && flat.isJsonArray()) {
            for (var e : flat.getAsJsonArray()) {
                if (e.isJsonObject() && messageId.equals(ChatsApi.string(e.getAsJsonObject(), "id"))) {
                    return e.getAsJsonObject();
                }
            }
        }
        return null;
    }
}
