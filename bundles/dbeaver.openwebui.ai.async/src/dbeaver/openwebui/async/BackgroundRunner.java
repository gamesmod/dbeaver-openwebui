/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async;

import com.google.gson.JsonObject;
import dbeaver.openwebui.async.core.AsyncSettings;
import dbeaver.openwebui.async.core.BackgroundTask;
import dbeaver.openwebui.async.core.ChatsApi;
import dbeaver.openwebui.async.core.JobStore;
import dbeaver.openwebui.async.core.OwuiException;
import dbeaver.openwebui.model.JsonSupport;
import dbeaver.openwebui.model.OpenWebUIProperties;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.ai.AIChatConversation;
import org.jkiss.dbeaver.model.ai.engine.AIEngineRequest;
import org.jkiss.dbeaver.model.ai.engine.AIEngineResponseChunk;
import org.jkiss.dbeaver.model.ai.engine.AIEngineResponseConsumer;

import java.util.List;

/**
 * Runs one chat request as an Open WebUI server task and feeds the answer to DBeaver:
 * either streams it while the chat waits, or returns a placeholder at once (detached mode)
 * and leaves the delivery to {@link JobPoller}.
 */
final class BackgroundRunner {

    private static final Log log = Log.getLog(BackgroundRunner.class);
    /** The server may be unreachable this long before the waiting chat is released. */
    private static final long GIVE_UP_MS = 3 * 60_000L;

    private BackgroundRunner() {
    }

    /**
     * @return {@code false} if the request can't run in the background and must be sent the usual way
     */
    static boolean start(
        @NotNull AsyncOpenWebUIEngine engine,
        @NotNull RequestContext ctx,
        @NotNull AIEngineRequest request,
        @NotNull AIEngineResponseConsumer listener,
        @NotNull AsyncSettings settings,
        @NotNull String apiBase
    ) throws DBException {
        OpenWebUIProperties props = engine.props();
        String model = props.getModel();
        if (model == null || model.isBlank()) {
            throw new DBException("Open WebUI model is not selected. Open AI settings and choose a model.");
        }
        AIChatConversation conversation = ctx.conversation();
        JobStore store = AsyncPlugin.getJobStore();
        ChatsApi api = AsyncOpenWebUIEngine.createApi(props, apiBase);

        JsonObject completion = JsonSupport.GSON.toJsonTree(engine.buildChatRequest(request)).getAsJsonObject();
        completion.remove("tools");
        completion.remove("stream");
        completion.remove("stream_options");

        BackgroundTask.Request req = new BackgroundTask.Request(
            conversation.getId().toString(),
            conversation.getCaption(),
            model,
            ctx.profile() == null ? null : ctx.profile().getProfileId(),
            AsyncOpenWebUIEngine.historyOf(conversation.getMessages(), null),
            completion,
            props.isHideThinking(),
            settings.detach() ? JobStore.MODE_DETACHED : JobStore.MODE_WAIT,
            job -> {
                if (settings.detach()) {
                    job.placeholder = placeholder(job, apiBase);
                } else {
                    JobPoller.own(job.id);
                }
            });

        BackgroundTask.Started started;
        try {
            started = BackgroundTask.start(api, store, req);
        } catch (OwuiException e) {
            if (e.getStatus() == 400 && e.getMessage().contains("user message")) {
                return false;
            }
            if (e.getStatus() == 404 || e.getStatus() == 405) {
                log.warn("Open WebUI does not support background chats (" + e.getMessage() + "), the request is sent the usual way");
                return false;
            }
            throw new DBException(e.getMessage(), e);
        }

        if (started.directAnswer() != null) {
            send(listener, started.directAnswer());
            listener.completeBlock();
            return true;
        }

        JobStore.Job job = started.job();
        if (settings.detach()) {
            send(listener, job.placeholder);
            listener.completeBlock();
            JobPoller.wake();
            return true;
        }

        Thread thread = new Thread(() -> waitForAnswer(api, store, job, ctx, listener, settings),
            "Open WebUI answer " + shortId(job.id));
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private static void waitForAnswer(
        ChatsApi api,
        JobStore store,
        JobStore.Job job,
        RequestContext ctx,
        AIEngineResponseConsumer listener,
        AsyncSettings settings
    ) {
        StringBuilder shown = new StringBuilder();
        BackgroundTask.Sink sink = new BackgroundTask.Sink() {
            @Override
            public void text(String delta) {
                shown.append(delta);
                send(listener, delta);
            }

            @Override
            public void done(String fullText) {
                listener.completeBlock();
            }

            @Override
            public void error(String message) {
                listener.error(new DBException(AsyncMessages.error_prefix + " " + message));
            }

            @Override
            public boolean canceled() {
                return ctx.conversation().getState() == AIChatConversation.State.CANCELED || ctx.session().isClosed();
            }
        };
        try {
            boolean finished = BackgroundTask.poll(api, store, job, sink, settings.pollMillis(), GIVE_UP_MS);
            if (!finished) {
                // Server unreachable for a long time: release the chat, the poller delivers the answer later
                String note = (shown.isEmpty() ? "" : "\n\n") + AsyncMessages.connection_lost;
                String placeholder = shown + note;
                store.update(job.id, j -> {
                    j.mode = JobStore.MODE_DETACHED;
                    j.placeholder = placeholder;
                });
                send(listener, note);
                listener.completeBlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("Error waiting for the Open WebUI answer", e);
            listener.error(e);
        } finally {
            JobPoller.release(job.id);
            JobPoller.wake();
        }
    }

    @NotNull
    static String placeholder(@NotNull JobStore.Job job, @NotNull String apiBase) {
        return NLS.bind(AsyncMessages.placeholder, shortId(job.id))
            + "\n\n[" + AsyncMessages.placeholder_link + "](" + ChatsApi.chatUrl(apiBase, job.chatId) + ")";
    }

    static String shortId(String id) {
        return id.length() > 6 ? id.substring(0, 6) : id;
    }

    private static void send(AIEngineResponseConsumer listener, String text) {
        if (text != null && !text.isEmpty()) {
            listener.nextChunk(new AIEngineResponseChunk(List.of(text)));
        }
    }
}
