package io.dbtools.openwebui.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dbtools.openwebui.history.ChatSession;
import io.dbtools.openwebui.history.ChatStore;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Смоук-тест истории: API чатов Open WebUI против фейкового сервера и локальное хранилище.
 */
public class HistorySmokeTest {

    private static int passed;

    public static void main(String[] args) throws Exception {
        Map<String, JsonObject> chats = new ConcurrentHashMap<>();
        AtomicInteger seq = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chats", ex -> {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (method.equals("GET") && (path.equals("/api/v1/chats/") || path.equals("/api/v1/chats"))) {
                StringBuilder sb = new StringBuilder("[");
                chats.forEach((id, c) -> {
                    if (sb.length() > 1) {
                        sb.append(',');
                    }
                    sb.append("{\"id\":\"").append(id).append("\",\"title\":\"")
                        .append(c.getAsJsonObject("chat").get("title").getAsString())
                        .append("\",\"updated_at\":1790000000,\"created_at\":1790000000}");
                });
                respond(ex, 200, sb.append(']').toString());
                return;
            }
            String id = path.substring("/api/v1/chats/".length());
            if (method.equals("POST")) {
                JsonObject form = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (id.equals("new")) {
                    id = "remote-" + seq.incrementAndGet();
                } else if (!chats.containsKey(id)) {
                    respond(ex, 401, "{\"detail\":\"not found\"}");
                    return;
                }
                JsonObject stored = new JsonObject();
                stored.addProperty("id", id);
                stored.addProperty("title", form.getAsJsonObject("chat").get("title").getAsString());
                stored.add("chat", form.getAsJsonObject("chat"));
                chats.put(id, stored);
                respond(ex, 200, stored.toString());
            } else if (method.equals("GET")) {
                JsonObject c = chats.get(id);
                respond(ex, c == null ? 401 : 200, c == null ? "{\"detail\":\"not found\"}" : c.toString());
            } else if (method.equals("DELETE")) {
                respond(ex, 200, String.valueOf(chats.remove(id) != null));
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        Path tmp = Files.createTempDirectory("owui-hist");
        try {
            OpenWebUIClient client = new OpenWebUIClient(new ClientSettings(base, "k", "m1", null, Duration.ofSeconds(5), null));
            ChatHistoryApi api = new ChatHistoryApi(client);

            // --- создание
            List<ChatMessage> msgs = List.of(ChatMessage.system("СХЕМА: orders(id)"), ChatMessage.user("вопрос 1"),
                ChatMessage.assistant("ответ 1"));
            String rid = api.save(null, "local-1", "Мой чат", "m1", msgs);
            check("create returns id", rid.equals("remote-1"));
            JsonObject chat = chats.get(rid).getAsJsonObject("chat");
            check("title stored", chat.get("title").getAsString().equals("Мой чат"));
            check("system in params", chat.getAsJsonObject("params").get("system").getAsString().contains("orders"));
            check("history has 2 visible msgs", chat.getAsJsonObject("history").getAsJsonObject("messages").size() == 2);
            check("currentId set", !chat.getAsJsonObject("history").get("currentId").isJsonNull());
            check("list has 2 msgs", chat.getAsJsonArray("messages").size() == 2);

            // --- обновление тем же localId сохраняет id сообщений
            String firstId = chat.getAsJsonArray("messages").get(0).getAsJsonObject().get("id").getAsString();
            List<ChatMessage> more = List.of(msgs.get(0), msgs.get(1), msgs.get(2), ChatMessage.user("вопрос 2"),
                ChatMessage.assistant("ответ 2"));
            String rid2 = api.save(rid, "local-1", "Мой чат", "m1", more);
            JsonObject chat2 = chats.get(rid2).getAsJsonObject("chat");
            check("update keeps id", rid2.equals(rid));
            check("stable message ids", chat2.getAsJsonArray("messages").get(0).getAsJsonObject().get("id").getAsString().equals(firstId));

            // --- чтение ветки
            ChatHistoryApi.RemoteChatContent content = api.get(rid);
            check("read system first", content.messages().get(0).role().equals("system"));
            check("read 5 messages", content.messages().size() == 5);
            check("read order", content.messages().get(4).content().equals("ответ 2"));
            check("read model", "m1".equals(content.model()));

            // --- ветвление: currentId указывает на альтернативный ответ — читаем именно его
            JsonObject hist = chat2.getAsJsonObject("history");
            JsonObject msgMap = hist.getAsJsonObject("messages");
            String lastUser = ChatHistoryApi.messageId("local-1", 2);
            JsonObject alt = new JsonObject();
            alt.addProperty("id", "alt");
            alt.addProperty("parentId", lastUser);
            alt.addProperty("role", "assistant");
            alt.addProperty("content", "альтернативный ответ");
            msgMap.add("alt", alt);
            hist.addProperty("currentId", "alt");
            ChatHistoryApi.RemoteChatContent branch = api.get(rid);
            check("branch follows currentId", branch.messages().get(branch.messages().size() - 1).content().equals("альтернативный ответ"));

            // --- чат из веба: исходные id сообщений сохраняются при записи обратно
            ChatHistoryApi.RemoteChatContent web = api.get(rid);
            List<ChatMessage> cont = new java.util.ArrayList<>(web.messages());
            List<String> ids = new java.util.ArrayList<>(web.messageIds());
            cont.add(ChatMessage.user("ещё вопрос"));
            ids.add(null);
            api.save(rid, "другая-сессия", "Мой чат", "m1", cont, ids);
            JsonObject chat3 = chats.get(rid).getAsJsonObject("chat");
            check("web ids preserved", chat3.getAsJsonArray("messages").get(0).getAsJsonObject().get("id").getAsString().equals(firstId));
            check("web branch kept", chat3.getAsJsonArray("messages").get(3).getAsJsonObject().get("id").getAsString().equals("alt"));

            // --- список
            List<ChatHistoryApi.RemoteChat> list = api.list(1);
            check("list returns chat", list.size() == 1 && list.get(0).title().equals("Мой чат"));

            // --- удаление
            api.delete(rid);
            check("delete", chats.isEmpty());

            // --- локальное хранилище
            ChatStore store = new ChatStore(tmp.resolve("chats"));
            ChatSession s = ChatSession.create();
            store.save(s);
            check("empty session not saved", store.list().isEmpty());
            s.setSystem("sys");
            s.addUser("полный промпт со схемой", "Объяснение запроса · shop");
            s.addAssistant("ответ");
            s.connection = "shop / public";
            store.save(s);
            List<ChatStore.Summary> sums = store.list();
            check("store list", sums.size() == 1 && sums.get(0).title().equals("Объяснение запроса · shop"));
            ChatSession loaded = store.load(s.id);
            check("store roundtrip", loaded.toRequest().size() == 3 && loaded.entries().get(1).shown().startsWith("Объяснение"));
            check("request uses full content", loaded.toRequest().get(1).content().equals("полный промпт со схемой"));
            loaded.removeTrailingUser();
            check("removeTrailingUser keeps answered", loaded.toRequest().size() == 3);
            s.setSystem("sys2");
            check("setSystem replaces", s.toRequest().get(0).content().equals("sys2") && s.toRequest().size() == 3);
            Files.writeString(tmp.resolve("chats").resolve("broken.json"), "{not json");
            check("broken file skipped", store.list().size() == 1);
            store.delete(s.id);
            check("store delete", store.list().isEmpty());
            try {
                store.load("../etc/passwd");
                check("path traversal rejected", false);
            } catch (java.io.IOException e) {
                check("path traversal rejected", true);
            }
        } finally {
            server.stop(0);
        }
        System.out.println("ALL PASSED: " + passed);
    }

    private static void check(String name, boolean cond) {
        if (!cond) {
            throw new AssertionError("FAILED: " + name);
        }
        passed++;
        System.out.println("ok  " + name);
    }

    private static void respond(HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
