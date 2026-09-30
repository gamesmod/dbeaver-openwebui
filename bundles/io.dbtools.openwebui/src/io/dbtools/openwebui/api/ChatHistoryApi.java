package io.dbtools.openwebui.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * История чатов на сервере Open WebUI ({@code /api/v1/chats}).
 * <p>
 * Чаты сохраняются в том же формате, что пишет веб-интерфейс: линейная ветка сообщений в
 * {@code chat.history.messages} с указателем {@code currentId}, дубль в {@code chat.messages}
 * и системный промпт в {@code chat.params.system}. Поэтому сохранённый из DBeaver диалог
 * открывается и продолжается в браузере, и наоборот.
 */
public class ChatHistoryApi {

    /** Строка списка чатов. {@code updatedAt} — секунды эпохи. */
    public record RemoteChat(String id, String title, long updatedAt) {
    }

    /** Содержимое чата: системный промпт (может быть null), сообщения и модель. */
    public record RemoteChatContent(String id, String title, String model, List<ChatMessage> messages,
                                    List<String> messageIds) {
        /** id сообщения Open WebUI для i-го сообщения из {@code messages} (для system — null). */
        public String messageId(int i) {
            return i < messageIds.size() ? messageIds.get(i) : null;
        }
    }

    private final OpenWebUIClient client;

    public ChatHistoryApi(OpenWebUIClient client) {
        this.client = client;
    }

