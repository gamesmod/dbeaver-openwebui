/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async.core;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.UUID;

/**
 * One request executed as an Open WebUI server task.
 * <ol>
 *   <li>the DBeaver conversation is mirrored into its Open WebUI chat (created on first use);</li>
 *   <li>{@code POST /api/chat/completions} with {@code chat_id}, {@code id}, {@code session_id} starts the task;
 *       Open WebUI answers at once and writes the answer into the chat by itself;</li>
 *   <li>the answer is read back with {@code GET /api/v1/chats/{id}} — while DBeaver waits ({@link #poll})
 *       or later, by the background poller ({@link #check}), also after a DBeaver restart.</li>
 * </ol>
 */
public final class BackgroundTask {

    /** Receiver of the answer while DBeaver waits for it. */
    public interface Sink {
        void text(String delta);

        void done(String fullText);

        void error(String message);

        /** The user pressed "Stop" in DBeaver. */
        boolean canceled();
    }

    /** Result of {@link #start}: a job running on the server, or an answer the server gave at once. */
    public record Started(JobStore.Job job, String directAnswer) {
    }

    /** What is needed to start a request. */
    public record Request(
        String conversationId,
        String title,
        String model,
        String profileId,
        List<TurnMapper.Item> history,
        JsonObject completion,
        boolean hideThinking,
        String mode,
        java.util.function.Consumer<JobStore.Job> prepare
    ) {
        public Request(String conversationId, String title, String model, String profileId, List<TurnMapper.Item> history,
                       JsonObject completion, boolean hideThinking, String mode) {
            this(conversationId, title, model, profileId, history, completion, hideThinking, mode, null);
        }
    }

    private static final int FINISH_BY_TASKS_POLLS = 2;
    private static final int MISSING_MESSAGE_POLLS = 5;

    private BackgroundTask() {
    }

    /**
     * Mirrors the conversation and starts the server task. The last history item must be the new user message.
     */
    public static Started start(ChatsApi api, JobStore store, Request req) throws OwuiException {
        List<ChatHistory.Turn> turns = TurnMapper.map(req.conversationId(), req.history(), store);
        if (turns.isEmpty() || !"user".equals(turns.getLast().role()) || turns.getLast().isExternal()) {
            throw new OwuiException("The conversation does not end with a user message", 400);
        }
        ChatHistory.Turn user = turns.getLast();
        String parentOfUser = turns.size() > 1 ? turns.get(turns.size() - 2).id() : null;
        String assistantId = UUID.randomUUID().toString();
        String chatId = ensureChat(api, store, req.conversationId(), ChatHistory.build(req.title(), req.model(), turns, assistantId));

        JsonObject body = req.completion().deepCopy();
        body.addProperty("model", req.model());
        body.addProperty("stream", true);
        body.addProperty("chat_id", chatId);
        body.addProperty("id", assistantId);
        // Any session id switches Open WebUI to the background mode; there is no browser to notify
        body.addProperty("session_id", "dbeaver-" + UUID.randomUUID());
        body.add("user_message", ChatHistory.userMessage(user, parentOfUser, assistantId, req.model()));
        JsonObject tasks = new JsonObject();
        tasks.addProperty("title_generation", false);
        tasks.addProperty("tags_generation", false);
        tasks.addProperty("follow_up_generation", false);
        body.add("background_tasks", tasks);

        ChatsApi.StartResult r = api.startBackground(body);
        if (!r.isBackground()) {
            return new Started(null, req.hideThinking() ? ReplyState.stripReasoning(r.directAnswer()) : r.directAnswer());
        }
        JobStore.Job job = new JobStore.Job();
        job.id = UUID.randomUUID().toString();
        job.conversationId = req.conversationId();
        job.profileId = req.profileId();
        job.chatId = chatId;
        job.assistantId = assistantId;
        job.mode = req.mode();
        job.apiBase = api.getApiBase();
        job.model = req.model();
        job.hideThinking = req.hideThinking();
        job.taskIds.addAll(r.taskIds());
        job.created = System.currentTimeMillis();
        if (req.prepare() != null) {
            // e.g. the placeholder text: the job must be complete when other threads can see it
            req.prepare().accept(job);
        }
        store.add(job);
        return new Started(job, null);
    }

    /** Creates the chat on first use, or when it was deleted in Open WebUI; updates it otherwise. */
    static String ensureChat(ChatsApi api, JobStore store, String conversationId, JsonObject chat) throws OwuiException {
        String chatId = store.chatOf(conversationId);
        if (chatId != null) {
            try {
                api.updateChat(chatId, chat);
                return chatId;
            } catch (OwuiException e) {
                if (!e.isNotFound()) {
                    throw e;
                }
            }
        }
        chatId = api.createChat(chat);
        store.linkChat(conversationId, chatId);
        return chatId;
    }

