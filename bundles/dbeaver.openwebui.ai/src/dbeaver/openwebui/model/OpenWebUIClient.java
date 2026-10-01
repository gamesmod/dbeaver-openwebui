/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.ai.engine.AbstractHttpAIClient;
import org.jkiss.dbeaver.model.ai.engine.TooManyRequestsException;
import org.jkiss.dbeaver.model.ai.utils.AIHttpUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the OpenAI Chat Completions API as exposed by Open WebUI
 * ({@code <host>/api/models}, {@code <host>/api/chat/completions}).
 * Also works with any other Chat Completions compatible server (LiteLLM, vLLM, Ollama /v1, LM Studio...).
 */
public class OpenWebUIClient extends AbstractHttpAIClient {

    private static final Log log = Log.getLog(OpenWebUIClient.class);

    private final String baseUrl;
    @Nullable
    private final String token;
    private final Map<String, String> extraHeaders;
    private final boolean logTraffic;

    public OpenWebUIClient(@NotNull OpenWebUIProperties properties) {
        this.baseUrl = normalizeBaseUrl(properties.getBaseUrl());
        this.token = properties.getToken();
        this.extraHeaders = properties.getExtraHeadersMap();
        this.logTraffic = properties.isLoggingEnabled();
        setTimeout(properties.getTimeout());
    }

    @NotNull
    public String getBaseUrl() {
        return baseUrl;
    }

    @NotNull
    public List<ChatDto.ModelInfo> getModels(@NotNull DBRProgressMonitor monitor) throws DBException {
        HttpRequest request = newRequest(OpenWebUIConstants.PATH_MODELS).GET().build();
        String body = client.send(monitor, request);
        ChatDto.ModelList list = parse(body, ChatDto.ModelList.class);
        if (list == null || list.data == null) {
            throw new DBException("Unexpected response from " + request.uri() + ": no 'data' array");
        }
        return list.data.stream().filter(m -> m.id != null && !m.id.isBlank()).toList();
    }

    @NotNull
    public ChatDto.ChatResponse chat(
        @NotNull DBRProgressMonitor monitor,
        @NotNull ChatDto.ChatRequest chatRequest
    ) throws DBException {
        chatRequest.stream = false;
        chatRequest.streamOptions = null;
        String payload = JsonSupport.GSON.toJson(chatRequest);
        logRequest(payload);
        HttpRequest request = newRequest(OpenWebUIConstants.PATH_CHAT_COMPLETIONS)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
        String body = client.send(monitor, request);
        if (logTraffic) {
            log.debug("Open WebUI <<< " + body);
        }
        ChatDto.ChatResponse response = parse(body, ChatDto.ChatResponse.class);
        if (response == null) {
            throw new DBException("Empty response from " + request.uri());
        }
        if (response.error != null && !response.error.isJsonNull()) {
            throw new DBException("Open WebUI error: " + JsonSupport.findMessage(response.error));
        }
        return response;
    }

    public void chatStream(
        @NotNull ChatDto.ChatRequest chatRequest,
        @NotNull ChatStreamHandler handler,
        boolean includeUsage
    ) throws DBException {
        chatRequest.stream = true;
        chatRequest.streamOptions = includeUsage ? new ChatDto.StreamOptions() : null;
        String payload = JsonSupport.GSON.toJson(chatRequest);
        logRequest(payload);
        HttpRequest request = newRequest(OpenWebUIConstants.PATH_CHAT_COMPLETIONS)
            .setHeader("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
        client.sendAsync(request, handler, handler::onError, handler::onComplete);
    }

    @NotNull
    private HttpRequest.Builder newRequest(@NotNull String path) throws DBException {
        URI uri = AIHttpUtils.resolve(baseUrl, path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "DBeaver-OpenWebUI-AI/1.0");
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token.strip());
        }
        for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
            try {
                builder.header(h.getKey(), h.getValue());
            } catch (IllegalArgumentException e) {
                log.warn("Skip invalid HTTP header '" + h.getKey() + "': " + e.getMessage());
            }
        }
        return builder;
    }

    @Nullable
    private static <T> T parse(@NotNull String body, @NotNull Class<T> type) throws DBException {
        String text = body.stripLeading();
        if (text.startsWith("<")) {
            // Open WebUI serves its SPA index.html for unknown paths with HTTP 200
            throw new DBException("Server returned an HTML page instead of JSON. "
                + "Check the base URL: for Open WebUI it must end with /api (e.g. http://host:3000/api)");
        }
        try {
            return JsonSupport.GSON.fromJson(text, type);
        } catch (Exception e) {
            throw new DBException("Can't parse server response: " + abbreviate(text), e);
        }
    }

    private void logRequest(@NotNull String payload) {
        if (logTraffic) {
            log.debug("Open WebUI >>> " + baseUrl + OpenWebUIConstants.PATH_CHAT_COMPLETIONS + "\n" + payload);
        }
    }

    @NotNull
    @Override
    protected DBException mapHttpError(int statusCode, @NotNull String body) {
        String message = "Open WebUI request failed: " + JsonSupport.extractErrorMessage(statusCode, body);
        log.debug(message);
        if (statusCode == 429) {
            return new TooManyRequestsException(message);
        }
        return new DBException(message);
    }

    /**
     * "http://host:3000" -> "http://host:3000/api/" (Open WebUI default);
     * any explicit path ("/api", "/v1", "/ollama/v1") is kept as is.
     */
    @NotNull
    static String normalizeBaseUrl(@Nullable String url) {
        String value = url == null || url.isBlank() ? OpenWebUIConstants.DEFAULT_BASE_URL : url.strip();
        if (!value.contains("://")) {
            value = "http://" + value;
        }
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                value = value.replaceAll("/+$", "") + "/api";
            }
        } catch (IllegalArgumentException ignored) {
            // leave as is, the request will report the error
        }
        if (value.endsWith("/chat/completions")) {
            value = value.substring(0, value.length() - "chat/completions".length());
        }
        return value.endsWith("/") ? value : value + "/";
    }

    @NotNull
    private static String abbreviate(@NotNull String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
