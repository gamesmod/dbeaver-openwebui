/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.compat25.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * HTTP client for OpenAI Chat Completions API of Open WebUI, independent of DBeaver HTTP helpers
 * (their API differs between versions). Uses HTTP/1.1: over plain http:// the default HTTP/2 client
 * attempts an h2c upgrade, and uvicorn (Open WebUI) then drops the request body.
 */
final class Client25 implements AutoCloseable {

    private static final Log log = Log.getLog(Client25.class);

    private final HttpClient http;
    private final String baseUrl;
    @Nullable
    private final String token;
    private final Map<String, String> extraHeaders;
    private final Duration timeout;
    private final boolean logTraffic;

    Client25(@NotNull OpenWebUIProperties25 properties) {
        this.timeout = Duration.ofSeconds(properties.getTimeout());
        this.http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(timeout)
            .build();
        this.baseUrl = normalizeBaseUrl(properties.getBaseUrl());
        this.token = properties.getToken();
        this.extraHeaders = properties.getExtraHeadersMap();
        this.logTraffic = properties.isLoggingEnabled();
    }

    @NotNull
    List<ChatDto.ModelInfo> getModels(@NotNull DBRProgressMonitor monitor) throws DBException {
        String body = send(monitor, newRequest("models").GET().build());
        ChatDto.ModelList list = parse(body, ChatDto.ModelList.class);
        if (list == null || list.data == null) {
            throw new DBException("Unexpected response from " + baseUrl + "models: no 'data' array");
        }
        return list.data.stream().filter(m -> m.id != null && !m.id.isBlank()).toList();
    }

    @NotNull
    ChatDto.ChatResponse chat(@NotNull DBRProgressMonitor monitor, @NotNull ChatDto.ChatRequest request) throws DBException {
        request.stream = false;
        request.streamOptions = null;
        String payload = Json25.GSON.toJson(request);
        logRequest(payload);
        String body = send(monitor, newRequest("chat/completions").POST(HttpRequest.BodyPublishers.ofString(payload)).build());
        if (logTraffic) {
            log.debug("Open WebUI <<< " + body);
        }
        ChatDto.ChatResponse response = parse(body, ChatDto.ChatResponse.class);
        if (response == null) {
            throw new DBException("Empty response from Open WebUI");
        }
        if (response.error != null && !response.error.isJsonNull()) {
            throw new DBException("Open WebUI error: " + Json25.findMessage(response.error));
        }
        return response;
    }

    void chatStream(@NotNull ChatDto.ChatRequest request, @NotNull StreamHandler25 handler) throws DBException {
        request.stream = true;
        request.streamOptions = null;
        String payload = Json25.GSON.toJson(request);
        logRequest(payload);
        HttpRequest httpRequest = newRequest("chat/completions")
            .setHeader("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
        http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofLines())
            .thenAccept(response -> {
                if (response.statusCode() != 200) {
                    String body = response.body().collect(Collectors.joining("\n"));
                    handler.onError(httpError(response.statusCode(), body));
                    return;
                }
                response.body().forEach(handler);
                handler.onComplete();
            })
            .exceptionally(e -> {
                handler.onError(e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e);
                return null;
            });
    }

    @NotNull
    private String send(@NotNull DBRProgressMonitor monitor, @NotNull HttpRequest request) throws DBException {
        CompletableFuture<HttpResponse<String>> future = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            while (!future.isDone()) {
                if (monitor.isCanceled()) {
                    future.cancel(true);
                    throw new DBException("Request was cancelled");
                }
                TimeUnit.MILLISECONDS.sleep(100);
            }
            HttpResponse<String> response = future.get();
            if (response.statusCode() != 200) {
                throw httpError(response.statusCode(), response.body());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DBException("Request was cancelled", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new DBException("Open WebUI request failed: " + cause.getMessage(), cause);
        }
    }

    @NotNull
    private HttpRequest.Builder newRequest(@NotNull String path) throws DBException {
        URI uri;
        try {
            uri = URI.create(baseUrl).resolve(path);
        } catch (IllegalArgumentException e) {
            throw new DBException("Invalid Open WebUI base URL: " + baseUrl, e);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "DBeaver-OpenWebUI-AI/2");
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
            throw new DBException("Server returned an HTML page instead of JSON. "
                + "Check the base URL: for Open WebUI it must end with /api (e.g. http://host:3000/api)");
        }
        try {
            return Json25.GSON.fromJson(text, type);
        } catch (Exception e) {
            throw new DBException("Can't parse server response: " + (text.length() > 300 ? text.substring(0, 300) + "..." : text), e);
        }
    }

    @NotNull
    private static DBException httpError(int status, @Nullable String body) {
        return new DBException("Open WebUI request failed: " + Json25.extractErrorMessage(status, body));
    }

    private void logRequest(@NotNull String payload) {
        if (logTraffic) {
            log.debug("Open WebUI >>> " + baseUrl + "chat/completions\n" + payload);
        }
    }

    /**
     * "http://host:3000" -> "http://host:3000/api/"; an explicit path ("/api", "/v1") is kept.
     */
    @NotNull
    static String normalizeBaseUrl(@Nullable String url) {
        String value = url == null || url.isBlank() ? OpenWebUIProperties25.DEFAULT_BASE_URL : url.strip();
        if (!value.contains("://")) {
            value = "http://" + value;
        }
        try {
            String path = URI.create(value).getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                value = value.replaceAll("/+$", "") + "/api";
            }
        } catch (IllegalArgumentException ignored) {
            // reported on request
        }
        if (value.endsWith("/chat/completions")) {
            value = value.substring(0, value.length() - "chat/completions".length());
        }
        return value.endsWith("/") ? value : value + "/";
    }

    @Override
    public void close() {
        http.close();
    }
}
