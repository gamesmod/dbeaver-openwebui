/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async;

import dbeaver.openwebui.async.core.BackgroundTask;
import dbeaver.openwebui.async.core.ChatsApi;
import dbeaver.openwebui.async.core.OwuiException;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.ai.AIUsage;
import org.jkiss.dbeaver.model.ai.engine.AIEngineResponseChunk;
import org.jkiss.dbeaver.model.ai.engine.AIEngineResponseConsumer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Passes a usual streaming answer to DBeaver and, when it is complete, copies the conversation
 * with the answer into its Open WebUI chat ("Save chats in Open WebUI" without background requests).
 */
final class MirroringConsumer implements AIEngineResponseConsumer {

    private static final Log log = Log.getLog(MirroringConsumer.class);
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Open WebUI chat mirror");
        t.setDaemon(true);
        return t;
    });

    private final AIEngineResponseConsumer delegate;
    private final RequestContext ctx;
    private final AsyncOpenWebUIEngine engine;
    private final String apiBase;
    private final StringBuilder text = new StringBuilder();
    private boolean functionCall;

    MirroringConsumer(AIEngineResponseConsumer delegate, RequestContext ctx, AsyncOpenWebUIEngine engine, String apiBase) {
        this.delegate = delegate;
        this.ctx = ctx;
        this.engine = engine;
        this.apiBase = apiBase;
    }

    @Override
    public void nextChunk(@NotNull AIEngineResponseChunk chunk) {
        if (chunk.getFunctionCall() != null) {
            functionCall = true;
        } else if (!chunk.getChoices().isEmpty()) {
            text.append(chunk.getChoices().getFirst());
        }
        delegate.nextChunk(chunk);
    }

    @Override
    public void error(@NotNull Throwable throwable) {
        delegate.error(throwable);
    }

    @Override
    public void completeBlock() {
        if (functionCall || text.isEmpty()) {
            delegate.completeBlock();
            return;
        }
        // Snapshot before DBeaver adds the answer to the conversation
        String answer = text.toString();
        var history = AsyncOpenWebUIEngine.historyOf(ctx.conversation().getMessages(), answer);
        delegate.completeBlock();
        String conversationId = ctx.conversation().getId().toString();
        String title = ctx.conversation().getCaption();
        String model = engine.props().getModel();
        ChatsApi api = AsyncOpenWebUIEngine.createApi(engine.props(), apiBase);
        EXECUTOR.execute(() -> {
            try {
                BackgroundTask.mirror(api, AsyncPlugin.getJobStore(), conversationId, title, model, history);
            } catch (OwuiException e) {
                log.debug("Can't copy the conversation to Open WebUI: " + e.getMessage());
            } catch (RuntimeException e) {
                log.debug("Can't copy the conversation to Open WebUI", e);
            }
        });
    }

    @Override
    public void usage(@Nullable AIUsage usage) {
        delegate.usage(usage);
    }

    @Override
    public void systemPromptLength(int length) {
        delegate.systemPromptLength(length);
    }

    @Override
    public void warning(@NotNull String message) {
        delegate.warning(message);
    }
}
