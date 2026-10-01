package dbeaver.openwebui.model;

import org.jkiss.dbeaver.model.ai.*;
import org.jkiss.dbeaver.model.ai.engine.*;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;

public class HarnessTest {
    static int passed, failed;
    static void check(String name, boolean ok, Object info) {
        if (ok) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name + " -> " + info); }
    }
    static final DBRProgressMonitor MON = new DBRProgressMonitor() {
        public boolean isCanceled() { return false; } public void beginTask(String n, int w) {} public void subTask(String n) {} public void done() {} };

    static class Rec implements AIEngineResponseConsumer {
        StringBuilder text = new StringBuilder(); List<AIFunctionCall> calls = new ArrayList<>();
        Throwable error; AIUsage usage; boolean completed; int sysLen; CountDownLatch latch = new CountDownLatch(1);
        public void nextChunk(AIEngineResponseChunk c) { if (c.getFunctionCall() != null) calls.add(c.getFunctionCall()); else text.append(c.getChoices().getFirst()); }
        public void error(Throwable t) { error = t; latch.countDown(); }
        public void completeBlock() { completed = true; latch.countDown(); }
        public void usage(AIUsage u) { usage = u; }
        public void systemPromptLength(int l) { sysLen = l; }
        public void warning(String m) {}
        Rec await() throws Exception { if (!latch.await(10, TimeUnit.SECONDS)) throw new RuntimeException("timeout"); Thread.sleep(150); return this; }
    }

    static OpenWebUIProperties props(String url, String token, String model) {
        OpenWebUIProperties p = new OpenWebUIProperties();
        p.setBaseUrl(url); p.setToken(token); if (model != null) p.setModel(model); p.setTimeout(5); return p;
    }
    static AIEngineRequest simpleRequest() {
        return new AIEngineRequest(List.of(new AIMessage(AIMessageType.SYSTEM, "You are SQL expert", null), new AIMessage(AIMessageType.USER, "all users", null)));
    }
    static String last() throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18080/_last")).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    public static void main(String[] a) throws Exception {
        String base = "http://127.0.0.1:18080";
        // URL normalization
        check("normalize root", OpenWebUIClient.normalizeBaseUrl("http://h:3000").equals("http://h:3000/api/"), OpenWebUIClient.normalizeBaseUrl("http://h:3000"));
        check("normalize v1", OpenWebUIClient.normalizeBaseUrl("http://h:8000/v1").equals("http://h:8000/v1/"), null);
        check("normalize full endpoint", OpenWebUIClient.normalizeBaseUrl("https://h/api/chat/completions").equals("https://h/api/"), OpenWebUIClient.normalizeBaseUrl("https://h/api/chat/completions"));
        check("normalize no scheme", OpenWebUIClient.normalizeBaseUrl("h:3000").equals("http://h:3000/api/"), OpenWebUIClient.normalizeBaseUrl("h:3000"));

        // models
        try (OpenWebUIEngine e = new OpenWebUIEngine(props(base, "sk-test", null))) {
            List<AIModel> models = e.getModels(MON);
            check("models count", models.size() == 2, models);
            check("models num_ctx", Integer.valueOf(8192).equals(models.getFirst().contextWindowSize()), models.getFirst());
        }
        try (OpenWebUIEngine e = new OpenWebUIEngine(props(base, "bad", null))) {
            e.getModels(MON); check("bad token", false, "no error");
        } catch (Exception ex) { check("bad token message", ex.getMessage().contains("401") && ex.getMessage().contains("Not authenticated"), ex.getMessage()); }
        try (OpenWebUIEngine e = new OpenWebUIEngine(props(base + "/ui", "sk-test", null))) {
            e.getModels(MON); check("html detect", false, "no error");
        } catch (Exception ex) { check("html detect message", ex.getMessage().contains("/api"), ex.getMessage()); }

        // streaming text with split <think>
        Rec r = new Rec();
        new OpenWebUIEngine(props(base, "sk-test", "llama3.1:8b")).requestCompletionStream(MON, simpleRequest(), r);
        r.await();
        check("stream text", "SELECT * FROM users;".equals(r.text.toString()), "[" + r.text + "] err=" + r.error);
        check("stream completed", r.completed && r.error == null, r.error);
        check("stream usage", r.usage != null && r.usage.totalInputTokens() == 120 && r.usage.cachedTokens() == 100 && r.usage.totalOutputTokens() == 9, r.usage);
        check("system prompt length", r.sysLen == "You are SQL expert".length(), r.sysLen);
        String lastReq = last();
        check("stream_options sent", lastReq.contains("\"include_usage\": true"), lastReq);
        check("temperature sent", lastReq.contains("\"temperature\": 0.0"), lastReq);

        // hideThinking off
        OpenWebUIProperties pt = props(base, "sk-test", "llama3.1:8b"); pt.setHideThinking(false);
        r = new Rec(); new OpenWebUIEngine(pt).requestCompletionStream(MON, simpleRequest(), r); r.await();
        check("think kept when disabled", r.text.toString().startsWith("<think>Let me think</think>"), r.text);

        // streaming tool calls (split args, finish_reason=stop)
        AIFunctionDescriptor fd = new AIFunctionDescriptor() {
            public String getFullId() { return "db_listTableNames"; }
            public String getAiDescription() { return "Returns tables"; }
            public AIFunctionParameter[] getParameters() { return new AIFunctionParameter[]{ new AIFunctionParameter() {
                public String getName() { return "schemaNames"; } public String getType() { return "string"; }
                public String getDescription() { return "schemas"; } public boolean isRequired() { return true; } public String[] getValidValues() { return null; } }}; }
        };
        AIFunctionDescriptor fdNoParams = new AIFunctionDescriptor() {
            public String getFullId() { return "ui_getCurrentScript"; } public String getAiDescription() { return null; }
            public AIFunctionParameter[] getParameters() { return new AIFunctionParameter[0]; } };
        AIEngineRequest toolReq = simpleRequest(); toolReq.setFunctions(List.of(fd, fdNoParams));
        r = new Rec(); new OpenWebUIEngine(props(base, "sk-test", "tool-model")).requestCompletionStream(MON, toolReq, r); r.await();
        check("stream 2 tool calls", r.calls.size() == 2, r.calls + " err=" + r.error);
        if (r.calls.size() == 2) {
            AIFunctionCall c0 = r.calls.get(0);
            check("tool call 0 args", "db_listTableNames".equals(c0.getFunctionName()) && "public".equals(c0.getArguments().get("schemaNames")), c0);
            check("tool call 0 id", "call_1".equals(c0.getMessageMetadata().get("call_id")), c0);
            check("tool call 1", "db_getTableDetails".equals(r.calls.get(1).getFunctionName()), r.calls.get(1));
        }
        check("tool stream completed", r.completed, null);
        lastReq = last();
        check("tools schema", lastReq.contains("\"type\": \"function\"") && lastReq.contains("\"required\": [\"schemaNames\"]") && lastReq.contains("\"name\": \"ui_getCurrentScript\""), lastReq);
        check("empty params has properties", lastReq.replace(" ", "").contains("\"name\":\"ui_getCurrentScript\",\"parameters\":{\"type\":\"object\",\"properties\":{}}"), lastReq);

        // functions disabled
        OpenWebUIProperties pf = props(base, "sk-test", "llama3.1:8b"); pf.setFunctionsEnabled(false);
        r = new Rec(); new OpenWebUIEngine(pf).requestCompletionStream(MON, toolReq, r); r.await();
        check("no tools when disabled", !last().contains("\"tools\""), null);

        // arguments as object, no index
        r = new Rec(); new OpenWebUIEngine(props(base, "sk-test", "ollama-obj-model")).requestCompletionStream(MON, simpleRequest(), r); r.await();
        check("obj args tool call", r.calls.size() == 1 && r.calls.getFirst().getArguments().containsKey("catalogName"), r.calls + " " + r.error);

        // non-streaming
        try (OpenWebUIEngine e = new OpenWebUIEngine(props(base, "sk-test", "llama3.1:8b"))) {
            AIEngineResponse resp = e.requestCompletion(MON, simpleRequest());
            check("sync text", "SELECT 1;".equals(resp.getVariants().getFirst()), resp);
            check("sync no stream flag", last().contains("\"stream\": false") && !last().contains("stream_options"), last());
        }
        try (OpenWebUIEngine e = new OpenWebUIEngine(props(base, "sk-test", "tool-model"))) {
            AIEngineResponse resp = e.requestCompletion(MON, toolReq);
            check("sync tool call", resp.getFunctionCall() != null && "public".equals(resp.getFunctionCall().getArguments().get("schemaNames")), resp);
            check("sync usage", resp.getUsage() != null && resp.getUsage().totalInputTokens() == 50, resp.getUsage());
        }

        // streaming disabled -> emulation
        OpenWebUIProperties ps = props(base, "sk-test", "llama3.1:8b"); ps.setStreaming(false);
        r = new Rec(); new OpenWebUIEngine(ps).requestCompletionStream(MON, simpleRequest(), r); r.await();
        check("emulated stream", "SELECT 1;".equals(r.text.toString()) && r.completed, r.text);

        // temperature rejected -> retry (stream + sync)
        r = new Rec(); new OpenWebUIEngine(props(base, "sk-test", "notemp-model")).requestCompletionStream(MON, simpleRequest(), r); r.await();
        check("stream temp retry", r.error == null && "SELECT * FROM users;".equals(r.text.toString()), "[" + r.text + "] " + r.error);
        check("retry without temperature", !last().contains("temperature"), last());
        try (OpenWebUIEngine e = new OpenWebUIEngine(props(base, "sk-test", "notemp-model"))) {
            AIEngineResponse resp = e.requestCompletion(MON, simpleRequest());
            check("sync temp remembered", "SELECT 1;".equals(resp.getVariants().getFirst()), resp);
        }

        // server error
        r = new Rec(); new OpenWebUIEngine(props(base, "sk-test", "err-model")).requestCompletionStream(MON, simpleRequest(), r); r.await();
        check("stream error detail", r.error != null && r.error.getMessage().contains("Model not found") && !r.completed, r.error);

        // no model
        try { new OpenWebUIEngine(props(base, "sk-test", null)).requestCompletion(MON, simpleRequest()); check("no model", false, null); }
        catch (Exception ex) { check("no model message", ex.getMessage().contains("model"), ex.getMessage()); }

        // history conversion: function call + result, call from other engine without id, local messages skipped
        AIFunctionCall fc = new AIFunctionCall("db_listTableNames", Map.of("schemaNames", "public"), Map.of("call_id", "call_1"));
        AIFunctionCall fcNoId = new AIFunctionCall("db_listSchemaNames", Map.of(), null);
        List<AIMessage> hist = List.of(
            new AIMessage(AIMessageType.SYSTEM, "sys", null),
            new AIMessage(AIMessageType.USER, "q", null),
            new AIMessage(AIMessageType.FUNCTION, "users, orders", fc),
            new AIMessage(AIMessageType.FUNCTION, "public", fcNoId),
            new AIMessage(AIMessageType.WARNING, "local warning", null),
            new AIMessage(AIMessageType.ASSISTANT, "done", null));
        List<ChatDto.ChatMessage> cm = ChatMessageConverter.toChatMessages(hist);
        check("history size", cm.size() == 7, cm.size());
        check("history pair", "assistant".equals(cm.get(2).role) && "call_1".equals(cm.get(2).toolCalls.getFirst().id)
            && "tool".equals(cm.get(3).role) && "call_1".equals(cm.get(3).toolCallId), JsonSupport.GSON.toJson(cm));
        check("synthetic id matches", cm.get(4).toolCalls.getFirst().id.equals(cm.get(5).toolCallId) && cm.get(5).toolCallId.startsWith("call_"), JsonSupport.GSON.toJson(cm.subList(4, 6)));
        check("args json string", "{\"schemaNames\":\"public\"}".equals(cm.get(2).toolCalls.getFirst().function.arguments.getAsString()), cm.get(2).toolCalls.getFirst().function.arguments);

        // think filter edge cases
        ThinkTagFilter f = new ThinkTagFilter();
        String out = f.accept("a < b and x <") + f.accept("= 5") + f.flush();
        check("filter keeps lt", "a < b and x <= 5".equals(out), out);
        check("filter unclosed", ThinkTagFilter.strip("<think>never ends").isEmpty(), ThinkTagFilter.strip("<think>never ends"));
        check("filter thinking tag", "ok".equals(ThinkTagFilter.strip("<thinking>x</thinking> ok")), ThinkTagFilter.strip("<thinking>x</thinking> ok"));
        check("filter mid-text", "before after".equals(ThinkTagFilter.strip("before <think>x</think>after")), ThinkTagFilter.strip("before <think>x</think>after"));

        // extra headers
        OpenWebUIProperties ph = new OpenWebUIProperties();
        ph.setExtraHeaders("X-Org: acme\nHost: evil\nbad line\nCF-Access-Client-Id : id1");
        check("extra headers", ph.getExtraHeadersMap().equals(Map.of("X-Org", "acme", "CF-Access-Client-Id", "id1")), ph.getExtraHeadersMap());

        // secrets
        AIConfigurationProfile prof = new AIConfigurationProfile();
        OpenWebUIProperties p1 = props(base, "sk-secret", "m"); p1.saveSecrets(prof);
        OpenWebUIProperties p2 = new OpenWebUIProperties(); p2.resolveSecrets(prof);
        check("secret roundtrip", "sk-secret".equals(p2.getToken()), p2.getToken());
        String json = new com.google.gson.GsonBuilder().setExclusionStrategies(new com.google.gson.ExclusionStrategy() {
            public boolean shouldSkipField(com.google.gson.FieldAttributes fa) { return fa.getAnnotation(org.jkiss.dbeaver.model.meta.SecureProperty.class) != null; }
            public boolean shouldSkipClass(Class<?> c) { return false; } }).create().toJson(p1);
        check("token not in profile json", !json.contains("sk-secret") && json.contains("openwebui.base_url"), json);

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