    /** Страница списка чатов пользователя (по 60 штук, новые сверху). */
    public List<RemoteChat> list(int page) throws OpenWebUIException {
        String body = client.getJson("/api/v1/chats/?page=" + Math.max(1, page));
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonArray()) {
                throw new OpenWebUIException("Неожиданный формат списка чатов");
            }
            List<RemoteChat> result = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                String id = str(o, "id");
                if (id != null) {
                    result.add(new RemoteChat(id, orDefault(str(o, "title"), "Без названия"), num(o, "updated_at")));
                }
            }
            return result;
        } catch (JsonParseException | IllegalStateException e) {
            throw new OpenWebUIException("Не удалось разобрать список чатов: " + e.getMessage(), e);
        }
    }

    /** Чат целиком: текущая ветка сообщений от корня к {@code currentId}. */
    public RemoteChatContent get(String id) throws OpenWebUIException {
        return parseChat(client.getJson("/api/v1/chats/" + id));
    }

    /**
     * Создаёт чат или обновляет существующий.
     *
     * @param remoteId  id чата в Open WebUI или null для нового
     * @param localId   постоянный id сессии в DBeaver — из него строятся стабильные id сообщений,
     *                  чтобы повторные сохранения не плодили ветки
     * @return id чата в Open WebUI
     */
    public String save(String remoteId, String localId, String title, String model, List<ChatMessage> messages)
        throws OpenWebUIException {
        return save(remoteId, localId, title, model, messages, null);
    }

    /**
     * @param messageIds id сообщений Open WebUI, параллельно {@code messages} (null или null-элементы — сгенерировать).
     *                   Для чатов, пришедших из веба, сохраняет исходные id, чтобы не плодить ветки.
     */
    public String save(String remoteId, String localId, String title, String model, List<ChatMessage> messages,
                       List<String> messageIds) throws OpenWebUIException {
        JsonObject form = new JsonObject();
        form.add("chat", buildChat(localId, title, model, messages, messageIds));
        String body = remoteId == null
            ? client.postJson("/api/v1/chats/new", form)
            : client.postJson("/api/v1/chats/" + remoteId, form);
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            String id = str(o, "id");
            if (id == null) {
                throw new OpenWebUIException("Open WebUI не вернул id чата");
            }
            return id;
        } catch (JsonParseException | IllegalStateException e) {
            throw new OpenWebUIException("Не удалось разобрать ответ при сохранении чата: " + e.getMessage(), e);
        }
    }

    public void delete(String remoteId) throws OpenWebUIException {
        client.delete("/api/v1/chats/" + remoteId);
    }

    // ------------------------------------------------------------------ формат Open WebUI

    static JsonObject buildChat(String localId, String title, String model, List<ChatMessage> messages) {
        return buildChat(localId, title, model, messages, null);
    }

    static JsonObject buildChat(String localId, String title, String model, List<ChatMessage> messages,
                                List<String> messageIds) {
        JsonObject chat = new JsonObject();
        chat.addProperty("title", title);
        JsonArray models = new JsonArray();
        if (model != null) {
            models.add(model);
        }
        chat.add("models", models);

        String system = null;
        List<ChatMessage> visible = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if ("system".equals(m.role())) {
                system = m.content();
            } else {
                String known = messageIds != null && i < messageIds.size() ? messageIds.get(i) : null;
                visible.add(m);
                ids.add(known != null ? known : messageId(localId, visible.size() - 1));
            }
        }
        JsonObject params = new JsonObject();
        if (system != null) {
            params.addProperty("system", system);
        }
        chat.add("params", params);

        long now = System.currentTimeMillis() / 1000;
        JsonArray list = new JsonArray();
        JsonObject map = new JsonObject();
        String parent = null;
        String currentId = null;
        for (int i = 0; i < visible.size(); i++) {
            ChatMessage m = visible.get(i);
            String id = ids.get(i);
            String next = i + 1 < visible.size() ? ids.get(i + 1) : null;
            JsonObject msg = new JsonObject();
            msg.addProperty("id", id);
            if (parent == null) {
                msg.add("parentId", com.google.gson.JsonNull.INSTANCE);
            } else {
                msg.addProperty("parentId", parent);
            }
            JsonArray children = new JsonArray();
            if (next != null) {
                children.add(next);
            }
            msg.add("childrenIds", children);
            msg.addProperty("role", m.role());
            msg.addProperty("content", m.content());
            msg.addProperty("timestamp", now);
            if ("user".equals(m.role())) {
                JsonArray um = new JsonArray();
                if (model != null) {
                    um.add(model);
                }
                msg.add("models", um);
            } else if (model != null) {
                msg.addProperty("model", model);
                msg.addProperty("modelName", model);
                msg.addProperty("done", true);
            }
            list.add(msg);
            map.add(id, msg);
            parent = id;
            currentId = id;
        }
        chat.add("messages", list);
        JsonObject history = new JsonObject();
        history.add("messages", map);
        if (currentId != null) {
            history.addProperty("currentId", currentId);
        } else {
            history.add("currentId", com.google.gson.JsonNull.INSTANCE);
        }
        chat.add("history", history);
        chat.addProperty("timestamp", System.currentTimeMillis());
        return chat;
    }

    static RemoteChatContent parseChat(String body) throws OpenWebUIException {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String id = str(root, "id");
            String title = orDefault(str(root, "title"), "Без названия");
            JsonObject chat = root.has("chat") && root.get("chat").isJsonObject() ? root.getAsJsonObject("chat") : new JsonObject();

            String model = null;
            if (chat.has("models") && chat.get("models").isJsonArray() && !chat.getAsJsonArray("models").isEmpty()) {
                JsonElement m0 = chat.getAsJsonArray("models").get(0);
                model = m0.isJsonPrimitive() ? m0.getAsString() : null;
            }

            List<ChatMessage> messages = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            if (chat.has("params") && chat.get("params").isJsonObject()) {
                String system = str(chat.getAsJsonObject("params"), "system");
                if (system != null && !system.isBlank()) {
                    messages.add(ChatMessage.system(system));
                    ids.add(null);
                }
            }

            List<JsonObject> branch = currentBranch(chat);
            for (JsonObject m : branch) {
                String role = str(m, "role");
                String content = contentOf(m);
                if (role != null && content != null && (role.equals("user") || role.equals("assistant"))) {
                    messages.add(new ChatMessage(role, content));
                    ids.add(str(m, "id"));
                }
            }
            return new RemoteChatContent(id, title, model, messages, ids);
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            throw new OpenWebUIException("Не удалось разобрать чат: " + e.getMessage(), e);
        }
    }

    /** Ветка от корня к currentId; если history нет — плоский список messages. */
    private static List<JsonObject> currentBranch(JsonObject chat) {
        List<JsonObject> result = new ArrayList<>();
        if (chat.has("history") && chat.get("history").isJsonObject()) {
            JsonObject history = chat.getAsJsonObject("history");
            JsonObject map = history.has("messages") && history.get("messages").isJsonObject()
                ? history.getAsJsonObject("messages") : new JsonObject();
            String cur = str(history, "currentId");
            Set<String> seen = new HashSet<>();
            while (cur != null && map.has(cur) && seen.add(cur)) {
                JsonObject m = map.getAsJsonObject(cur);
                result.add(m);
                cur = str(m, "parentId");
            }
            if (!result.isEmpty()) {
                Collections.reverse(result);
                return result;
            }
        }
        if (chat.has("messages") && chat.get("messages").isJsonArray()) {
            for (JsonElement el : chat.getAsJsonArray("messages")) {
                if (el.isJsonObject()) {
                    result.add(el.getAsJsonObject());
                }
            }
        }
        return result;
    }

    /** content бывает строкой или массивом частей [{type:"text", text:"…"}]. */
    private static String contentOf(JsonObject m) {
        JsonElement c = m.get("content");
        if (c == null || c.isJsonNull()) {
            return null;
        }
        if (c.isJsonPrimitive()) {
            return c.getAsString();
        }
        if (c.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement part : c.getAsJsonArray()) {
                if (part.isJsonObject() && "text".equals(str(part.getAsJsonObject(), "type"))) {
                    sb.append(str(part.getAsJsonObject(), "text"));
                }
            }
            return sb.toString();
        }
        return null;
    }

    static String messageId(String localId, int index) {
        return UUID.nameUUIDFromBytes((localId + ":" + index).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static long num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        try {
            return e == null || e.isJsonNull() ? 0 : e.getAsLong();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static String orDefault(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }
}
