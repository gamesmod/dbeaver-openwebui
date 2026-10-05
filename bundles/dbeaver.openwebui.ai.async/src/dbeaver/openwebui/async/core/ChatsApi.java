/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Open WebUI chats and background tasks API (the part of Open WebUI beyond OpenAI compatibility).
 * <ul>
 *   <li>{@code POST /api/v1/chats/new}, {@code POST|GET /api/v1/chats/{id}} — chats stored on the server;</li>
 *   <li>{@code POST /api/chat/completions} with {@code chat_id}, {@code id} and {@code session_id} — the
 *       completion runs as a server task and its answer is written into the chat, the call returns at once;</li>
 *   <li>{@code GET /api/tasks/chat/{id}}, {@code POST /api/tasks/chat/{id}/stop} — running tasks of a chat.</li>
 * </ul>
 * The base URL is the one of the engine settings, it must point at Open WebUI's {@code /api}.
 */
public class ChatsApi {

    private static final Gson GSON = new Gson();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);

    private final String apiBase;
    private final String token;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final HttpClient http;

    /**
     * @param apiBase normalized base URL ending with "/", e.g. {@code http://host:3000/api/}
     */
    public ChatsApi(String apiBase, String token, Map<String, String> headers, Duration timeout) {
        this.apiBase = apiBase.endsWith("/") ? apiBase : apiBase + "/";
        this.token = token;
        this.headers = headers == null ? Map.of() : new LinkedHashMap<>(headers);
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? Duration.ofSeconds(120) : timeout;
        this.http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /** Background tasks and stored chats exist only in Open WebUI itself, i.e. under {@code .../api/}. */
    public static boolean supportsChats(String apiBase) {
        if (apiBase == null) {
            return false;
        }
        String path;
        try {
            path = URI.create(apiBase).getPath();
        } catch (IllegalArgumentException e) {
            return false;
        }
        return path != null && (path.endsWith("/api/") || path.endsWith("/api"));
    }

    /** Address of the web interface for links: {@code http://host:3000/api/} → {@code http://host:3000/}. */
    public static String webBase(String apiBase) {
        String base = apiBase.endsWith("/") ? apiBase : apiBase + "/";
        return base.endsWith("/api/") ? base.substring(0, base.length() - "api/".length()) : base;
    }

    public static String chatUrl(String apiBase, String chatId) {
        return webBase(apiBase) + "c/" + chatId;
    }

    public String getApiBase() {
        return apiBase;
    }

    // ------------------------------------------------------------------ chats

    /** Creates a chat, returns its id. */
    public String createChat(JsonObject chat) throws OwuiException {
        JsonObject body = new JsonObject();
        body.add("chat", chat);
        JsonObject response = asObject(call("POST", "v1/chats/new", body));
        String id = string(response, "id");
        if (id == null) {
            throw new OwuiException("Open WebUI did not return the id of the new chat", 0);
        }
        return id;
    }

    /** Patches top-level chat keys; Open WebUI merges {@code history.messages} with the stored ones. */
    public void updateChat(String chatId, JsonObject chat) throws OwuiException {
        JsonObject body = new JsonObject();
        body.add("chat", chat);
        call("POST", "v1/chats/" + enc(chatId), body);
    }

    /** Full chat ({@code {id, title, chat: {history: ...}}}). In-progress answers are overlaid by Open WebUI 0.9+. */
    public JsonObject getChat(String chatId) throws OwuiException {
        JsonElement e = call("GET", "v1/chats/" + enc(chatId), null);
        if (e == null || !e.isJsonObject()) {
            throw new OwuiException("Chat " + chatId + " not found", 404);
        }
        return e.getAsJsonObject();
    }

    // ------------------------------------------------------------------ background tasks

    /**
     * Result of {@link #startBackground}: task ids, or a complete answer when the server
     * ignored the background request (old Open WebUI or a proxy in front of it).
     */
    public record StartResult(List<String> taskIds, String directAnswer) {
        public boolean isBackground() {
            return directAnswer == null;
        }
    }

    public StartResult startBackground(JsonObject body) throws OwuiException {
        JsonElement e = call("POST", "chat/completions", body);
        if (e == null || !e.isJsonObject()) {
            throw new OwuiException("Unexpected response of Open WebUI to a background request", 0);
        }
        JsonObject o = e.getAsJsonObject();
        if (o.has("choices")) {
            return new StartResult(List.of(), directText(o));
        }
        if (o.has("status") && !isTrue(o.get("status"))) {
            throw new OwuiException("Open WebUI refused the request: " + o, 400);
        }
        List<String> ids = new ArrayList<>();
        JsonElement arr = o.get("task_ids");
        if (arr != null && arr.isJsonArray()) {
            for (JsonElement t : arr.getAsJsonArray()) {
                if (t.isJsonPrimitive()) {
                    ids.add(t.getAsString());
                }
            }
        }
        String single = string(o, "task_id");
        if (single != null) {
            ids.add(single);
        }
        return new StartResult(ids, null);
    }

    /** Ids of tasks still running for the chat (empty when generation finished). */
    public List<String> activeTasks(String chatId) throws OwuiException {
        JsonObject o = asObject(call("GET", "tasks/chat/" + enc(chatId), null));
        List<String> ids = new ArrayList<>();
        JsonElement arr = o.get("task_ids");
        if (arr != null && arr.isJsonArray()) {
            for (JsonElement t : arr.getAsJsonArray()) {
                ids.add(t.getAsString());
            }
        }
        return ids;
    }

    /** Stops generation in the chat. Older servers have only the per-task endpoint. */
    public void stopTasks(String chatId, List<String> taskIds) throws OwuiException {
        try {
            call("POST", "tasks/chat/" + enc(chatId) + "/stop", new JsonObject());
            return;
        } catch (OwuiException e) {
            if (e.getStatus() != 404 && e.getStatus() != 405) {
                throw e;
            }
        }
        for (String id : taskIds) {
            call("POST", "tasks/stop/" + enc(id), new JsonObject());
        }
    }

    // ------------------------------------------------------------------ http

    private JsonElement call(String method, String path, JsonObject body) throws OwuiException {
        URI uri = URI.create(apiBase + path);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("User-Agent", "DBeaver-OpenWebUI-AI/async");
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token.strip());
        }
        for (Map.Entry<String, String> h : headers.entrySet()) {
            try {
                b.header(h.getKey(), h.getValue());
            } catch (IllegalArgumentException ignored) {
                // restricted header — the main engine reports it
            }
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response;
        try {
            response = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new OwuiException("Open WebUI is not reachable (" + uri.getHost() + "): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OwuiException("Interrupted", e);
        }
        String text = response.body() == null ? "" : response.body().strip();
        if (response.statusCode() >= 400) {
            throw new OwuiException("Open WebUI " + method + " " + path + ": HTTP " + response.statusCode()
                + errorDetail(text), response.statusCode());
        }
        if (text.startsWith("<")) {
            throw new OwuiException("Open WebUI returned an HTML page for " + path
                + ". Background chats need the Open WebUI address ending with /api", 404);
        }
        if (text.isEmpty() || "null".equals(text)) {
            return null;
        }
        try {
            return JsonParser.parseString(text);
        } catch (RuntimeException e) {
            throw new OwuiException("Can't parse Open WebUI response: " + abbreviate(text), e);
        }
    }

    private static String errorDetail(String text) {
        if (text.isEmpty()) {
            return "";
        }
        try {
            JsonElement e = JsonParser.parseString(text);
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                for (String key : new String[] {"detail", "message", "error"}) {
                    JsonElement v = o.get(key);
                    if (v != null && v.isJsonPrimitive()) {
                        return ": " + v.getAsString();
                    }
                    if (v != null && v.isJsonObject() && v.getAsJsonObject().has("message")) {
                        return ": " + v.getAsJsonObject().get("message").getAsString();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        return ": " + abbreviate(text);
    }

    private static String directText(JsonObject o) {
        StringBuilder sb = new StringBuilder();
        JsonElement choices = o.get("choices");
        if (choices != null && choices.isJsonArray()) {
            JsonArray arr = choices.getAsJsonArray();
            if (!arr.isEmpty() && arr.get(0).isJsonObject()) {
                JsonObject msg = arr.get(0).getAsJsonObject().getAsJsonObject("message");
                if (msg != null && msg.has("content") && msg.get("content").isJsonPrimitive()) {
                    sb.append(msg.get("content").getAsString());
                }
            }
        }
        return sb.toString();
    }

    private static JsonObject asObject(JsonElement e) throws OwuiException {
        if (e == null || !e.isJsonObject()) {
            throw new OwuiException("Unexpected response of Open WebUI: " + e, 0);
        }
        return e.getAsJsonObject();
    }

    static String string(JsonObject o, String key) {
        JsonElement v = o == null ? null : o.get(key);
        return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
    }

    private static boolean isTrue(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
