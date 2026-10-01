/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the Apache License, Version 2.0.
 */
package dbeaver.openwebui.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.ai.AIFunctionCall;
import org.jkiss.dbeaver.model.ai.AIMessage;
import org.jkiss.dbeaver.model.ai.AIMessageType;
import org.jkiss.dbeaver.model.ai.AIUsage;
import org.jkiss.dbeaver.model.ai.engine.AIEngineRequest;
import org.jkiss.dbeaver.model.ai.engine.AIEngineResponse;
import org.jkiss.dbeaver.model.ai.engine.AIEngineResponseChunk;
import org.jkiss.dbeaver.model.ai.engine.AIEngineResponseConsumer;
import org.jkiss.dbeaver.model.ai.engine.AIModel;
import org.jkiss.dbeaver.model.ai.engine.AIModelFeature;
import org.jkiss.dbeaver.model.ai.engine.BaseCompletionEngine;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DBeaver AI engine talking to Open WebUI (or any server) via OpenAI Chat Completions API.
 * <p>
 * The built-in "OpenAI" engine of DBeaver uses the Responses API ({@code /responses}),
 * which Open WebUI does not provide - hence this separate engine.
 * <p>
 * DBeaver creates a new engine instance per request and closes it afterwards.
 */
public class OpenWebUIEngine extends BaseCompletionEngine<OpenWebUIProperties> {

    private static final Log log = Log.getLog(OpenWebUIEngine.class);

    /** Models which rejected the "temperature" parameter (o1/o3/gpt-5 via OpenAI connection etc). */
    private static final Set<String> MODELS_WITHOUT_TEMPERATURE = ConcurrentHashMap.newKeySet();

    private static final Set<AIModelFeature> CHAT_FEATURES = Set.of(AIModelFeature.CHAT, AIModelFeature.STREAMING);

    @Nullable
    private OpenWebUIClient client;

    public OpenWebUIEngine(@NotNull OpenWebUIProperties properties) {
        super(properties);
    }

    @NotNull
    @Override
    public List<AIModel> getModels(@NotNull DBRProgressMonitor monitor) throws DBException {
        List<AIModel> result = new ArrayList<>();
        for (ChatDto.ModelInfo m : getClient().getModels(monitor)) {
            result.add(new AIModel(m.id, detectContextWindow(m), CHAT_FEATURES));
        }
        return result;
    }

    @NotNull
    @Override
    public AIEngineResponse requestCompletion(
        @NotNull DBRProgressMonitor monitor,
        @NotNull AIEngineRequest request
    ) throws DBException {
        ChatDto.ChatRequest chatRequest = createChatRequest(request);
        ChatDto.ChatResponse response;
        try {
            response = getClient().chat(monitor, chatRequest);
        } catch (DBException e) {
            if (!dropTemperatureIfRejected(chatRequest, e)) {
                throw e;
            }
            response = getClient().chat(monitor, chatRequest);
        }
        return toEngineResponse(response);
    }

    @Override
    public void requestCompletionStream(
        @NotNull DBRProgressMonitor monitor,
        @NotNull AIEngineRequest request,
        @NotNull AIEngineResponseConsumer listener
    ) throws DBException {
        listener.systemPromptLength(calcSystemPromptLength(request.getMessages()));

        if (!properties.isStreaming()) {
            // Streaming disabled in settings (e.g. a reverse proxy buffers SSE): emulate it
            AIEngineResponse response = requestCompletion(monitor, request);
            listener.usage(response.getUsage());
            AIFunctionCall fc = response.getFunctionCall();
            if (fc != null) {
                listener.nextChunk(new AIEngineResponseChunk(fc));
            } else if (response.getVariants() != null && !response.getVariants().isEmpty()) {
                listener.nextChunk(new AIEngineResponseChunk(List.of(response.getVariants().getFirst())));
            }
            listener.completeBlock();
            return;
        }

        ChatDto.ChatRequest chatRequest = createChatRequest(request);
        startStream(chatRequest, listener, true);
    }

    private void startStream(
        @NotNull ChatDto.ChatRequest chatRequest,
        @NotNull AIEngineResponseConsumer listener,
        boolean allowRetry
    ) throws DBException {
        ChatStreamHandler handler = new ChatStreamHandler(
            listener,
            properties.isHideThinking(),
            properties.isLoggingEnabled()
        );
        if (allowRetry) {
            handler.setRetryHook(error -> {
                if (!dropTemperatureIfRejected(chatRequest, error)) {
                    return false;
                }
                try {
                    startStream(chatRequest, listener, false);
                    return true;
                } catch (DBException e) {
                    log.debug("Retry without temperature failed", e);
                    return false;
                }
            });
        }
        getClient().chatStream(chatRequest, handler, true);
    }

