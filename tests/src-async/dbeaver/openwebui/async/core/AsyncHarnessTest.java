package dbeaver.openwebui.async.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jkiss.dbeaver.model.ai.qm.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline checks of the background chats add-on against tests/mock_owui.py. */
public class AsyncHarnessTest {
    static int passed, failed;

    static void check(String name, boolean ok, Object info) {
        if (ok) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name + " -> " + info); }
    }

    static final String API = "http://127.0.0.1:18080/api/";

    static ChatsApi api(String token) {
        return new ChatsApi(API, token, Map.of("X-Test", "1"), Duration.ofSeconds(10));
    }

    static JsonObject mockState() throws Exception {
        String body = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18080/_bg")).build(),
            HttpResponse.BodyHandlers.ofString()).body();
        return JsonParser.parseString(body).getAsJsonObject();
    }

    static class Sink implements BackgroundTask.Sink {
        final StringBuilder text = new StringBuilder();
        String done, error;
        int chunks;
        volatile boolean cancel;
        public void text(String d) { text.append(d); chunks++; }
        public void done(String t) { done = t; }
        public void error(String m) { error = m; }
        public boolean canceled() { return cancel; }
    }

    static JsonObject completion() {
        JsonObject c = new JsonObject();
        JsonArray msgs = new JsonArray();
        JsonObject sys = new JsonObject(); sys.addProperty("role", "system"); sys.addProperty("content", "You are SQL expert"); msgs.add(sys);
        c.add("messages", msgs);
        return c;
    }

    static JsonObject completion(String... userTexts) {
        JsonObject c = completion();
        for (String u : userTexts) {
            JsonObject m = new JsonObject(); m.addProperty("role", "user"); m.addProperty("content", u);
            c.getAsJsonArray("messages").add(m);
        }
        return c;
    }

    static TurnMapper.Item user(String s) { return new TurnMapper.Item(true, s, 1_700_000_000L); }
    static TurnMapper.Item assistant(String s) { return new TurnMapper.Item(false, s, 1_700_000_100L); }

    public static void main(String[] a) throws Exception {
        Path tmp = Files.createTempDirectory("owui-async");

        // --- pure helpers
        check("supportsChats /api/", ChatsApi.supportsChats("http://h:3000/api/"), null);
        check("supportsChats /v1/ no", !ChatsApi.supportsChats("http://h:8000/v1/"), null);
        check("chatUrl", ChatsApi.chatUrl("http://h:3000/api/", "abc").equals("http://h:3000/c/abc"), ChatsApi.chatUrl("http://h:3000/api/", "abc"));
        check("strip think", ReplyState.stripReasoning("<think>x</think>\n\nSELECT 1").equals("SELECT 1"), null);
        check("strip open think", ReplyState.stripReasoning("<think>partial").isEmpty(), null);
        check("strip details", ReplyState.stripReasoning("<details type=\"reasoning\" done=\"true\">\n<summary>T</summary>\nhm\n</details>\nSELECT 2").equals("SELECT 2"), null);
        check("strip open details", ReplyState.stripReasoning("<details type=\"reasoning\" done=\"false\">\nhm").isEmpty(), null);

        // ChatHistory chain
        List<ChatHistory.Turn> turns = List.of(
            new ChatHistory.Turn("u1", "user", "q1", 1), new ChatHistory.Turn("a1", "assistant", null, 2),
            new ChatHistory.Turn("u2", "user", "q2", 3));
        JsonObject chat = ChatHistory.build("T", "m", turns, "a2");
        JsonObject msgs = chat.getAsJsonObject("history").getAsJsonObject("messages");
        check("history excludes external", !msgs.has("a1") && msgs.has("u1") && msgs.has("u2") && msgs.has("a2"), msgs.keySet());
        check("history parent chain", "a1".equals(msgs.getAsJsonObject("u2").get("parentId").getAsString())
            && "u2".equals(msgs.getAsJsonObject("a2").get("parentId").getAsString()), msgs);
        check("history currentId", "a2".equals(chat.getAsJsonObject("history").get("currentId").getAsString()), chat);
        check("stable ids", ChatHistory.messageId("c", 0, "user", "x").equals(ChatHistory.messageId("c", 0, "user", "x"))
            && !ChatHistory.messageId("c", 0, "user", "x").equals(ChatHistory.messageId("c", 1, "user", "x")), null);

        // --- background request, DBeaver waits
        JobStore store = new JobStore(tmp.resolve("state.json"));
        ChatsApi api = api("sk-test");
        String conv = UUID.randomUUID().toString();
        BackgroundTask.Started st = BackgroundTask.start(api, store, new BackgroundTask.Request(conv, "Users", "llama3.1:8b", "p1",
            List.of(user("all users")), completion("all users"), true, JobStore.MODE_WAIT));
        check("started in background", st.job() != null && st.directAnswer() == null, st);
        String chatId = store.chatOf(conv);
        check("chat linked", chatId != null, null);
        Sink sink = new Sink();
        boolean finished = BackgroundTask.poll(api, store, st.job(), sink, 150, 5000);
        String expected = "Answer 2 for: all users";
        check("poll finished", finished && expected.equals(sink.done), sink.done);
        check("streamed text equals answer", expected.equals(sink.text.toString()), sink.text);
        check("streamed in several chunks", sink.chunks >= 2, sink.chunks);
        check("job delivered and forgotten", store.jobs().isEmpty(), store.jobs());
        JsonObject state = mockState();
        JsonArray reqs = state.getAsJsonArray("requests");
        JsonObject bg = reqs.get(reqs.size() - 1).getAsJsonObject();
        check("request has chat_id/id/session_id", chatId.equals(bg.get("chat_id").getAsString()) && bg.has("id")
            && bg.get("session_id").getAsString().startsWith("dbeaver-") && bg.get("stream").getAsBoolean(), bg);
        check("request has user_message", "all users".equals(bg.getAsJsonObject("user_message").get("content").getAsString()), bg);
        check("background tasks off, title kept", !bg.getAsJsonObject("background_tasks").has("title_generation")
            && !bg.getAsJsonObject("background_tasks").get("tags_generation").getAsBoolean(), bg);
        JsonObject srvChat = state.getAsJsonObject("chats").getAsJsonObject(chatId).getAsJsonObject("chat");
        JsonObject srvMsgs = srvChat.getAsJsonObject("history").getAsJsonObject("messages");
        check("server chat has 2 messages", srvMsgs.size() == 2, srvMsgs.keySet());
        JsonObject srvAnswer = srvMsgs.getAsJsonObject(st.job().assistantId);
        check("server answer linked to user message", srvAnswer != null && bg.getAsJsonObject("user_message").get("id").getAsString()
            .equals(srvAnswer.get("parentId").getAsString()), srvAnswer);
        check("chat title", "Users".equals(srvChat.get("title").getAsString()), srvChat.get("title"));

        // --- follow-up in the same conversation: same chat, server answer referenced, not re-uploaded
        String answer1 = sink.done;
        BackgroundTask.Started st2 = BackgroundTask.start(api, store, new BackgroundTask.Request(conv, "Users", "llama3.1:8b", "p1",
            List.of(user("all users"), assistant(answer1), user("only active")), completion("all users", "only active"), true, JobStore.MODE_WAIT));
        check("same chat reused", chatId.equals(store.chatOf(conv)), store.chatOf(conv));
        Sink sink2 = new Sink();
        BackgroundTask.poll(api, store, st2.job(), sink2, 150, 5000);
        check("second answer", "Answer 3 for: only active".equals(sink2.done), sink2.done);
        srvMsgs = mockState().getAsJsonObject("chats").getAsJsonObject(chatId).getAsJsonObject("chat").getAsJsonObject("history").getAsJsonObject("messages");
        check("server chat has 4 messages (no duplicates)", srvMsgs.size() == 4, srvMsgs.keySet());
        JsonObject q2 = null;
        for (String k : srvMsgs.keySet()) {
            if ("only active".equals(srvMsgs.getAsJsonObject(k).get("content").getAsString())) q2 = srvMsgs.getAsJsonObject(k);
        }
        check("follow-up parent is the server answer", q2 != null && st.job().assistantId.equals(q2.get("parentId").getAsString()), q2);

        // --- detached mode: poller delivers later
        String conv2 = UUID.randomUUID().toString();
        BackgroundTask.Started st3 = BackgroundTask.start(api, store, new BackgroundTask.Request(conv2, "Orders", "bg-slow", "p1",
            List.of(user("count orders")), completion("count orders"), true, JobStore.MODE_DETACHED));
        String placeholder = "⏳ pending #" + st3.job().id.substring(0, 4);
        store.update(st3.job().id, j -> j.placeholder = placeholder);
        check("placeholder unresolved -> dropped from history", store.resolveText(placeholder) == null, null);
        check("placeholder recognized", store.isPlaceholder(placeholder), null);
        boolean done = false;
        for (int i = 0; i < 60 && !done; i++) {
            Thread.sleep(250);
            done = BackgroundTask.check(api, store, store.find(st3.job().id).orElseThrow());
        }
        JobStore.Job j3 = store.find(st3.job().id).orElseThrow();
        check("detached finished", done && JobStore.STATE_DONE.equals(j3.state) && "Answer 2 for: count orders".equals(j3.result), j3.result);
        check("finished placeholder resolves to answer", "Answer 2 for: count orders".equals(store.resolveText(placeholder)), store.resolveText(placeholder));
        // a placeholder in history while the next question is asked: referenced by server id
        List<ChatHistory.Turn> mapped = TurnMapper.map(conv2, List.of(user("count orders"), assistant(placeholder), user("and today?")), store);
        check("placeholder mapped to server message", mapped.get(1).isExternal() && j3.assistantId.equals(mapped.get(1).id()), mapped);
        store.delivered(j3.id);
        check("delivered placeholder still resolves", "Answer 2 for: count orders".equals(store.resolveText(placeholder)), null);

        // persistence of the store
        JobStore reopened = new JobStore(tmp.resolve("state.json"));
        check("store persisted links", chatId.equals(reopened.chatOf(conv)), reopened.chatOf(conv));
        check("store persisted resolved placeholders", "Answer 2 for: count orders".equals(reopened.resolveText(placeholder)), null);

        // --- server error
        BackgroundTask.Started st4 = BackgroundTask.start(api, store, new BackgroundTask.Request(UUID.randomUUID().toString(), "E", "bg-error", null,
            List.of(user("boom")), completion("boom"), true, JobStore.MODE_WAIT));
        Sink sink4 = new Sink();
        BackgroundTask.poll(api, store, st4.job(), sink4, 150, 5000);
        check("server error reported", "Model crashed".equals(sink4.error), sink4.error);

        // --- cancel
        String conv5 = UUID.randomUUID().toString();
        BackgroundTask.Started st5 = BackgroundTask.start(api, store, new BackgroundTask.Request(conv5, "C", "bg-slow", null,
            List.of(user("long question")), completion("long question"), true, JobStore.MODE_WAIT));
        Sink sink5 = new Sink() {
            public void text(String d) { super.text(d); cancel = true; }
        };
        BackgroundTask.poll(api, store, st5.job(), sink5, 150, 5000);
        Thread.sleep(800);
        JsonObject srv5 = ChatHistory.findMessage(mockState().getAsJsonObject("chats").getAsJsonObject(store.chatOf(conv5)), st5.job().assistantId);
        check("cancel stops server task", srv5 != null && srv5.get("done").getAsBoolean()
            && srv5.get("content").getAsString().length() < "Answer 2 for: long question".length() + 20, srv5);
        check("canceled job removed", store.find(st5.job().id).isEmpty(), null);

        // --- old server: no "done", no overlay
        BackgroundTask.Started st6 = BackgroundTask.start(api, store, new BackgroundTask.Request(UUID.randomUUID().toString(), "O", "bg-old", null,
            List.of(user("legacy")), completion("legacy"), true, JobStore.MODE_WAIT));
        Sink sink6 = new Sink();
        BackgroundTask.poll(api, store, st6.job(), sink6, 150, 5000);
        check("old server finished by task list", "Answer 2 for: legacy".equals(sink6.done), sink6.done);

        // --- chat deleted in Open WebUI: recreated
        String conv7 = UUID.randomUUID().toString();
        store.linkChat(conv7, "deleted-chat");
        BackgroundTask.Started st7 = BackgroundTask.start(api, store, new BackgroundTask.Request(conv7, "R", "llama3.1:8b", null,
            List.of(user("again")), completion("again"), false, JobStore.MODE_WAIT));
        check("deleted chat recreated", !"deleted-chat".equals(store.chatOf(conv7)) && store.chatOf(conv7) != null, store.chatOf(conv7));
        Sink sink7 = new Sink();
        BackgroundTask.poll(api, store, st7.job(), sink7, 150, 5000);
        check("thinking kept when not hidden", sink7.done != null && sink7.done.startsWith("<think>planning</think>"), sink7.done);

        // --- errors
        try {
            BackgroundTask.start(api("bad"), store, new BackgroundTask.Request(UUID.randomUUID().toString(), "X", "m", null,
                List.of(user("q")), completion("q"), true, JobStore.MODE_WAIT));
            check("bad token", false, "no error");
        } catch (OwuiException e) {
            check("bad token error", e.getStatus() == 401 && e.getMessage().contains("Not authenticated"), e.getMessage());
        }
        try {
            BackgroundTask.start(api, store, new BackgroundTask.Request(UUID.randomUUID().toString(), "X", "m", null,
                List.of(user("q"), assistant("a")), completion("q"), true, JobStore.MODE_WAIT));
            check("must end with user", false, "no error");
        } catch (OwuiException e) {
            check("must end with user message", e.getMessage().contains("user message"), e.getMessage());
        }
        try {
            new ChatsApi("http://127.0.0.1:1/api/", null, null, Duration.ofSeconds(2)).getChat("x");
            check("unreachable", false, "no error");
        } catch (OwuiException e) {
            check("unreachable is transient", e.isTransient(), e.getMessage());
        }
        // mirror only
        String conv8 = UUID.randomUUID().toString();
        BackgroundTask.mirror(api, store, conv8, "Mirror", "gpt-4o", List.of(user("hi"), assistant("hello")));
        JsonObject m8 = mockState().getAsJsonObject("chats").getAsJsonObject(store.chatOf(conv8)).getAsJsonObject("chat");
        check("mirror created chat with 2 messages", m8.getAsJsonObject("history").getAsJsonObject("messages").size() == 2, m8);
        BackgroundTask.mirror(api, store, conv8, "Mirror", "gpt-4o", List.of(user("hi"), assistant("hello"), user("more"), assistant("sure")));
        m8 = mockState().getAsJsonObject("chats").getAsJsonObject(store.chatOf(conv8)).getAsJsonObject("chat");
        check("mirror appends without duplicates", m8.getAsJsonObject("history").getAsJsonObject("messages").size() == 4, m8);

        // --- local chat storage
        FileChatStorage fs = new FileChatStorage(tmp.resolve("chats"));
        QMAIChatMessage m1 = new QMAIChatMessage(0, "q", null, QMAIChatRole.USER, null, null, Instant.parse("2026-10-01T10:00:00Z"), false, null);
        QMAIChatMessage m2 = new QMAIChatMessage(1, placeholder, null, QMAIChatRole.ASSISTANT, null, null, Instant.parse("2026-10-01T10:00:05Z"), false,
            List.of(new QMAIMessageMeta("prompt", "openwebui", "llama", 120, Duration.ofMillis(1500), 10, 0, 20, 0)));
        QMAIConversationHistory h = new QMAIConversationHistory("c-1", "Users", "sql", new QMAIDataSource("General", "pg-1"),
            new ArrayList<>(List.of(m1, m2)), new QMAIContext("{\"scope\":\"CURRENT_SCHEMA\"}", Set.of(new QMAIContextObject("public", QMAIContextObjectType.SCHEMA))),
            "prof", "openwebui", 2, false);
        fs.saveConversation("s", h);
        FileChatStorage fs2 = new FileChatStorage(tmp.resolve("chats"));
        List<QMAIConversationHistory> loaded = fs2.findConversations("other-session");
        QMAIConversationHistory l = loaded.getFirst();
        check("storage roundtrip", loaded.size() == 1 && "Users".equals(l.getCaption()) && "pg-1".equals(l.getDataSource().dataSourceId())
            && l.getMessages().size() == 2 && l.getNextMessageId() == 2 && "prof".equals(l.getProfileId()), l.getMessages());
        check("storage meta roundtrip", l.getMessages().get(1).meta().getFirst().timeSpent().toMillis() == 1500
            && l.getMessages().get(1).meta().getFirst().totalOutputTokens() == 20, l.getMessages().get(1).meta());
        check("storage context roundtrip", l.getContext().getContextJson().contains("CURRENT_SCHEMA")
            && l.getContext().getObjects().iterator().next().type() == QMAIContextObjectType.SCHEMA, null);
        check("storage can persist", fs2.canPersist(), null);
        fs2.deliver("c-1", placeholder, "SELECT count(*) FROM orders;", false);
        check("deliver replaces placeholder", "SELECT count(*) FROM orders;".equals(new FileChatStorage(tmp.resolve("chats")).get("c-1").getMessages().get(1).content()), null);
        fs2.deliver("c-1", null, "late answer", false);
        QMAIConversationHistory l2 = new FileChatStorage(tmp.resolve("chats")).get("c-1");
        check("deliver appends when no placeholder", l2.getMessages().size() == 3 && "late answer".equals(l2.getMessages().get(2).content())
            && l2.getMessages().get(2).id() == 2 && l2.getNextMessageId() == 3, l2.getMessages());
        // content fixer on save: a stale copy with the placeholder gets the answer
        FileChatStorage fs3 = new FileChatStorage(tmp.resolve("chats"));
        fs3.setContentFixer(t -> t.equals("stale") ? "fresh" : t);
        QMAIChatMessage stale = new QMAIChatMessage(3, "stale", null, QMAIChatRole.ASSISTANT, null, null, Instant.now(), false, null);
        List<QMAIChatMessage> withStale = new ArrayList<>(l2.getMessages()); withStale.add(stale);
        fs3.saveConversation("s", new QMAIConversationHistory("c-1", "Users", "sql", l2.getDataSource(), withStale,
            new QMAIContext(null, Set.of()), "prof", "openwebui", 4, false));
        QMAIConversationHistory l3 = new FileChatStorage(tmp.resolve("chats")).get("c-1");
        check("fixer applied on save", "fresh".equals(l3.getMessages().get(3).content()), l3.getMessages());
        check("context kept when caller has none", l3.getContext().getContextJson() != null, null);
        fs3.renameConversation("c-1", "Renamed");
        fs3.deleteMessage("c-1", 3);
        QMAIConversationHistory l4 = new FileChatStorage(tmp.resolve("chats")).get("c-1");
        check("rename and delete message", "Renamed".equals(l4.getCaption()) && l4.getMessages().size() == 3, l4.getMessages());
        fs3.deleteConversation("c-1");
        check("delete conversation", new FileChatStorage(tmp.resolve("chats")).findConversations("s").isEmpty(), null);
        Files.writeString(tmp.resolve("chats").resolve("bad.json"), "{broken");
        check("broken file skipped", new FileChatStorage(tmp.resolve("chats")).findConversations("s").isEmpty()
            && Files.exists(tmp.resolve("chats").resolve("bad.json.broken")), null);

        System.out.println();
        System.out.println("Async: passed " + passed + ", failed " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
