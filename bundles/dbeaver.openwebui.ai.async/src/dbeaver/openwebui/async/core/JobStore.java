/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Persistent state of the add-on (one JSON file in the workspace):
 * <ul>
 *   <li>background requests whose answers are not delivered into DBeaver yet;</li>
 *   <li>which Open WebUI chat mirrors which DBeaver conversation;</li>
 *   <li>answers written by the server (to reference them instead of re-uploading).</li>
 * </ul>
 * Survives restarts: an answer generated while DBeaver was closed is delivered on the next start.
 */
public final class JobStore {

    public static final String MODE_WAIT = "wait";
    public static final String MODE_DETACHED = "detached";

    public static final String STATE_RUNNING = "running";
    public static final String STATE_DONE = "done";
    public static final String STATE_ERROR = "error";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final long KEEP_RESOLVED_MS = 30L * 24 * 3600 * 1000;

    /** One background request. */
    public static final class Job {
        public String id;
        public String conversationId;
        public String profileId;
        public String chatId;
        public String assistantId;
        /** Text DBeaver shows instead of the answer (detached mode), {@code null} while DBeaver waits. */
        public String placeholder;
        public String mode;
        public String state = STATE_RUNNING;
        public String result;
        public String error;
        public String apiBase;
        public String model;
        public boolean hideThinking = true;
        public List<String> taskIds = new ArrayList<>();
        public long created;
        public long finished;

        public boolean isFinished() {
            return STATE_DONE.equals(state) || STATE_ERROR.equals(state);
        }

        public boolean isDetached() {
            return MODE_DETACHED.equals(mode);
        }

        /** Text to put into DBeaver when the job is finished. */
        public String deliveryText() {
            if (STATE_ERROR.equals(state)) {
                return error == null ? "Open WebUI error" : error;
            }
            return result == null ? "" : result;
        }
    }

    /** Placeholder replaced by the answer; kept for a while to fix copies saved before delivery. */
    public static final class Resolved {
        public String text;
        public long at;

        Resolved() {
        }

        Resolved(String text, long at) {
            this.text = text;
            this.at = at;
        }
    }

    private static final class State {
        List<Job> jobs = new ArrayList<>();
        Map<String, String> chats = new LinkedHashMap<>();
        Map<String, String> serverAnswers = new LinkedHashMap<>();
        Map<String, Resolved> resolved = new LinkedHashMap<>();
    }

    private final Path file;
    private State state = new State();

    public JobStore(Path file) {
        this.file = file;
        load();
    }

    private synchronized void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            State s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), State.class);
            if (s != null) {
                if (s.jobs == null) {
                    s.jobs = new ArrayList<>();
                }
                if (s.chats == null) {
                    s.chats = new LinkedHashMap<>();
                }
                if (s.serverAnswers == null) {
                    s.serverAnswers = new LinkedHashMap<>();
                }
                if (s.resolved == null) {
                    s.resolved = new LinkedHashMap<>();
                }
                state = s;
            }
        } catch (IOException | RuntimeException e) {
            // A broken file must not break the chat: start with an empty state, keep the file for analysis
            try {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // nothing
            }
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        long now = System.currentTimeMillis();
        state.resolved.values().removeIf(r -> now - r.at > KEEP_RESOLVED_MS);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(state), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Can't save " + file + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ jobs

    public synchronized void add(Job job) {
        state.jobs.removeIf(j -> j.id.equals(job.id));
        state.jobs.add(job);
        save();
    }

    public synchronized void update(String jobId, Consumer<Job> change) {
        for (Job j : state.jobs) {
            if (j.id.equals(jobId)) {
                change.accept(j);
                save();
                return;
            }
        }
    }

    /** The answer reached DBeaver: forget the job, remember the placeholder → answer pair. */
    public synchronized void delivered(String jobId) {
        Job job = find(jobId).orElse(null);
        if (job == null) {
            return;
        }
        state.jobs.remove(job);
        if (job.placeholder != null) {
            state.resolved.put(ChatHistory.sha256(job.placeholder), new Resolved(job.deliveryText(), System.currentTimeMillis()));
        }
        if (job.result != null && !job.result.isEmpty()) {
            putServerAnswer(job.conversationId, job.result, job.assistantId);
        }
        save();
    }

    public synchronized void remove(String jobId) {
        if (state.jobs.removeIf(j -> j.id.equals(jobId))) {
            save();
        }
    }

    public synchronized Optional<Job> find(String jobId) {
        return state.jobs.stream().filter(j -> j.id.equals(jobId)).findFirst();
    }

    public synchronized List<Job> jobs() {
        return List.copyOf(state.jobs);
    }

    /** Job whose placeholder DBeaver shows, by the exact placeholder text. */
    public synchronized Optional<Job> findByPlaceholder(String text) {
        if (text == null) {
            return Optional.empty();
        }
        return state.jobs.stream().filter(j -> text.equals(j.placeholder)).findFirst();
    }

    // ------------------------------------------------------------------ placeholders

    /**
     * Text to use instead of a message of DBeaver history:
     * the answer for a delivered or finished placeholder, {@code null} for a placeholder still waiting
     * for its answer, the text itself otherwise.
     */
    public synchronized String resolveText(String text) {
        if (text == null) {
            return null;
        }
        Resolved r = state.resolved.get(ChatHistory.sha256(text));
        if (r != null) {
            return r.text;
        }
        Optional<Job> job = findByPlaceholder(text);
        if (job.isPresent()) {
            return job.get().isFinished() ? job.get().deliveryText() : null;
        }
        return text;
    }

    public synchronized boolean isPlaceholder(String text) {
        return text != null && (state.resolved.containsKey(ChatHistory.sha256(text)) || findByPlaceholder(text).isPresent());
    }

    // ------------------------------------------------------------------ chats

    public synchronized String chatOf(String conversationId) {
        return state.chats.get(conversationId);
    }

    public synchronized void linkChat(String conversationId, String chatId) {
        if (chatId == null) {
            state.chats.remove(conversationId);
        } else {
            state.chats.put(conversationId, chatId);
        }
        save();
    }

    public synchronized void forgetConversation(String conversationId) {
        state.chats.remove(conversationId);
        state.serverAnswers.keySet().removeIf(k -> k.startsWith(conversationId + "|"));
        save();
    }

    public synchronized void rememberServerAnswer(String conversationId, String text, String messageId) {
        putServerAnswer(conversationId, text, messageId);
        save();
    }

    private void putServerAnswer(String conversationId, String text, String messageId) {
        state.serverAnswers.put(conversationId + "|" + ChatHistory.sha256(text.strip()), messageId);
    }

    /** Id of an answer the server already stores in the chat, by its text. */
    public synchronized String serverAnswerId(String conversationId, String text) {
        return text == null ? null : state.serverAnswers.get(conversationId + "|" + ChatHistory.sha256(text.strip()));
    }

    public synchronized void saveNow() {
        save();
    }
}