    /**
     * Mirrors a conversation without running anything (answers produced by the usual streaming request).
     */
    public static void mirror(ChatsApi api, JobStore store, String conversationId, String title, String model,
                              List<TurnMapper.Item> history) throws OwuiException {
        List<ChatHistory.Turn> turns = TurnMapper.map(conversationId, history, store);
        if (turns.isEmpty()) {
            return;
        }
        ensureChat(api, store, conversationId, ChatHistory.build(title, model, turns, null));
    }

    /**
     * Polls the server until the answer is finished, passing new text to the sink.
     *
     * @param giveUpMs how long the server may stay unreachable before returning {@code false}
     *                 (the caller then leaves the job to the background poller)
     * @return {@code true} when the sink got done/error/cancel, {@code false} when the server is unreachable
     */
    public static boolean poll(ChatsApi api, JobStore store, JobStore.Job job, Sink sink, long pollMs, long giveUpMs)
        throws InterruptedException {
        String sent = "";
        long failingSince = 0;
        int noTasksPolls = 0;
        int missingPolls = 0;
        while (true) {
            if (sink.canceled()) {
                try {
                    api.stopTasks(job.chatId, job.taskIds);
                } catch (OwuiException ignored) {
                    // the server stops abandoned tasks by itself sooner or later
                }
                store.remove(job.id);
                return true;
            }
            try {
                ReplyState s = ReplyState.of(api.getChat(job.chatId), job.assistantId, job.hideThinking);
                failingSince = 0;
                if (s.exists() && s.text().startsWith(sent) && s.text().length() > sent.length()) {
                    sink.text(s.text().substring(sent.length()));
                    sent = s.text();
                }
                boolean finished = s.isFinished();
                if (!finished) {
                    // Servers without the "done" flag: no running tasks means the answer is complete
                    if (api.activeTasks(job.chatId).isEmpty()) {
                        if (s.exists() && !s.text().isEmpty()) {
                            finished = ++noTasksPolls >= FINISH_BY_TASKS_POLLS;
                        } else if (++missingPolls >= MISSING_MESSAGE_POLLS) {
                            s = new ReplyState(s.exists(), s.text(), true,
                                "Open WebUI finished the request without an answer. Check the server log.");
                            finished = true;
                        }
                    } else {
                        noTasksPolls = 0;
                        missingPolls = 0;
                    }
                }
                if (finished) {
                    final ReplyState fin = s;
                    store.update(job.id, j -> finish(j, fin));
                    if (fin.error() != null && fin.text().isEmpty()) {
                        sink.error(fin.error());
                    } else {
                        if (fin.text().length() > sent.length() && fin.text().startsWith(sent)) {
                            sink.text(fin.text().substring(sent.length()));
                        }
                        sink.done(fin.text());
                    }
                    store.delivered(job.id);
                    return true;
                }
            } catch (OwuiException e) {
                if (!e.isTransient()) {
                    store.update(job.id, j -> {
                        j.state = JobStore.STATE_ERROR;
                        j.error = e.getMessage();
                        j.finished = System.currentTimeMillis();
                    });
                    sink.error(e.getMessage());
                    store.delivered(job.id);
                    return true;
                }
                if (failingSince == 0) {
                    failingSince = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - failingSince > giveUpMs) {
                    return false;
                }
            }
            Thread.sleep(pollMs);
        }
    }

    /**
     * One check of a job left to the background poller.
     *
     * @return {@code true} when the job is finished (its state and result are saved in the store)
     */
    public static boolean check(ChatsApi api, JobStore store, JobStore.Job job) {
        try {
            ReplyState s = ReplyState.of(api.getChat(job.chatId), job.assistantId, job.hideThinking);
            boolean finished = s.isFinished();
            if (!finished && api.activeTasks(job.chatId).isEmpty()) {
                if (s.exists() && !s.text().isEmpty()) {
                    finished = true;
                } else if (System.currentTimeMillis() - job.created > 60_000) {
                    s = new ReplyState(s.exists(), s.text(), true, "Open WebUI finished the request without an answer. Check the server log.");
                    finished = true;
                }
            }
            if (finished) {
                final ReplyState fin = s;
                store.update(job.id, j -> finish(j, fin));
                return true;
            }
        } catch (OwuiException e) {
            if (e.isNotFound()) {
                store.update(job.id, j -> {
                    j.state = JobStore.STATE_ERROR;
                    j.error = "The chat was deleted in Open WebUI: " + e.getMessage();
                    j.finished = System.currentTimeMillis();
                });
                return true;
            }
        }
        return false;
    }

    private static void finish(JobStore.Job j, ReplyState s) {
        if (s.error() != null && s.text().isEmpty()) {
            j.state = JobStore.STATE_ERROR;
            j.error = s.error();
        } else {
            j.state = JobStore.STATE_DONE;
            j.result = s.text();
            j.error = s.error();
        }
        j.finished = System.currentTimeMillis();
    }
}
