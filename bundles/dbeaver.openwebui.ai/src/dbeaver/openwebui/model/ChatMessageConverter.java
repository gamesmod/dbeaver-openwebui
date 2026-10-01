/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.ai.AIFunctionCall;
import org.jkiss.dbeaver.model.ai.AIFunctionDescriptor;
import org.jkiss.dbeaver.model.ai.AIFunctionParameter;
import org.jkiss.dbeaver.model.ai.AIMessage;
import org.jkiss.dbeaver.model.ai.AIMessageType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts DBeaver AI chat history and function descriptors into Chat Completions payload.
 */
final class ChatMessageConverter {

    private ChatMessageConverter() {
    }

    @NotNull
    static List<ChatDto.ChatMessage> toChatMessages(@NotNull List<AIMessage> messages) {
        return toChatMessages(messages, new MetadataFilter(false, MetadataFilter.Snapshot.FULL, true, true, 0));
    }

    @NotNull
    static List<ChatDto.ChatMessage> toChatMessages(@NotNull List<AIMessage> messages, @NotNull MetadataFilter filter) {
        List<ChatDto.ChatMessage> result = new ArrayList<>(messages.size() + 4);
        for (AIMessage message : messages) {
            AIMessageType role = message.getRole();
            if (role.isLocal()) {
                continue;
            }
            AIFunctionCall call = message.getFunctionCall();
            if (call != null) {
                String callId = resolveCallId(call);

                ChatDto.ToolCall toolCall = new ChatDto.ToolCall();
                toolCall.id = callId;
                toolCall.type = "function";
                toolCall.function = new ChatDto.FunctionCall();
                toolCall.function.name = call.getFunctionName();
                toolCall.function.arguments = new JsonPrimitive(JsonSupport.GSON.toJson(call.getArguments()));

                // Empty string instead of null: some OpenAI-compatible servers require the key
                ChatDto.ChatMessage assistant = new ChatDto.ChatMessage("assistant", "");
                assistant.toolCalls = List.of(toolCall);
                result.add(assistant);

                if (role == AIMessageType.FUNCTION) {
                    ChatDto.ChatMessage tool = new ChatDto.ChatMessage("tool",
                        filter.filterFunctionResult(call.getFunctionName(), message.getContent()));
                    tool.toolCallId = callId;
                    result.add(tool);
                }
                continue;
            }
            String content = message.getContent();
            if (role == AIMessageType.SYSTEM) {
                content = filter.filterSystem(content);
            }
            String mappedRole = switch (role) {
                case SYSTEM -> "system";
                case USER -> "user";
                case ASSISTANT -> "assistant";
                // Function result without call info: pass as plain user-visible context
                case FUNCTION -> "user";
                default -> null;
            };
            if (mappedRole != null) {
                result.add(new ChatDto.ChatMessage(mappedRole, content));
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
            if (!filter.isFunctionAllowed(fd.getFullId())) {
                continue;
            }
            JsonObject function = new JsonObject();
            function.addProperty("name", fd.getFullId());
            String description = fd.getAiDescription();
            if (description != null && !description.isBlank()) {
                function.addProperty("description", description);
            }

            JsonObject parameters = new JsonObject();
            parameters.addProperty("type", "object");
            JsonObject properties = new JsonObject();
            com.google.gson.JsonArray required = new com.google.gson.JsonArray();
            for (AIFunctionParameter param : fd.getParameters()) {
                JsonObject p = new JsonObject();
                p.addProperty("type", param.getType());
                if (param.getDescription() != null) {
                    p.addProperty("description", param.getDescription());
                }
                String[] validValues = param.getValidValues();
                if (validValues != null && validValues.length > 0) {
                    com.google.gson.JsonArray en = new com.google.gson.JsonArray();
                    for (String v : validValues) {
                        en.add(v);
                    }
                    p.add("enum", en);
                }
                properties.add(param.getName(), p);
                if (param.isRequired()) {
                    required.add(param.getName());
                }
            }
            // Always send "properties": llama.cpp/vLLM validators reject object schemas without it
            parameters.add("properties", properties);
            if (!required.isEmpty()) {
                parameters.add("required", required);
            }
            function.add("parameters", parameters);

            ChatDto.Tool tool = new ChatDto.Tool();
            tool.function = function;
            tools.add(tool);
        }
        return tools;
    }

    /**
     * Function calls produced by this engine carry the server's tool_call_id in metadata.
     * Calls produced by other engines (after switching the profile) may not: use a stable synthetic id,
     * so that the assistant/tool message pair still matches.
     */
    @NotNull
    private static String resolveCallId(@NotNull AIFunctionCall call) {
        Map<String, String> meta = call.getMessageMetadata();
        if (meta != null) {
            String id = meta.get(OpenWebUIConstants.META_CALL_ID);
            if (id != null && !id.isBlank()) {
                return id;
            }
            // Anthropic-style key, just in case the history came from another engine
            id = meta.get("tool_use_id");
            if (id != null && !id.isBlank()) {
                return id;
            }
        }
        return "call_" + call.getId().toString().replace("-", "").substring(0, 24);
    }
}
