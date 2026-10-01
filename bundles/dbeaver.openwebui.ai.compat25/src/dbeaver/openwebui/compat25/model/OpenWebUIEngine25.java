/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.compat25.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.ai.AIMessage;
import org.jkiss.dbeaver.model.ai.AIMessageType;
import org.jkiss.dbeaver.model.ai.engine.AIEngineRequest;
import org.jkiss.dbeaver.model.ai.engine.AIEngineResponse;
import org.jkiss.dbeaver.model.ai.engine.AIEngineResponseChunk;
import org.jkiss.dbeaver.model.ai.engine.AIEngineResponseConsumer;
import org.jkiss.dbeaver.model.ai.engine.AIModel;
import org.jkiss.dbeaver.model.ai.engine.AIModelFeature;
import org.jkiss.dbeaver.model.ai.engine.BaseCompletionEngine;
import org.jkiss.dbeaver.model.ai.registry.AIFunctionDescriptor;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open WebUI engine (OpenAI Chat Completions) for the AI API of DBeaver 25.2.4–25.2.5.
 */
public class OpenWebUIEngine25 extends BaseCompletionEngine<OpenWebUIProperties25> {

    private static final Log log = Log.getLog(OpenWebUIEngine25.class);
    private static final Set<String> MODELS_WITHOUT_TEMPERATURE = ConcurrentHashMap.newKeySet();
    private static final Set<AIModelFeature> CHAT_FEATURES = Set.of(AIModelFeature.CHAT, AIModelFeature.STREAMING);

    @Nullable
    private Client25 client;

    public OpenWebUIEngine25(@NotNull OpenWebUIProperties25 properties) {
        super(properties);
    }

    @NotNull
    @Override
    public List<AIModel> getModels(@NotNull DBRProgressMonitor monitor) throws DBException {
        List<AIModel> result = new ArrayList<>();
        for (ChatDto.ModelInfo m : getClient().getModels(monitor)) {
            result.add(new AIModel(m.id, Json25.detectContextWindow(m), CHAT_FEATURES));
        }
        return result;
    }

    @NotNull
    @Override
    public AIEngineResponse requestCompletion(@NotNull DBRProgressMonitor monitor, @NotNull AIEngineRequest request) throws DBException {
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
        if (!properties.isStreaming()) {
            AIEngineResponse response = requestCompletion(monitor, request);
            if (response.getFunctionCall() != null) {
                listener.nextChunk(new AIEngineResponseChunk(response.getFunctionCall()));
            } else if (response.getVariants() != null && !response.getVariants().isEmpty()) {
                listener.nextChunk(new AIEngineResponseChunk(List.of(response.getVariants().getFirst())));
            }
            listener.close();
            return;
        }
        getClient().chatStream(createChatRequest(request),
            new StreamHandler25(listener, properties.isHideThinking(), properties.isLoggingEnabled()));
    }

    @Override
    public int getContextWindowSize(@NotNull DBRProgressMonitor monitor) {
        Integer size = properties.getContextWindowSize();
        return size != null && size > 0 ? size : OpenWebUIProperties25.DEFAULT_CONTEXT_WINDOW;
    }

