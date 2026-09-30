package io.dbtools.openwebui.api;

import java.time.Duration;
import java.util.List;

/**
 * Неизменяемый снимок настроек подключения к Open WebUI.
 *
 * @param baseUrl         адрес сервера, например http://localhost:3000
 * @param apiKey          API-ключ (Settings → Account → API Keys) или JWT
 * @param model           идентификатор модели по умолчанию
 * @param temperature     температура генерации, null — значение сервера
 * @param requestTimeout  таймаут ожидания ответа
 * @param knowledgeIds    идентификаторы коллекций знаний Open WebUI (RAG), могут быть пустыми
 */
public record ClientSettings(
    String baseUrl,
    String apiKey,
    String model,
    Double temperature,
    Duration requestTimeout,
    List<String> knowledgeIds
) {
    public ClientSettings {
        baseUrl = normalizeBaseUrl(baseUrl);
        knowledgeIds = knowledgeIds == null ? List.of() : List.copyOf(knowledgeIds);
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(120);
        }
    }

    /** Убирает хвостовые слэши и случайно вставленный суффикс /api. */
    static String normalizeBaseUrl(String url) {
        if (url == null) {
            return "";
        }
        String result = url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.endsWith("/api")) {
            result = result.substring(0, result.length() - 4);
        }
        return result;
    }
}