    @Override
    public int getContextWindowSize(@NotNull DBRProgressMonitor monitor) {
        Integer size = properties.getContextWindowSize();
        return size != null && size > 0 ? size : OpenWebUIConstants.DEFAULT_CONTEXT_WINDOW;
    }

    @Override
    public synchronized void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    // ------------------------------------------------------------------ internals

    @NotNull
    private synchronized OpenWebUIClient getClient() {
        if (client == null) {
            client = new OpenWebUIClient(properties);
        }
        return client;
    }

    @NotNull
    private ChatDto.ChatRequest createChatRequest(@NotNull AIEngineRequest request) throws DBException {
        String model = properties.getModel();
        if (model == null || model.isBlank()) {
            throw new DBException("Open WebUI model is not selected. Open AI settings and choose a model.");
        }
        ChatDto.ChatRequest chatRequest = new ChatDto.ChatRequest();
        chatRequest.model = model;
        chatRequest.messages = ChatMessageConverter.toChatMessages(request.getMessages());
        if (!MODELS_WITHOUT_TEMPERATURE.contains(model)) {
            chatRequest.temperature = properties.getTemperature();
        }
        if (properties.isFunctionsEnabled() && !request.getFunctions().isEmpty()) {
            chatRequest.tools = ChatMessageConverter.toTools(request.getFunctions());
        }
        return chatRequest;
    }

    @NotNull
    private AIEngineResponse toEngineResponse(@NotNull ChatDto.ChatResponse response) throws DBException {
        AIUsage usage = JsonSupport.toUsage(response.usage);
        if (response.choices == null || response.choices.isEmpty()) {
            return new AIEngineResponse(AIMessageType.ASSISTANT, List.of(""), usage);
        }
        // Function call has priority: DBeaver executes it and asks the model again
        for (ChatDto.Choice choice : response.choices) {
            ChatDto.ResponseMessage msg = choice.message;
            if (msg != null && msg.toolCalls != null && !msg.toolCalls.isEmpty()) {
                ChatDto.ToolCall tc = msg.toolCalls.getFirst();
                return new AIEngineResponse(
                    JsonSupport.createFunctionCall(
                        tc.id,
                        tc.function == null ? null : tc.function.name,
                        tc.function == null ? null : JsonSupport.argumentsToString(tc.function.arguments)
                    ),
                    usage
                );
            }
        }
        List<String> variants = new ArrayList<>();
        for (ChatDto.Choice choice : response.choices) {
            if (choice.message != null && choice.message.content != null) {
                String text = properties.isHideThinking()
                    ? ThinkTagFilter.strip(choice.message.content)
                    : choice.message.content;
                variants.add(text);
            }
        }
        if (variants.isEmpty()) {
            variants.add("");
        }
        return new AIEngineResponse(AIMessageType.ASSISTANT, variants, usage);
    }

    private static boolean dropTemperatureIfRejected(@NotNull ChatDto.ChatRequest request, @NotNull Throwable error) {
        if (request.temperature == null) {
            return false;
        }
        String msg = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        if (msg.contains("temperature") && (msg.contains("unsupported") || msg.contains("not support")
            || msg.contains("does not support") || msg.contains("invalid") || msg.contains("only the default"))) {
            MODELS_WITHOUT_TEMPERATURE.add(request.model);
            request.temperature = null;
            log.debug("Model " + request.model + " rejected temperature, retrying without it");
            return true;
        }
        return false;
    }

    private static int calcSystemPromptLength(@NotNull List<AIMessage> messages) {
        int len = 0;
        for (AIMessage m : messages) {
            if (m.getRole() == AIMessageType.SYSTEM) {
                len += m.getContent().length();
            }
        }
        return len;
    }

    /**
     * Context size from the model list: OpenAI-style "context_length"/"max_model_len"
     * or Open WebUI model card params (num_ctx for Ollama models).
     */
    @Nullable
    static Integer detectContextWindow(@NotNull ChatDto.ModelInfo m) {
        if (m.contextLength != null && m.contextLength > 0) {
            return m.contextLength;
        }
        if (m.maxModelLen != null && m.maxModelLen > 0) {
            return m.maxModelLen;
        }
        if (m.info != null) {
            JsonElement params = m.info.get("params");
            if (params != null && params.isJsonObject()) {
                JsonObject p = params.getAsJsonObject();
                JsonElement numCtx = p.get("num_ctx");
                if (numCtx != null && numCtx.isJsonPrimitive() && numCtx.getAsJsonPrimitive().isNumber()) {
                    int v = numCtx.getAsInt();
                    return v > 0 ? v : null;
                }
            }
        }
        return null;
    }
}