    @Override
    public synchronized void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @NotNull
    private synchronized Client25 getClient() {
        if (client == null) {
            client = new Client25(properties);
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
        MetadataFilter filter = properties.createMetadataFilter();
        chatRequest.messages = toMessages(request.getMessages(), filter);
        if (!MODELS_WITHOUT_TEMPERATURE.contains(model)) {
            chatRequest.temperature = properties.getTemperature();
        }
        if (properties.isFunctionsEnabled() && !request.getFunctions().isEmpty()) {
            List<ChatDto.Tool> tools = toTools(request.getFunctions(), filter);
            chatRequest.tools = tools.isEmpty() ? null : tools;
        }
        return chatRequest;
    }

    /** In DBeaver 25.2 function results are local messages and are not sent to the model. */
    @NotNull
    static List<ChatDto.ChatMessage> toMessages(@NotNull List<AIMessage> messages) {
        return toMessages(messages, new MetadataFilter(false, MetadataFilter.Snapshot.FULL, true, true, 0));
    }

    @NotNull
    static List<ChatDto.ChatMessage> toMessages(@NotNull List<AIMessage> messages, @NotNull MetadataFilter filter) {
        List<ChatDto.ChatMessage> result = new ArrayList<>(messages.size());
        for (AIMessage m : messages) {
            if (m.getRole().isLocal()) {
                continue;
            }
            String role = switch (m.getRole()) {
                case SYSTEM -> "system";
                case USER -> "user";
                case ASSISTANT -> "assistant";
                default -> null;
            };
            if (role != null) {
                String content = m.getRole() == AIMessageType.SYSTEM ? filter.filterSystem(m.getContent()) : m.getContent();
                result.add(new ChatDto.ChatMessage(role, content));
            }
        }
        return result;
    }

    @NotNull
    static List<ChatDto.Tool> toTools(@NotNull List<AIFunctionDescriptor> functions) {
        return toTools(functions, new MetadataFilter(false, MetadataFilter.Snapshot.FULL, true, true, 0));
    }

    @NotNull
    static List<ChatDto.Tool> toTools(@NotNull List<AIFunctionDescriptor> functions, @NotNull MetadataFilter filter) {
        List<ChatDto.Tool> tools = new ArrayList<>(functions.size());
        for (AIFunctionDescriptor fd : functions) {
            if (!filter.isFunctionAllowed(fd.getId())) {
                continue;
            }
            JsonObject function = new JsonObject();
            function.addProperty("name", fd.getId());
            if (fd.getDescription() != null && !fd.getDescription().isBlank()) {
                function.addProperty("description", fd.getDescription());
            }
            JsonObject parameters = new JsonObject();
            parameters.addProperty("type", "object");
            JsonObject props = new JsonObject();
            for (AIFunctionDescriptor.Parameter param : fd.getParameters()) {
                JsonObject p = new JsonObject();
                p.addProperty("type", param.getType());
                if (param.getDescription() != null) {
                    p.addProperty("description", param.getDescription());
                }
                String[] valid = param.getValidValues();
                if (valid != null && valid.length > 0) {
                    JsonArray en = new JsonArray();
                    for (String v : valid) {
                        en.add(v.trim());
                    }
                    p.add("enum", en);
                }
                props.add(param.getName(), p);
            }
            parameters.add("properties", props);
            function.add("parameters", parameters);
            ChatDto.Tool tool = new ChatDto.Tool();
            tool.function = function;
            tools.add(tool);
        }
        return tools;
    }

    @NotNull
    private AIEngineResponse toEngineResponse(@NotNull ChatDto.ChatResponse response) throws DBException {
        AIEngineResponse result;
        ChatDto.ToolCall toolCall = null;
        List<String> variants = new ArrayList<>();
        if (response.choices != null) {
            for (ChatDto.Choice choice : response.choices) {
                ChatDto.ResponseMessage msg = choice.message;
                if (msg == null) {
                    continue;
                }
                if (toolCall == null && msg.toolCalls != null && !msg.toolCalls.isEmpty()) {
                    toolCall = msg.toolCalls.getFirst();
                }
                if (msg.content != null) {
                    variants.add(properties.isHideThinking() ? ThinkTagFilter.strip(msg.content) : msg.content);
                }
            }
        }
        if (toolCall != null) {
            result = new AIEngineResponse(Json25.createFunctionCall(
                toolCall.function == null ? null : toolCall.function.name,
                toolCall.function == null ? null : Json25.argumentsToString(toolCall.function.arguments)));
        } else {
            if (variants.isEmpty()) {
                variants.add("");
            }
            result = new AIEngineResponse(AIMessageType.ASSISTANT, variants);
        }
        if (response.usage != null) {
            result.setInputTokensConsumed(response.usage.promptTokens == null ? 0 : response.usage.promptTokens);
            result.setOutputTokensConsumed(response.usage.completionTokens == null ? 0 : response.usage.completionTokens);
        }
        return result;
    }

    private static boolean dropTemperatureIfRejected(@NotNull ChatDto.ChatRequest request, @NotNull Throwable error) {
        if (request.temperature == null) {
            return false;
        }
        String msg = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        if (msg.contains("temperature") && (msg.contains("unsupported") || msg.contains("not support")
            || msg.contains("invalid") || msg.contains("only the default"))) {
            MODELS_WITHOUT_TEMPERATURE.add(request.model);
            request.temperature = null;
            log.debug("Model " + request.model + " rejected temperature, retrying without it");
            return true;
        }
        return false;
    }
}
