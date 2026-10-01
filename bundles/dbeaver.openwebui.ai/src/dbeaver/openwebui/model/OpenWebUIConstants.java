/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.model;

public final class OpenWebUIConstants {

    /** Engine id, registered in plugin.xml (extension point com.dbeaver.ai.engine). */
    public static final String ENGINE_ID = "openwebui";

    /** Open WebUI exposes OpenAI-compatible API under /api (models, chat/completions). */
    public static final String DEFAULT_BASE_URL = "http://localhost:3000/api";

    public static final String PATH_MODELS = "models";
    public static final String PATH_CHAT_COMPLETIONS = "chat/completions";

    /** Secret storage key for API key / JWT. */
    public static final String SECRET_TOKEN = "openwebui.token";

    /**
     * Metadata key used to keep tool_call_id between function call and function result.
     * Same key as the built-in OpenAI engine uses, so history created by it is also understood.
     */
    public static final String META_CALL_ID = "call_id";

    public static final int DEFAULT_CONTEXT_WINDOW = 32_768;

    private OpenWebUIConstants() {
    }
}
