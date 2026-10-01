/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the Apache License, Version 2.0.
 */
package dbeaver.openwebui.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Minimal OpenAI Chat Completions DTOs. All fields are nullable: Open WebUI proxies many
 * different backends (Ollama, OpenAI, vLLM, LiteLLM, pipelines) and their payloads vary.
 */
public final class ChatDto {

    private ChatDto() {
    }

    // ---------------------------------------------------------------- request

    public static class ChatRequest {
        public String model;
        public List<ChatMessage> messages;
        public Boolean stream;
        public Double temperature;
        public List<Tool> tools;
        @SerializedName("stream_options")
        public StreamOptions streamOptions;
    }

    public static class StreamOptions {
        @SerializedName("include_usage")
        public boolean includeUsage = true;
    }

    public static class ChatMessage {
        public String role;
        /** Must be serialized even when null for assistant messages with tool_calls (see serializer). */
        public String content;
        @SerializedName("tool_calls")
        public List<ToolCall> toolCalls;
        @SerializedName("tool_call_id")
        public String toolCallId;

        public ChatMessage() {
        }

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public static class Tool {
        public String type = "function";
        public JsonObject function;
    }

    public static class ToolCall {
        public Integer index;
        public String id;
        public String type;
        public FunctionCall function;
    }

    public static class FunctionCall {
        public String name;
        /** JSON string per OpenAI spec; some backends send an object instead. */
        public JsonElement arguments;
    }

    // ---------------------------------------------------------------- responses

    public static class ChatResponse {
        public List<Choice> choices;
        public Usage usage;
        public JsonElement error;
        public JsonElement detail;
    }

    public static class Choice {
        public Integer index;
        public ResponseMessage message;
        public ResponseMessage delta;
        @SerializedName("finish_reason")
        public String finishReason;
    }

    public static class ResponseMessage {
        public String role;
        public String content;
        @SerializedName("tool_calls")
        public List<ToolCall> toolCalls;
    }

    public static class Usage {
        @SerializedName("prompt_tokens")
        public Integer promptTokens;
        @SerializedName("completion_tokens")
        public Integer completionTokens;
        @SerializedName("prompt_tokens_details")
        public PromptDetails promptTokensDetails;
        @SerializedName("completion_tokens_details")
        public CompletionDetails completionTokensDetails;
    }

    public static class PromptDetails {
        @SerializedName("cached_tokens")
        public Integer cachedTokens;
    }

    public static class CompletionDetails {
        @SerializedName("reasoning_tokens")
        public Integer reasoningTokens;
    }

    // ---------------------------------------------------------------- models

    public static class ModelList {
        public List<ModelInfo> data;
    }

    public static class ModelInfo {
        public String id;
        public String name;
        @SerializedName("context_length")
        public Integer contextLength;
        @SerializedName("max_model_len")
        public Integer maxModelLen;
        /** Open WebUI model card; may contain params.num_ctx for Ollama-backed models. */
        public JsonObject info;
    }
}
