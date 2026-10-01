/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.compat25.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.ai.engine.AIEngineResponseChunk;
import org.jkiss.dbeaver.model.ai.engine.AIEngineResponseConsumer;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Server-Sent Events of Chat Completions → DBeaver 25.2 response consumer (nextChunk / error / close).
 */
final class StreamHandler25 implements Consumer<String> {

    private static final Log log = Log.getLog(StreamHandler25.class);

    private final AIEngineResponseConsumer listener;
    @Nullable
    private final ThinkTagFilter thinkFilter;
    private final boolean logTraffic;
    private final TreeMap<Integer, ToolCall> toolCalls = new TreeMap<>();
    private volatile boolean finished;

    StreamHandler25(@NotNull AIEngineResponseConsumer listener, boolean hideThinking, boolean logTraffic) {
        this.listener = listener;
        this.thinkFilter = hideThinking ? new ThinkTagFilter() : null;
        this.logTraffic = logTraffic;
    }

    @Override
    public void accept(@Nullable String line) {
        if (finished || line == null) {
            return;
        }
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith(":") || trimmed.startsWith("event:") || trimmed.startsWith("id:")) {
            return;
        }
        if (logTraffic) {
            log.debug("Open WebUI <<< " + trimmed);
        }
        String data;
        if (trimmed.startsWith("data:")) {
            data = trimmed.substring(5).strip();
        } else if (trimmed.startsWith("{")) {
            data = trimmed;
        } else {
            return;
        }
        if ("[DONE]".equals(data)) {
            flushToolCalls();
            return;
        }
        try {
            handle(Json25.GSON.fromJson(data, ChatDto.ChatResponse.class));
        } catch (Exception e) {
            onError(e);
        }
    }

    private void handle(@Nullable ChatDto.ChatResponse chunk) throws DBException {
        if (chunk == null) {
            return;
        }
        if (chunk.error != null && !chunk.error.isJsonNull()) {
            throw new DBException("Open WebUI error: " + Json25.findMessage(chunk.error));
        }
        if (chunk.detail != null && !chunk.detail.isJsonNull() && chunk.choices == null) {
            throw new DBException("Open WebUI error: " + Json25.findMessage(chunk.detail));
        }
        if (chunk.choices == null) {
            return;
        }
        for (ChatDto.Choice choice : chunk.choices) {
            ChatDto.ResponseMessage delta = choice.delta != null ? choice.delta : choice.message;
            if (delta != null) {
                if (delta.content != null && !delta.content.isEmpty()) {
                    String out = thinkFilter != null ? thinkFilter.accept(delta.content) : delta.content;
                    if (!out.isEmpty()) {
                        listener.nextChunk(new AIEngineResponseChunk(List.of(out)));
                    }
                }
                accumulate(delta.toolCalls);
            }
            if (choice.finishReason != null) {
                flushToolCalls();
            }
        }
    }

    private void accumulate(@Nullable List<ChatDto.ToolCall> deltas) {
        if (deltas == null) {
            return;
        }
        for (int i = 0; i < deltas.size(); i++) {
            ChatDto.ToolCall d = deltas.get(i);
            int index = d.index != null ? d.index : resolveById(d.id, i);
            ToolCall acc = toolCalls.computeIfAbsent(index, k -> new ToolCall());
            if (d.id != null && !d.id.isEmpty()) {
                acc.id = d.id;
            }
            if (d.function != null) {
                if (d.function.name != null && !d.function.name.isEmpty()) {
                    acc.name = d.function.name;
                }
                String args = Json25.argumentsToString(d.function.arguments);
                if (args != null) {
                    acc.arguments.append(args);
                }
            }
        }
    }

    private int resolveById(@Nullable String id, int position) {
        if (id == null || id.isEmpty()) {
            return position;
        }
        for (Map.Entry<Integer, ToolCall> e : toolCalls.entrySet()) {
            if (id.equals(e.getValue().id)) {
                return e.getKey();
            }
        }
        return toolCalls.isEmpty() ? position : toolCalls.lastKey() + 1;
    }

    private void flushToolCalls() {
        if (toolCalls.isEmpty()) {
            return;
        }
        try {
            // DBeaver 25.2 handles one function call per response
            ToolCall first = toolCalls.firstEntry().getValue();
            listener.nextChunk(new AIEngineResponseChunk(Json25.createFunctionCall(first.name, first.arguments.toString())));
        } catch (DBException e) {
            onError(e);
        } finally {
            toolCalls.clear();
        }
    }

    void onComplete() {
        if (finished) {
            return;
        }
        flushToolCalls();
        if (thinkFilter != null) {
            String rest = thinkFilter.flush();
            if (!rest.isEmpty()) {
                listener.nextChunk(new AIEngineResponseChunk(List.of(rest)));
            }
        }
        if (!finished) {
            finished = true;
            listener.close();
        }
    }

    void onError(@NotNull Throwable error) {
        if (finished) {
            return;
        }
        finished = true;
        listener.error(error);
    }

    private static final class ToolCall {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
