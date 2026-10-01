/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the Apache License, Version 2.0.
 */
package dbeaver.openwebui.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.ai.AIUsage;
import org.jkiss.dbeaver.model.ai.engine.AIEngineResponseChunk;
import org.jkiss.dbeaver.model.ai.engine.AIEngineResponseConsumer;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Parses Server-Sent Events of the Chat Completions streaming API line by line
 * and forwards text deltas and assembled tool calls to DBeaver.
 */
final class ChatStreamHandler implements Consumer<String> {

    private static final Log log = Log.getLog(ChatStreamHandler.class);

    private static final String DATA = "data:";
    private static final String DONE = "[DONE]";

    private final AIEngineResponseConsumer listener;
    @Nullable
    private final ThinkTagFilter thinkFilter;
    private final boolean logTraffic;

    private final TreeMap<Integer, ToolCallAccumulator> toolCalls = new TreeMap<>();
    private volatile boolean failed;
    /** Set when the request was re-sent by the retry hook: this handler must stay silent. */
    private volatile boolean superseded;
    private volatile boolean emitted;
    @Nullable
    private Predicate<Throwable> retryHook;

    ChatStreamHandler(@NotNull AIEngineResponseConsumer listener, boolean hideThinking, boolean logTraffic) {
        this.listener = listener;
        this.thinkFilter = hideThinking ? new ThinkTagFilter() : null;
        this.logTraffic = logTraffic;
    }

    /**
     * Hook called on an error before anything was sent to DBeaver.
     * If it returns true, the request was re-sent and this handler ignores the rest of the response.
     */
    void setRetryHook(@Nullable Predicate<Throwable> retryHook) {
        this.retryHook = retryHook;
    }

    @Override
    public void accept(@Nullable String line) {
        if (failed || superseded || line == null) {
            return;
        }
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith(":") || trimmed.startsWith("event:") || trimmed.startsWith("id:")) {
            return; // keep-alive comments and SSE service fields
        }
        if (logTraffic) {
            log.debug("Open WebUI <<< " + trimmed);
        }
        String data;
        if (trimmed.startsWith(DATA)) {
            data = trimmed.substring(DATA.length()).strip();
        } else if (trimmed.startsWith("{")) {
            // Some proxies ignore "stream": true and return a plain JSON body
            data = trimmed;
        } else {
            return;
        }
        if (DONE.equals(data)) {
            flushToolCalls();
            return;
        }
        try {
            handleChunk(JsonSupport.GSON.fromJson(data, ChatDto.ChatResponse.class));
        } catch (Exception e) {
            fail(e);
        }
    }

    private void handleChunk(@Nullable ChatDto.ChatResponse chunk) throws DBException {
        if (chunk == null) {
            return;
        }
        if (chunk.error != null && !chunk.error.isJsonNull()) {
            String msg = JsonSupport.findMessage(chunk.error);
            throw new DBException("Open WebUI error: " + (msg != null ? msg : chunk.error.toString()));
        }
        if (chunk.detail != null && !chunk.detail.isJsonNull() && chunk.choices == null) {
            throw new DBException("Open WebUI error: " + JsonSupport.findMessage(chunk.detail));
        }
        if (chunk.choices != null) {
            for (ChatDto.Choice choice : chunk.choices) {
                ChatDto.ResponseMessage delta = choice.delta != null ? choice.delta : choice.message;
                if (delta != null) {
                    if (delta.content != null && !delta.content.isEmpty()) {
                        emitText(delta.content);
                    }
                    accumulate(delta.toolCalls);
                }
                if (choice.finishReason != null) {
                    // Some backends (e.g. Ollama behind Open WebUI) report "stop" even for tool calls,
                    // so flush on any finish reason, not only "tool_calls"
                    flushToolCalls();
                }
            }
        }
        AIUsage usage = JsonSupport.toUsage(chunk.usage);
        if (usage != null) {
            listener.usage(usage);
        }
    }

    private void emitText(@NotNull String text) {
        String out = thinkFilter != null ? thinkFilter.accept(text) : text;
        if (!out.isEmpty()) {
            emitted = true;
            listener.nextChunk(new AIEngineResponseChunk(List.of(out)));
        }
    }

    private void accumulate(@Nullable List<ChatDto.ToolCall> deltas) {
        if (deltas == null) {
            return;
        }
        for (int i = 0; i < deltas.size(); i++) {
            ChatDto.ToolCall d = deltas.get(i);
            int index = resolveIndex(d, i);
            ToolCallAccumulator acc = toolCalls.computeIfAbsent(index, k -> new ToolCallAccumulator());
            if (d.id != null && !d.id.isEmpty()) {
                acc.id = d.id;
            }
            if (d.function != null) {
                if (d.function.name != null && !d.function.name.isEmpty()) {
                    acc.name = d.function.name;
                }
                String args = JsonSupport.argumentsToString(d.function.arguments);
                if (args != null) {
                    acc.arguments.append(args);
                }
            }
        }
    }

    /**
     * Spec-compliant servers send "index". Some (Ollama-compatible) omit it and send every call
     * complete in its own chunk - then distinguish calls by id.
     */
    private int resolveIndex(@NotNull ChatDto.ToolCall d, int position) {
        if (d.index != null) {
            return d.index;
        }
        if (d.id != null && !d.id.isEmpty()) {
            for (Map.Entry<Integer, ToolCallAccumulator> e : toolCalls.entrySet()) {
                if (d.id.equals(e.getValue().id)) {
                    return e.getKey();
                }
            }
            return toolCalls.isEmpty() ? position : toolCalls.lastKey() + 1;
        }
        return position;
    }

    private void flushToolCalls() {
        if (toolCalls.isEmpty()) {
            return;
        }
        try {
            for (ToolCallAccumulator acc : toolCalls.values()) {
                emitted = true;
                listener.nextChunk(new AIEngineResponseChunk(
                    JsonSupport.createFunctionCall(acc.id, acc.name, acc.arguments.toString())
                ));
            }
        } catch (DBException e) {
            fail(e);
        } finally {
            toolCalls.clear();
        }
    }

    /**
     * Called by the HTTP client when the response body is fully read.
     */
    void onComplete() {
        if (failed || superseded) {
            return;
        }
        flushToolCalls();
        if (thinkFilter != null) {
            String rest = thinkFilter.flush();
            if (!rest.isEmpty()) {
                listener.nextChunk(new AIEngineResponseChunk(List.of(rest)));
            }
        }
        if (!failed) {
            listener.completeBlock();
        }
    }

    void onError(@NotNull Throwable error) {
        if (failed || superseded) {
            return;
        }
        Predicate<Throwable> hook = retryHook;
        if (!emitted && hook != null) {
            retryHook = null;
            superseded = true;
            if (hook.test(error)) {
                return;
            }
            superseded = false;
        }
        fail(error);
    }

    private void fail(@NotNull Throwable error) {
        if (failed) {
            return;
        }
        failed = true;
        listener.error(error);
    }

    private static final class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
