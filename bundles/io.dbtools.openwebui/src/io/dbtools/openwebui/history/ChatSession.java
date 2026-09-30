package io.dbtools.openwebui.history;

import io.dbtools.openwebui.api.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Один диалог: сообщения для модели плюс то, как их показывать пользователю.
 * <p>
 * У пользовательского сообщения две формы: {@code content} уходит модели (может содержать структуру БД
 * и полный текст запроса), {@code display} — короткий текст для панели и списка истории.
 * Сериализуется в JSON как есть; методы потокобезопасны.
 */
public final class ChatSession {

    /** Сообщение диалога. {@code display} = null — показывать {@code content}. */
    public static final class Entry {
        public String role;
        public String content;
        public String display;
        public long time;
        /** id сообщения в Open WebUI, если оно пришло оттуда. */
        public String remoteMessageId;

        public Entry() {
        }

        Entry(String role, String content, String display) {
            this.role = role;
            this.content = content;
            this.display = display;
            this.time = System.currentTimeMillis();
        }

        public String shown() {
            return display != null ? display : content;
        }
    }

    public String id;
    public String title;
    public long createdAt;
    public long updatedAt;
    public String model;
    /** Подключение DBeaver, с которым шёл диалог (для списка истории). */
    public String connection;
    /** id чата в Open WebUI, если диалог синхронизирован. */
    public String remoteId;
    public List<Entry> messages = new ArrayList<>();

    public ChatSession() {
    }

    public static ChatSession create() {
        ChatSession s = new ChatSession();
        s.id = UUID.randomUUID().toString();
        s.createdAt = System.currentTimeMillis();
        s.updatedAt = s.createdAt;
        return s;
    }

    /** Есть ли в диалоге хоть одна реплика пользователя. */
    public synchronized boolean hasUserMessages() {
        return messages.stream().anyMatch(e -> "user".equals(e.role));
    }

    public synchronized boolean isEmpty() {
        return messages.isEmpty();
    }

    /** Заменяет или добавляет системное сообщение (всегда первое). */
    public synchronized void setSystem(String content) {
        if (!messages.isEmpty() && "system".equals(messages.get(0).role)) {
            messages.get(0).content = content;
        } else {
            messages.add(0, new Entry("system", content, null));
        }
    }

    /** Новая тема: сообщения целиком из готового промпта; у последней реплики пользователя — короткое отображение. */
    public synchronized void replaceWith(List<ChatMessage> prompt, String lastUserDisplay) {
        messages.clear();
        for (int i = 0; i < prompt.size(); i++) {
            ChatMessage m = prompt.get(i);
            boolean lastUser = "user".equals(m.role()) && i == prompt.size() - 1;
            messages.add(new Entry(m.role(), m.content(), lastUser ? lastUserDisplay : null));
        }
        touchTitle(lastUserDisplay);
    }

    public synchronized void addUser(String content, String display) {
        messages.add(new Entry("user", content, display));
        touchTitle(display != null ? display : content);
    }

    public synchronized void addAssistant(String content) {
        messages.add(new Entry("assistant", content, null));
        updatedAt = System.currentTimeMillis();
    }

    /** Убирает последнюю реплику пользователя, оставшуюся без ответа (после ошибки). */
    public synchronized void removeTrailingUser() {
        if (!messages.isEmpty() && "user".equals(messages.get(messages.size() - 1).role)) {
            messages.remove(messages.size() - 1);
        }
    }

    public synchronized List<ChatMessage> toRequest() {
        List<ChatMessage> out = new ArrayList<>(messages.size());
        for (Entry e : messages) {
            out.add(new ChatMessage(e.role, e.content));
        }
        return out;
    }

    public synchronized List<Entry> entries() {
        List<Entry> copy = new ArrayList<>(messages.size());
        for (Entry e : messages) {
            Entry c = new Entry(e.role, e.content, e.display);
            c.time = e.time;
            copy.add(c);
        }
        return copy;
    }

    /** Загрузка из Open WebUI: сообщения без отдельного отображения. */
    public static ChatSession fromRemote(String remoteId, String title, String model, List<ChatMessage> msgs,
                                         List<String> remoteMessageIds) {
        ChatSession s = create();
        s.remoteId = remoteId;
        s.title = title;
        s.model = model;
        for (int i = 0; i < msgs.size(); i++) {
            ChatMessage m = msgs.get(i);
            Entry e = new Entry(m.role(), m.content(), null);
            e.remoteMessageId = remoteMessageIds != null && i < remoteMessageIds.size() ? remoteMessageIds.get(i) : null;
            s.messages.add(e);
        }
        return s;
    }

    /** id сообщений Open WebUI параллельно {@link #toRequest()} (null — ещё не было в Open WebUI). */
    public synchronized List<String> remoteMessageIds() {
        List<String> out = new ArrayList<>(messages.size());
        for (Entry e : messages) {
            out.add(e.remoteMessageId);
        }
        return out;
    }

    private void touchTitle(String text) {
        updatedAt = System.currentTimeMillis();
        if ((title == null || title.isBlank()) && text != null && !text.isBlank()) {
            String t = text.strip().replaceAll("\\s+", " ");
            title = t.length() > 70 ? t.substring(0, 70) + "…" : t;
        }
    }
}
