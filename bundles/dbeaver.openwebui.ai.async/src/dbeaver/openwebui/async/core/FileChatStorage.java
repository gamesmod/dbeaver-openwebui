/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.qm.AIChatStorage;
import org.jkiss.dbeaver.model.ai.qm.QMAIChatMessage;
import org.jkiss.dbeaver.model.ai.qm.QMAIChatRole;
import org.jkiss.dbeaver.model.ai.qm.QMAIContext;
import org.jkiss.dbeaver.model.ai.qm.QMAIContextObject;
import org.jkiss.dbeaver.model.ai.qm.QMAIContextObjectType;
import org.jkiss.dbeaver.model.ai.qm.QMAIConversationHistory;
import org.jkiss.dbeaver.model.ai.qm.QMAIDataSource;
import org.jkiss.dbeaver.model.ai.qm.QMAIMessageMeta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Chat storage of DBeaver AI assistant that survives restarts: one JSON file per conversation.
 * <p>
 * DBeaver CE keeps chats only in memory ({@code QMAIChatStorageInMemory}); with this storage the
 * conversation list, history and the "delete conversation" button work as in DBeaver PRO.
 * Placeholders of background answers are replaced on load and save (see {@link #setContentFixer}).
 */
public class FileChatStorage implements AIChatStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
    private static final int FORMAT = 1;

    private final Path dir;
    private final Map<String, QMAIConversationHistory> cache = new LinkedHashMap<>();
    private boolean loaded;
    private volatile UnaryOperator<String> contentFixer = UnaryOperator.identity();

    public FileChatStorage(@NotNull Path dir) {
        this.dir = dir;
    }

    public Path getDirectory() {
        return dir;
    }

    /** Applied to message texts on load and save: delivers background answers into stale copies. */
    public void setContentFixer(@Nullable UnaryOperator<String> fixer) {
        this.contentFixer = fixer == null ? UnaryOperator.identity() : fixer;
    }

    // ------------------------------------------------------------------ AIChatStorage

    @NotNull
    @Override
    public synchronized List<QMAIConversationHistory> findConversations(@NotNull String sessionId) throws DBException {
        ensureLoaded();
        List<QMAIConversationHistory> result = new ArrayList<>();
        for (QMAIConversationHistory h : cache.values()) {
            if (!h.isDeleted()) {
                result.add(h);
            }
        }
        result.sort(Comparator.comparing(FileChatStorage::lastTime));
        return result;
    }

    @Override
    public synchronized void saveConversation(@NotNull String sessionId, @NotNull QMAIConversationHistory chat) throws DBException {
        ensureLoaded();
        QMAIConversationHistory fixed = fix(chat);
        QMAIConversationHistory old = cache.get(fixed.getId());
        if (old != null && fixed.getContext().getObjects().isEmpty() && fixed.getContext().getContextJson() == null) {
            // Callers that do not know the context must not erase it
            fixed.setContext(old.getContext());
        }
        cache.put(fixed.getId(), fixed);
        write(fixed);
    }

    @Override
    public synchronized void appendMessages(@NotNull String conversationId, @NotNull List<QMAIChatMessage> messages) throws DBException {
        QMAIConversationHistory chat = require(conversationId);
        List<QMAIChatMessage> list = new ArrayList<>(chat.getMessages());
        for (QMAIChatMessage m : messages) {
            list.add(fixMessage(m));
        }
        int next = Math.max(chat.getNextMessageId(), list.stream().mapToInt(QMAIChatMessage::id).max().orElse(-1) + 1);
        QMAIConversationHistory updated = copy(chat, list, next);
        cache.put(conversationId, updated);
        write(updated);
    }

    @Override
    public synchronized void deleteMessage(@NotNull String conversationId, int messageId) throws DBException {
        QMAIConversationHistory chat = require(conversationId);
        List<QMAIChatMessage> list = new ArrayList<>(chat.getMessages());
        list.removeIf(m -> m.id() == messageId);
        chat.setMessages(list);
        write(chat);
    }

    @Override
    public synchronized void extendContext(@NotNull String conversationId, @NotNull Set<QMAIContextObject> extra) throws DBException {
        QMAIConversationHistory chat = require(conversationId);
        Set<QMAIContextObject> objects = new HashSet<>(chat.getContext().getObjects());
        objects.addAll(extra);
        chat.setContext(new QMAIContext(chat.getContext().getContextJson(), objects));
        write(chat);
    }

    @Override
    public synchronized void deleteConversation(@NotNull String conversationId) throws DBException {
        ensureLoaded();
        cache.remove(conversationId);
        try {
            Files.deleteIfExists(file(conversationId));
        } catch (IOException e) {
            throw new DBException("Can't delete conversation file: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void changeConversationProfile(@NotNull String conversationId, @Nullable String profileId, @Nullable String engineId) throws DBException {
        QMAIConversationHistory chat = require(conversationId);
        chat.setProfileId(profileId);
        chat.setEngineId(engineId);
        write(chat);
    }

    @Override
    public synchronized void renameConversation(@NotNull String conversationId, @NotNull String newName) throws DBException {
        QMAIConversationHistory chat = require(conversationId);
        chat.setCaption(newName);
        write(chat);
    }

    @NotNull
    @Override
    public List<QMAIMessageMeta> getConversationHistoryMeta(@NotNull UUID conversationId) throws DBException {
        return List.of();
    }

    @NotNull
    @Override
    public List<QMAIMessageMeta> getConversationHistoryMeta(
        @NotNull String sessionId,
        @NotNull String engineId,
        @NotNull Instant from,
        @NotNull Instant to
    ) throws DBException {
        return List.of();
    }

    @Override
    public boolean canPersist() {
        return true;
    }

    // ------------------------------------------------------------------ background answers

    /**
     * Delivers an answer into a stored conversation: replaces the placeholder message, or appends
     * an assistant message when DBeaver was closed while waiting (no placeholder was saved).
     *
     * @return {@code false} if the conversation is not stored
     */
    public synchronized boolean deliver(@NotNull String conversationId, @Nullable String placeholder, @NotNull String text, boolean error)
        throws DBException {
        ensureLoaded();
        QMAIConversationHistory chat = cache.get(conversationId);
        if (chat == null) {
            return false;
        }
        List<QMAIChatMessage> list = new ArrayList<>(chat.getMessages());
        if (placeholder != null) {
            for (int i = list.size() - 1; i >= 0; i--) {
                QMAIChatMessage m = list.get(i);
                if (placeholder.equals(m.content())) {
                    list.set(i, new QMAIChatMessage(m.id(), text, null, error ? QMAIChatRole.ERROR : m.role(),
                        null, null, m.timestamp(), m.deleted(), m.meta()));
                    chat.setMessages(list);
                    write(chat);
                    return true;
                }
            }
            // The placeholder was not saved yet (or removed by the user): append
        }
        int id = chat.getNextMessageId();
        for (QMAIChatMessage m : list) {
            id = Math.max(id, m.id() + 1);
        }
        list.add(new QMAIChatMessage(id, text, null, error ? QMAIChatRole.ERROR : QMAIChatRole.ASSISTANT,
            null, null, Instant.now(), false, null));
        QMAIConversationHistory updated = copy(chat, list, id + 1);
        cache.put(conversationId, updated);
        write(updated);
        return true;
    }

    /** Replaces the text of one stored message (the live conversation was patched in memory). */
    public synchronized void replaceMessage(@NotNull String conversationId, int messageId, @NotNull String text) throws DBException {
        ensureLoaded();
        QMAIConversationHistory chat = cache.get(conversationId);
        if (chat == null) {
            return;
        }
        List<QMAIChatMessage> list = new ArrayList<>(chat.getMessages());
        for (int i = 0; i < list.size(); i++) {
            QMAIChatMessage m = list.get(i);
            if (m.id() == messageId) {
                list.set(i, new QMAIChatMessage(m.id(), text, null, m.role(), m.functionCall(), m.functionResult(),
                    m.timestamp(), m.deleted(), m.meta()));
                chat.setMessages(list);
                write(chat);
                return;
            }
        }
    }

    @Nullable
    public synchronized QMAIConversationHistory get(@NotNull String conversationId) throws DBException {
        ensureLoaded();
        return cache.get(conversationId);
    }

    // ------------------------------------------------------------------ internals

    private QMAIConversationHistory require(String conversationId) throws DBException {
        ensureLoaded();
        QMAIConversationHistory chat = cache.get(conversationId);
        if (chat == null) {
            throw new DBException("Conversation not found: " + conversationId);
        }
        return chat;
    }

    private void ensureLoaded() throws DBException {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new DBException("Can't read AI chats from " + dir + ": " + e.getMessage(), e);
        }
        for (Path f : files) {
            try {
                QMAIConversationHistory h = fromJson(JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject());
                QMAIConversationHistory fixed = fix(h);
                cache.put(fixed.getId(), fixed);
                if (fixed != h) {
                    write(fixed);
                }
            } catch (IOException | RuntimeException e) {
                // skip a broken file, keep it for analysis
                try {
                    Files.move(f, f.resolveSibling(f.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // nothing
                }
            }
        }
    }

    /** Returns the same object when nothing changed. */
    private QMAIConversationHistory fix(QMAIConversationHistory h) {
        List<QMAIChatMessage> list = h.getMessages();
        List<QMAIChatMessage> fixed = null;
        for (int i = 0; i < list.size(); i++) {
            QMAIChatMessage m = list.get(i);
            QMAIChatMessage f = fixMessage(m);
            if (f != m) {
                if (fixed == null) {
                    fixed = new ArrayList<>(list);
                }
                fixed.set(i, f);
            }
        }
        if (fixed == null) {
            return h;
        }
        return copy(h, fixed, h.getNextMessageId());
    }

    private QMAIChatMessage fixMessage(QMAIChatMessage m) {
        if (m.role() != QMAIChatRole.ASSISTANT) {
            return m;
        }
        String text = contentFixer.apply(m.content());
        if (text == null || text.equals(m.content())) {
            return m;
        }
        return new QMAIChatMessage(m.id(), text, null, m.role(), m.functionCall(), m.functionResult(),
            m.timestamp(), m.deleted(), m.meta());
    }

    private static QMAIConversationHistory copy(QMAIConversationHistory h, List<QMAIChatMessage> messages, int nextMessageId) {
        return new QMAIConversationHistory(h.getId(), h.getCaption(), h.getPromptGeneratorId(), h.getDataSource(),
            messages, h.getContext(), h.getProfileId(), h.getEngineId(), nextMessageId, h.isDeleted());
    }

    private static Instant lastTime(QMAIConversationHistory h) {
        return h.getMessages().isEmpty() ? Instant.EPOCH : h.getMessages().getLast().timestamp();
    }

    private Path file(String conversationId) {
        String safe = conversationId.replaceAll("[^A-Za-z0-9._-]", "_");
        return dir.resolve(safe + ".json");
    }

    private void write(QMAIConversationHistory h) throws DBException {
        try {
            Files.createDirectories(dir);
            Path target = file(h.getId());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(toJson(h)), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new DBException("Can't save AI chat to " + dir + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ JSON

    static JsonObject toJson(QMAIConversationHistory h) {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("id", h.getId());
        o.addProperty("caption", h.getCaption());
        o.addProperty("promptGeneratorId", h.getPromptGeneratorId());
        if (h.getDataSource() != null) {
            JsonObject ds = new JsonObject();
            ds.addProperty("projectId", h.getDataSource().projectId());
            ds.addProperty("dataSourceId", h.getDataSource().dataSourceId());
            o.add("dataSource", ds);
        }
        JsonObject ctx = new JsonObject();
        ctx.addProperty("contextJson", h.getContext().getContextJson());
        JsonArray objs = new JsonArray();
        for (QMAIContextObject co : h.getContext().getObjects()) {
            JsonObject x = new JsonObject();
            x.addProperty("name", co.name());
            x.addProperty("type", co.type() == null ? null : co.type().name());
            objs.add(x);
        }
        ctx.add("objects", objs);
        o.add("context", ctx);
        o.addProperty("profileId", h.getProfileId());
        o.addProperty("engineId", h.getEngineId());
        o.addProperty("nextMessageId", h.getNextMessageId());
        JsonArray msgs = new JsonArray();
        for (QMAIChatMessage m : h.getMessages()) {
            JsonObject x = new JsonObject();
            x.addProperty("id", m.id());
            x.addProperty("role", m.role().name());
            x.addProperty("content", m.content());
            x.addProperty("displayMessage", m.displayMessage());
            x.addProperty("functionCall", m.functionCall());
            x.addProperty("functionResult", m.functionResult());
            x.addProperty("timestamp", m.timestamp().toString());
            if (m.deleted()) {
                x.addProperty("deleted", true);
            }
            if (m.meta() != null && !m.meta().isEmpty()) {
                JsonArray meta = new JsonArray();
                for (QMAIMessageMeta mm : m.meta()) {
                    JsonObject y = new JsonObject();
                    y.addProperty("type", mm.type());
                    y.addProperty("engineId", mm.engineId());
                    y.addProperty("modelId", mm.modelId());
                    y.addProperty("systemPromptLength", mm.systemPromptLength());
                    y.addProperty("timeSpentMs", mm.timeSpent().toMillis());
                    y.addProperty("totalInputTokens", mm.totalInputTokens());
                    y.addProperty("cachedTokens", mm.cachedTokens());
                    y.addProperty("totalOutputTokens", mm.totalOutputTokens());
                    y.addProperty("reasoningTokens", mm.reasoningTokens());
                    meta.add(y);
                }
                x.add("meta", meta);
            }
            msgs.add(x);
        }
        o.add("messages", msgs);
        return o;
    }

    static QMAIConversationHistory fromJson(JsonObject o) {
        QMAIDataSource ds = null;
        if (o.has("dataSource") && o.get("dataSource").isJsonObject()) {
            JsonObject d = o.getAsJsonObject("dataSource");
            ds = new QMAIDataSource(str(d, "projectId"), str(d, "dataSourceId"));
        }
        String contextJson = null;
        Set<QMAIContextObject> objects = new LinkedHashSet<>();
        if (o.has("context") && o.get("context").isJsonObject()) {
            JsonObject c = o.getAsJsonObject("context");
            contextJson = str(c, "contextJson");
            if (c.has("objects") && c.get("objects").isJsonArray()) {
                for (JsonElement e : c.getAsJsonArray("objects")) {
                    JsonObject x = e.getAsJsonObject();
                    String type = str(x, "type");
                    objects.add(new QMAIContextObject(str(x, "name"), type == null ? null : enumOr(QMAIContextObjectType.class, type, null)));
                }
            }
        }
        List<QMAIChatMessage> messages = new ArrayList<>();
        if (o.has("messages") && o.get("messages").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("messages")) {
                JsonObject x = e.getAsJsonObject();
                List<QMAIMessageMeta> meta = null;
                if (x.has("meta") && x.get("meta").isJsonArray()) {
                    meta = new ArrayList<>();
                    for (JsonElement me : x.getAsJsonArray("meta")) {
                        JsonObject y = me.getAsJsonObject();
                        meta.add(new QMAIMessageMeta(
                            nz(str(y, "type")), nz(str(y, "engineId")), str(y, "modelId"), integer(y, "systemPromptLength"),
                            Duration.ofMillis(y.has("timeSpentMs") ? y.get("timeSpentMs").getAsLong() : 0),
                            integer(y, "totalInputTokens"), integer(y, "cachedTokens"), integer(y, "totalOutputTokens"),
                            integer(y, "reasoningTokens")));
                    }
                }
                String ts = str(x, "timestamp");
                messages.add(new QMAIChatMessage(
                    integer(x, "id"),
                    nz(str(x, "content")),
                    str(x, "displayMessage"),
                    enumOr(QMAIChatRole.class, str(x, "role"), QMAIChatRole.USER),
                    str(x, "functionCall"),
                    str(x, "functionResult"),
                    ts == null ? Instant.EPOCH : Instant.parse(ts),
                    x.has("deleted") && x.get("deleted").getAsBoolean(),
                    meta));
            }
        }
        int next = o.has("nextMessageId") ? o.get("nextMessageId").getAsInt()
            : messages.stream().mapToInt(QMAIChatMessage::id).max().orElse(-1) + 1;
        return new QMAIConversationHistory(
            str(o, "id"),
            nz(str(o, "caption")),
            str(o, "promptGeneratorId"),
            ds,
            messages,
            new QMAIContext(contextJson, objects),
            str(o, "profileId"),
            str(o, "engineId"),
            next,
            false);
    }

    private static String str(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v == null || v instanceof JsonNull || !v.isJsonPrimitive() ? null : v.getAsString();
    }

    private static int integer(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v == null || !v.isJsonPrimitive() ? 0 : v.getAsInt();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E def) {
        if (name == null) {
            return def;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
