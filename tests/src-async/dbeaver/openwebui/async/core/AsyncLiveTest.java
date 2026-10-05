package dbeaver.openwebui.async.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The same flow as {@link AsyncHarnessTest}, but against a real Open WebUI (CI runs it in Docker,
 * with tests/mock_owui.py as the OpenAI-compatible upstream).
 *   java AsyncLiveTest http://127.0.0.1:8080/api/ <token> <model>
 */
public class AsyncLiveTest {
    static int passed, failed;

    static void check(String name, boolean ok, Object info) {
        if (ok) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name + " -> " + info); }
    }

    static class Sink implements BackgroundTask.Sink {
        final StringBuilder text = new StringBuilder();
        String done, error;
        public void text(String d) { text.append(d); }
        public void done(String t) { done = t; }
        public void error(String m) { error = m; }
        public boolean canceled() { return false; }
    }

    static JsonObject completion(String... userTexts) {
        JsonObject c = new JsonObject();
        JsonArray msgs = new JsonArray();
        JsonObject sys = new JsonObject(); sys.addProperty("role", "system"); sys.addProperty("content", "You are SQL expert"); msgs.add(sys);
        for (String u : userTexts) {
            JsonObject m = new JsonObject(); m.addProperty("role", "user"); m.addProperty("content", u); msgs.add(m);
        }
        c.add("messages", msgs);
        c.addProperty("temperature", 0.0);
        return c;
    }

    /** Open WebUI may eat a space next to a reasoning block: compare without spaces. */
    static boolean same(String expected, String actual) {
        return actual != null && expected.replace(" ", "").equals(actual.replace(" ", ""));
    }

    public static void main(String[] a) throws Exception {
        String base = a[0], token = a[1], model = a[2];
        String expected = a.length > 3 ? a[3] : "SELECT * FROM users;";
        ChatsApi api = new ChatsApi(base, token, Map.of(), Duration.ofSeconds(60));
        JobStore store = new JobStore(Files.createTempDirectory("live").resolve("state.json"));
        String conv = UUID.randomUUID().toString();
        long ts = System.currentTimeMillis() / 1000;

        BackgroundTask.Started st = BackgroundTask.start(api, store, new BackgroundTask.Request(conv, "Live test", model, null,
            List.of(new TurnMapper.Item(true, "all users", ts)), completion("all users"), true, JobStore.MODE_WAIT));
        check("server runs the request in background", st.job() != null, "direct answer: " + st.directAnswer());
        if (st.job() == null) {
            System.out.println("Live: passed " + passed + ", failed " + (failed));
            System.exit(1);
        }
        Sink sink = new Sink();
        boolean fin = BackgroundTask.poll(api, store, st.job(), sink, 500, 30_000);
        check("answer received", fin && same(expected, sink.done), "done=" + sink.done + " error=" + sink.error + " text=" + sink.text);
        JsonObject chat = api.getChat(store.chatOf(conv));
        System.out.println("chat: " + chat);
        JsonObject answer = ChatHistory.findMessage(chat, st.job().assistantId);
        check("answer stored in the chat", answer != null && same(expected, ReplyState.stripReasoning(ChatHistory.messageText(answer))), answer);
        check("answer has a parent", answer != null && answer.has("parentId") && !answer.get("parentId").isJsonNull(), answer);
        String title = chat.has("title") ? chat.get("title").getAsString() : null;
        check("chat title kept", "Live test".equals(title), title);

        // follow-up in the same chat
        BackgroundTask.Started st2 = BackgroundTask.start(api, store, new BackgroundTask.Request(conv, "Live test", model, null,
            List.of(new TurnMapper.Item(true, "all users", ts), new TurnMapper.Item(false, sink.done, ts + 1),
                new TurnMapper.Item(true, "only active", ts + 2)), completion("all users", "only active"), true, JobStore.MODE_WAIT));
        Sink sink2 = new Sink();
        BackgroundTask.poll(api, store, st2.job(), sink2, 500, 30_000);
        check("follow-up answered", same(expected, sink2.done), sink2.done + " / " + sink2.error);
        JsonObject chat2 = api.getChat(store.chatOf(conv));
        JsonObject msgs = chat2.getAsJsonObject("chat").getAsJsonObject("history").getAsJsonObject("messages");
        check("chat has 4 messages", msgs.size() == 4, msgs.keySet());

        // detached: background poller check
        String conv2 = UUID.randomUUID().toString();
        BackgroundTask.Started st3 = BackgroundTask.start(api, store, new BackgroundTask.Request(conv2, "Detached", model, null,
            List.of(new TurnMapper.Item(true, "count orders", ts)), completion("count orders"), true, JobStore.MODE_DETACHED));
        boolean done = false;
        for (int i = 0; i < 60 && !done; i++) {
            Thread.sleep(500);
            done = BackgroundTask.check(api, store, store.find(st3.job().id).orElseThrow());
        }
        JobStore.Job j = store.find(st3.job().id).orElseThrow();
        check("detached answer", done && same(expected, j.result), j.result + " / " + j.error);

        // mirror only
        String conv4 = UUID.randomUUID().toString();
        BackgroundTask.mirror(api, store, conv4, "Mirror", model, List.of(new TurnMapper.Item(true, "hi", ts), new TurnMapper.Item(false, "hello", ts + 1)));
        JsonObject m = api.getChat(store.chatOf(conv4)).getAsJsonObject("chat").getAsJsonObject("history").getAsJsonObject("messages");
        check("mirror chat", m.size() == 2, m);

        System.out.println();
        System.out.println("Live: passed " + passed + ", failed " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
