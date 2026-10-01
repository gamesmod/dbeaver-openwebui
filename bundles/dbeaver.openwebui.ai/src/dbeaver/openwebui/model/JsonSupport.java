/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.reflect.TypeToken;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.AIFunctionCall;
import org.jkiss.dbeaver.model.ai.AIUsage;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

final class JsonSupport {

    static final Gson GSON = new GsonBuilder()
        .setStrictness(Strictness.LENIENT)
        .disableHtmlEscaping()
        .create();

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private JsonSupport() {
    }

    @NotNull
    static AIFunctionCall createFunctionCall(
        @Nullable String id,
        @Nullable String name,
        @Nullable String arguments
    ) throws DBException {
        if (name == null || name.isBlank()) {
            throw new DBException("Model returned a tool call without function name");
        }
        Map<String, Object> args;
        if (arguments == null || arguments.isBlank()) {
            args = new LinkedHashMap<>();
        } else {
            try {
                args = GSON.fromJson(arguments, MAP_TYPE);
            } catch (JsonParseException e) {
                throw new DBException("Can't parse tool call arguments: " + arguments, e);
            }
            if (args == null) {
                args = new LinkedHashMap<>();
            }
        }
        Map<String, String> meta = id == null || id.isBlank() ? null : Map.of(OpenWebUIConstants.META_CALL_ID, id);
        return new AIFunctionCall(name, args, meta);
    }

    /** Arguments are a JSON string per spec, but some backends put a JSON object there. */
    @Nullable
    static String argumentsToString(@Nullable JsonElement arguments) {
        if (arguments == null || arguments.isJsonNull()) {
            return null;
        }
        if (arguments.isJsonPrimitive()) {
            return arguments.getAsString();
        }
        return GSON.toJson(arguments);
    }

    @Nullable
    static AIUsage toUsage(@Nullable ChatDto.Usage usage) {
        if (usage == null || (usage.promptTokens == null && usage.completionTokens == null)) {
            return null;
        }
        return new AIUsage(
            nz(usage.promptTokens),
            usage.promptTokensDetails == null ? 0 : nz(usage.promptTokensDetails.cachedTokens),
            nz(usage.completionTokens),
            usage.completionTokensDetails == null ? 0 : nz(usage.completionTokensDetails.reasoningTokens)
        );
    }

    /**
     * Extracts a human-readable message from OpenAI ({"error":{"message"}}),
     * Open WebUI / FastAPI ({"detail": "..."}), or plain-text bodies.
     */
    @NotNull
    static String extractErrorMessage(int statusCode, @Nullable String body) {
        String prefix = statusCode > 0 ? "HTTP " + statusCode + ": " : "";
        if (body == null || body.isBlank()) {
            return prefix + httpReason(statusCode);
        }
        try {
            JsonElement root = JsonParser.parseString(body);
            String msg = findMessage(root);
            if (msg != null) {
                return prefix + msg;
            }
        } catch (Exception ignored) {
            // not JSON
        }
        String text = body.strip();
        if (text.length() > 500) {
            text = text.substring(0, 500) + "...";
        }
        return prefix + text;
    }

    @Nullable
    static String findMessage(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonArray()) {
            // FastAPI validation errors: {"detail":[{"msg": "..."}]}
            StringBuilder sb = new StringBuilder();
            for (JsonElement e : element.getAsJsonArray()) {
                String m = findMessage(e);
                if (m != null) {
                    if (!sb.isEmpty()) {
                        sb.append("; ");
                    }
                    sb.append(m);
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        JsonObject obj = element.getAsJsonObject();
        for (String key : new String[]{"error", "detail", "message", "msg"}) {
            if (obj.has(key)) {
                String m = findMessage(obj.get(key));
                if (m != null) {
                    return m;
                }
            }
        }
        return null;
    }

    private static String httpReason(int code) {
        return switch (code) {
            case 401 -> "unauthorized - check API key";
            case 403 -> "forbidden - the key has no access to this model or API";
            case 404 -> "not found - check base URL (for Open WebUI it usually ends with /api)";
            case 405 -> "method not allowed - check base URL";
            case 429 -> "too many requests";
            case 500, 502, 503, 504 -> "server error";
            default -> "request failed";
        };
    }

    private static int nz(@Nullable Integer v) {
        return v == null ? 0 : v;
    }
}
