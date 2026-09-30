package io.dbtools.openwebui.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * HTTP-клиент Open WebUI.
 * <p>
 * Используемые эндпоинты:
 * <ul>
 *     <li>{@code GET  /api/models} — список моделей;</li>
 *     <li>{@code POST /api/chat/completions} — OpenAI-совместимый чат, обычный и потоковый (SSE).</li>
 * </ul>
 * Авторизация — заголовок {@code Authorization: Bearer <api key>}.
 * Класс не зависит от Eclipse/DBeaver и потокобезопасен.
 */
public class OpenWebUIClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    private final ClientSettings settings;
    private final HttpClient http;

    public OpenWebUIClient(ClientSettings settings) {
        this(settings, HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build());
    }

    /** Конструктор для тестов и нестандартных HttpClient (прокси, свой SSLContext). */
    public OpenWebUIClient(ClientSettings settings, HttpClient http) {
        this.settings = settings;
        this.http = http;
    }

    public ClientSettings getSettings() {
        return settings;
    }

    // ------------------------------------------------------------------ models

    /** Список моделей, доступных пользователю с данным ключом. */
    public List<ModelInfo> listModels() throws OpenWebUIException {
        HttpRequest request = baseRequest("/api/models")
            .timeout(settings.requestTimeout())
            .GET()
            .build();
        String body = sendForString(request);
        return parseModels(body);
    }

    static List<ModelInfo> parseModels(String body) throws OpenWebUIException {
        try {
            JsonElement root = JsonParser.parseString(body);
            JsonArray items;
            if (root.isJsonArray()) {
                items = root.getAsJsonArray();
            } else if (root.isJsonObject() && root.getAsJsonObject().has("data")) {
                items = root.getAsJsonObject().getAsJsonArray("data");
            } else {
                throw new OpenWebUIException("Неожиданный формат ответа /api/models");
            }
            List<ModelInfo> result = new ArrayList<>();
            for (JsonElement item : items) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject obj = item.getAsJsonObject();
                String id = string(obj, "id");
                if (id == null) {
                    continue;
                }
                String name = string(obj, "name");
                result.add(new ModelInfo(id, name == null ? id : name));
            }
            return result;
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            throw new OpenWebUIException("Не удалось разобрать список моделей: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ chat

    /** Обычный (не потоковый) запрос: возвращает полный текст ответа. */
    public String chat(List<ChatMessage> messages, String modelOverride) throws OpenWebUIException {
        JsonObject payload = buildPayload(messages, modelOverride, false);
        HttpRequest request = baseRequest("/api/chat/completions")
            .timeout(settings.requestTimeout())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
            .build();
        return parseCompletion(sendForString(request));
    }

    /**
     * Потоковый запрос (Server-Sent Events). Фрагменты текста передаются в listener по мере прихода.
     *
     * @return полный текст ответа (склейка всех фрагментов)
     */
    public String chatStream(List<ChatMessage> messages, String modelOverride, StreamListener listener)
        throws OpenWebUIException {
        JsonObject payload = buildPayload(messages, modelOverride, true);
        HttpRequest request = baseRequest("/api/chat/completions")
            .timeout(settings.requestTimeout())
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
            .build();

        HttpResponse<Stream<String>> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            throw new OpenWebUIException(networkError(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenWebUIException("Запрос прерван", e);
        }

        try (Stream<String> lines = response.body()) {
            if (response.statusCode() / 100 != 2) {
                String body = String.join("\n", lines.limit(50).toList());
                throw httpError(response.statusCode(), body);
            }
            StringBuilder full = new StringBuilder();
            Iterator<String> it = lines.iterator();
            boolean sawSse = false;
            StringBuilder nonSse = new StringBuilder();
            while (it.hasNext()) {
                if (listener != null && listener.isCancelled()) {
                    break;
                }
                String line = it.next();
                if (line.isEmpty() || line.startsWith(":")) {
                    continue; // разделитель событий или комментарий SSE
                }
                if (!line.startsWith("data:")) {
                    nonSse.append(line).append('\n');
                    continue;
                }
                sawSse = true;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                String delta = parseStreamChunk(data);
                if (delta != null && !delta.isEmpty()) {
                    full.append(delta);
                    if (listener != null) {
                        listener.onDelta(delta);
                    }
                }
            }
            // Некоторые прокси/конфигурации игнорируют stream=true и отдают обычный JSON.
            if (!sawSse && nonSse.length() > 0) {
                String text = parseCompletion(nonSse.toString());
                if (listener != null) {
                    listener.onDelta(text);
                }
                return text;
            }
            return full.toString();
        }
    }

    JsonObject buildPayload(List<ChatMessage> messages, String modelOverride, boolean stream)
        throws OpenWebUIException {
        String model = modelOverride != null && !modelOverride.isBlank() ? modelOverride : settings.model();
        if (model == null || model.isBlank()) {
            throw new OpenWebUIException("Не выбрана модель. Укажите её в настройках Open WebUI.");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("stream", stream);
        if (settings.temperature() != null) {
            payload.addProperty("temperature", settings.temperature());
        }
        JsonArray arr = new JsonArray();
        for (ChatMessage m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            o.addProperty("content", m.content());
            arr.add(o);
        }
        payload.add("messages", arr);
        if (!settings.knowledgeIds().isEmpty()) {
            JsonArray files = new JsonArray();
            for (String id : settings.knowledgeIds()) {
                JsonObject f = new JsonObject();
                f.addProperty("type", "collection");
                f.addProperty("id", id);
                files.add(f);
            }
            payload.add("files", files);
        }
        return payload;
    }

    static String parseCompletion(String body) throws OpenWebUIException {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                String err = extractError(body);
                throw new OpenWebUIException(err != null ? err : "Пустой ответ модели");
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            String content = message == null ? null : string(message, "content");
            return content == null ? "" : content;
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            throw new OpenWebUIException("Не удалось разобрать ответ модели: " + e.getMessage(), e);
        }
    }

    /** Разбор одного события SSE; возвращает фрагмент текста или null. */
    static String parseStreamChunk(String data) throws OpenWebUIException {
        JsonObject chunk;
        try {
            JsonElement el = JsonParser.parseString(data);
            if (!el.isJsonObject()) {
                return null;
            }
            chunk = el.getAsJsonObject();
        } catch (JsonParseException e) {
            return null; // служебные события Open WebUI могут быть не-JSON — пропускаем
        }
        if (chunk.has("error")) {
            String err = extractError(data);
            throw new OpenWebUIException(err != null ? err : data);
        }
        JsonArray choices = chunk.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty() || !choices.get(0).isJsonObject()) {
            return null; // например, событие с источниками RAG или usage
        }
        JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
        return delta == null ? null : string(delta, "content");
    }

    // ------------------------------------------------------------------ helpers

    /** GET произвольного эндпоинта, тело ответа как строка. */
    String getJson(String path) throws OpenWebUIException {
        return sendForString(baseRequest(path).timeout(settings.requestTimeout()).GET().build());
    }

    /** POST JSON на произвольный эндпоинт. */
    String postJson(String path, JsonObject body) throws OpenWebUIException {
        return sendForString(baseRequest(path)
            .timeout(settings.requestTimeout())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build());
    }

    /** DELETE произвольного эндпоинта. */
    String delete(String path) throws OpenWebUIException {
        return sendForString(baseRequest(path).timeout(settings.requestTimeout()).DELETE().build());
    }


    private HttpRequest.Builder baseRequest(String path) throws OpenWebUIException {
        if (settings.baseUrl().isEmpty()) {
            throw new OpenWebUIException("Не указан адрес Open WebUI. Откройте настройки плагина.");
        }
        HttpRequest.Builder b;
        try {
            b = HttpRequest.newBuilder(URI.create(settings.baseUrl() + path));
        } catch (IllegalArgumentException e) {
            throw new OpenWebUIException("Некорректный адрес Open WebUI: " + settings.baseUrl(), e);
        }
        if (settings.apiKey() != null && !settings.apiKey().isBlank()) {
            b.header("Authorization", "Bearer " + settings.apiKey().trim());
        }
        return b;
    }

    private String sendForString(HttpRequest request) throws OpenWebUIException {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new OpenWebUIException(networkError(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenWebUIException("Запрос прерван", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw httpError(response.statusCode(), response.body());
        }
        return response.body();
    }

    private String networkError(IOException e) {
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return "Нет связи с Open WebUI (" + settings.baseUrl() + "): " + reason;
    }

    static OpenWebUIException httpError(int status, String body) {
        String detail = extractError(body);
        String prefix = switch (status) {
            case 401 -> "Неверный или просроченный API-ключ";
            case 403 -> "Доступ запрещён (проверьте права пользователя и разрешение API-ключей в Open WebUI)";
            case 404 -> "Эндпоинт или модель не найдены";
            case 429 -> "Превышен лимит запросов";
            default -> "Ошибка Open WebUI";
        };
        String msg = prefix + " (HTTP " + status + ")" + (detail != null ? ": " + detail : "");
        return new OpenWebUIException(msg, status, null);
    }

    /** Достаёт текст ошибки из типичных форматов: {detail}, {error:{message}}, {error:"..."}, {message}. */
    static String extractError(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonElement el = JsonParser.parseString(body);
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("detail")) {
                    JsonElement d = o.get("detail");
                    return d.isJsonPrimitive() ? d.getAsString() : d.toString();
                }
                if (o.has("error")) {
                    JsonElement e = o.get("error");
                    if (e.isJsonObject() && e.getAsJsonObject().has("message")) {
                        return e.getAsJsonObject().get("message").getAsString();
                    }
                    return e.isJsonPrimitive() ? e.getAsString() : e.toString();
                }
                if (o.has("message")) {
                    return o.get("message").getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // не JSON — вернём как есть
        }
        String trimmed = body.strip();
        return trimmed.length() > 300 ? trimmed.substring(0, 300) + "…" : trimmed;
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }
}
