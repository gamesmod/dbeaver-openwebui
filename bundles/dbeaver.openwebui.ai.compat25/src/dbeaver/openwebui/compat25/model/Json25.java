/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.compat25.model;

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
import org.jkiss.dbeaver.model.ai.engine.AIFunctionCall;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

final class Json25 {

    static final Gson GSON = new GsonBuilder()
        .setStrictness(Strictness.LENIENT)
        .disableHtmlEscaping()
        .create();

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private Json25() {
    }

    @NotNull
    static AIFunctionCall createFunctionCall(@Nullable String name, @Nullable String arguments) throws DBException {
        if (name == null || name.isBlank()) {
            throw new DBException("Model returned a tool call without function name");
        }
        Map<String, Object> args = null;
        if (arguments != null && !arguments.isBlank()) {
            try {
                args = GSON.fromJson(arguments, MAP_TYPE);
            } catch (JsonParseException e) {
                throw new DBException("Can't parse tool call arguments: " + arguments, e);
            }
        }
        return new AIFunctionCall(name, args == null ? new LinkedHashMap<>() : args);
    }

    @Nullable
    static String argumentsToString(@Nullable JsonElement arguments) {
        if (arguments == null || arguments.isJsonNull()) {
            return null;
        }
        return arguments.isJsonPrimitive() ? arguments.getAsString() : GSON.toJson(arguments);
    }

    @NotNull
    static String extractErrorMessage(int statusCode, @Nullable String body) {
        String prefix = statusCode > 0 ? "HTTP " + statusCode + ": " : "";
        if (body == null || body.isBlank()) {
            return prefix + switch (statusCode) {
                case 401 -> "unauthorized - check API key";
                case 403 -> "forbidden - the key has no access to this model or API";
                case 404 -> "not found - check base URL (for Open WebUI it usually ends with /api)";
                case 405 -> "method not allowed - check base URL";
                case 429 -> "too many requests";
                default -> "request failed";
            };
        }
        try {
            String msg = findMessage(JsonParser.parseString(body));
            if (msg != null) {
                return prefix + msg;
            }
        } catch (Exception ignored) {
            // not JSON
        }
        String text = body.strip();
        return prefix + (text.length() > 500 ? text.substring(0, 500) + "..." : text);
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

    @Nullable
    static Integer detectContextWindow(@NotNull ChatDto.ModelInfo m) {
        if (m.contextLength != null && m.contextLength > 0) {
            return m.contextLength;
        }
        if (m.maxModelLen != null && m.maxModelLen > 0) {
            return m.maxModelLen;
        }
        if (m.info != null && m.info.get("params") instanceof JsonObject p) {
            JsonElement numCtx = p.get("num_ctx");
            if (numCtx != null && numCtx.isJsonPrimitive() && numCtx.getAsJsonPrimitive().isNumber() && numCtx.getAsInt() > 0) {
                return numCtx.getAsInt();
            }
        }
        return null;
    }
}
