package io.dbtools.openwebui.api;

import com.sun.net.httpserver.HttpServer;
import io.dbtools.openwebui.ai.PromptFactory;
import io.dbtools.openwebui.ai.ResponseParser;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Автономный смоук-тест клиента против фейкового Open WebUI (без JUnit и без Eclipse).
 * Запуск: javac -cp gson.jar ... && java -cp gson.jar:out io.dbtools.openwebui.api.OpenWebUIClientSmokeTest
 */
public class OpenWebUIClientSmokeTest {

    private static int passed;

    public static void main(String[] args) throws Exception {
        AtomicReference<String> lastBody = new AtomicReference<>();
        AtomicReference<String> lastAuth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/api/models", ex -> {
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            if (!"Bearer sk-test".equals(lastAuth.get())) {
                respond(ex, 401, "{\"detail\":\"401 Unauthorized\"}", "application/json");
                return;
            }
            respond(ex, 200, "{\"data\":[{\"id\":\"llama3.1:8b\",\"name\":\"Llama 3.1\"},{\"id\":\"qwen2.5-coder\"}]}", "application/json");
        });

        server.createContext("/api/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastBody.set(body);
            if (body.contains("\"model\":\"missing\"")) {
                respond(ex, 404, "{\"detail\":\"Model not found\"}", "application/json");
            } else if (body.contains("\"stream\":true")) {
                String sse = """
                    data: {"choices":[{"delta":{"role":"assistant"}}]}

                    data: {"choices":[{"delta":{"content":"```sql\\nSELECT "}}]}

                    : keep-alive

                    data: {"sources":[{"name":"dict"}]}

                    data: {"choices":[{"delta":{"content":"id FROM orders;\\n```"}}]}

                    data: [DONE]

                    """;
                respond(ex, 200, sse, "text/event-stream");
            } else {
                respond(ex, 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"<think>hmm</think>Ответ:\\n```sql\\nSELECT 1\\n```\"}}]}", "application/json");
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/";

        try {
            ClientSettings ok = new ClientSettings(base, "sk-test", "llama3.1:8b", 0.2, Duration.ofSeconds(5), List.of("kb-1"));
            OpenWebUIClient client = new OpenWebUIClient(ok);

            // 1. нормализация URL
            check("baseUrl normalized", ok.baseUrl().endsWith(String.valueOf(server.getAddress().getPort())));

            // 2. модели
            List<ModelInfo> models = client.listModels();
            check("models count", models.size() == 2);
            check("model label", models.get(0).toString().equals("Llama 3.1 (llama3.1:8b)"));
            check("auth header", "Bearer sk-test".equals(lastAuth.get()));

            // 3. 401
            OpenWebUIClient bad = new OpenWebUIClient(new ClientSettings(base, "wrong", "x", null, null, null));
            try {
                bad.listModels();
                check("401 raises", false);
            } catch (OpenWebUIException e) {
                check("401 message", e.getStatusCode() == 401 && e.getMessage().contains("API-ключ"));
            }

            // 4. обычный чат + payload
            String answer = client.chat(List.of(ChatMessage.system("s"), ChatMessage.user("u")), null);
            check("non-stream answer", answer.contains("SELECT 1"));
            check("payload model", lastBody.get().contains("\"model\":\"llama3.1:8b\""));
            check("payload temperature", lastBody.get().contains("\"temperature\":0.2"));
            check("payload knowledge", lastBody.get().contains("\"files\":[{\"type\":\"collection\",\"id\":\"kb-1\"}]"));
            check("think stripped", "SELECT 1".equals(ResponseParser.extractSql(answer)));

            // 5. потоковый чат
            List<String> deltas = new ArrayList<>();
            String streamed = client.chatStream(List.of(ChatMessage.user("u")), "qwen2.5-coder", deltas::add);
            check("stream deltas", deltas.size() == 2);
            check("stream full", streamed.equals("```sql\nSELECT id FROM orders;\n```"));
            check("stream model override", lastBody.get().contains("\"model\":\"qwen2.5-coder\""));
            check("stream sql", "SELECT id FROM orders;".equals(ResponseParser.extractSql(streamed)));

            // 6. 404 в потоке
            try {
                client.chatStream(List.of(ChatMessage.user("u")), "missing", d -> { });
                check("404 raises", false);
            } catch (OpenWebUIException e) {
                check("404 message", e.getStatusCode() == 404 && e.getMessage().contains("Model not found"));
            }

            // 7. нет сервера
            OpenWebUIClient down = new OpenWebUIClient(new ClientSettings("http://127.0.0.1:1", "k", "m", null, Duration.ofSeconds(2), null));
            try {
                down.listModels();
                check("down raises", false);
            } catch (OpenWebUIException e) {
                check("down message", e.getMessage().startsWith("Нет связи с Open WebUI"));
            }

            // 8. промпты
            var db = new PromptFactory.DbContext("PostgreSQL 16", "PostgreSQL", "public",
                "orders(id bigint PK, customer_id bigint FK→customers.id)");
            var gen = PromptFactory.generateSql(null, db, "заказы за вчера", null);
            check("prompt system default", gen.get(0).content().contains("SQL"));
            check("prompt schema", gen.get(1).content().contains("orders(id bigint PK"));
            var opt = PromptFactory.queryAction("custom", db, PromptFactory.QueryAction.OPTIMIZE, "select * from orders");
            check("prompt custom system", opt.get(0).content().equals("custom"));
            check("prompt optimize", opt.get(1).content().contains("CREATE INDEX"));

            // 9. парсер
            check("parser no sql", ResponseParser.extractSql("Недостаточно данных") == null);
            check("parser plain sql", "SELECT 2".equals(ResponseParser.extractSql("SELECT 2")));
            check("parser first block", "select 3".equals(ResponseParser.extractSql("x\n```python\nprint()\n```\n```sql\nselect 3\n```")));
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

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body, String type) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
