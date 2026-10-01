package dbeaver.openwebui.compat25.model;

import org.jkiss.dbeaver.model.ai.AIMessage;
import org.jkiss.dbeaver.model.ai.AIMessageType;
import org.jkiss.dbeaver.model.ai.engine.*;
import org.jkiss.dbeaver.model.ai.registry.AIFunctionDescriptor;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;

/** Тесты движка для AI API DBeaver 25.2.4 против mock Open WebUI (tests/mock_owui.py). */
public class Harness25Test {
    static int passed, failed;
    static void check(String name, boolean ok, Object info) {
        if (ok) { passed++; System.out.println("PASS " + name); } else { failed++; System.out.println("FAIL " + name + " -> " + info); }
    }
    static final DBRProgressMonitor MON = new DBRProgressMonitor() {
        public boolean isCanceled() { return false; } public void beginTask(String n, int w) {} public void subTask(String n) {} public void done() {} };

    static class Rec implements AIEngineResponseConsumer {
        StringBuilder text = new StringBuilder(); List<AIFunctionCall> calls = new ArrayList<>(); Throwable error; int closed;
        CountDownLatch latch = new CountDownLatch(1);
        public void nextChunk(AIEngineResponseChunk c) { if (c.getFunctionCall() != null) calls.add(c.getFunctionCall()); else text.append(c.getChoices().getFirst()); }
        public void error(Throwable t) { error = t; latch.countDown(); }
        public void close() { closed++; latch.countDown(); }
        Rec await() throws Exception { latch.await(10, TimeUnit.SECONDS); Thread.sleep(150); return this; }
    }
    static OpenWebUIProperties25 props(String url, String token, String model) {
        OpenWebUIProperties25 p = new OpenWebUIProperties25(); p.setBaseUrl(url); p.setToken(token); if (model != null) p.setModel(model); p.setTimeout(5); return p;
    }
    static AIEngineRequest req() {
        return new AIEngineRequest(List.of(new AIMessage(AIMessageType.SYSTEM, "You are SQL expert"), new AIMessage(AIMessageType.USER, "all users"),
            new AIMessage(AIMessageType.WARNING, "local")));
    }
    static String last() throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18080/_last")).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    public static void main(String[] a) throws Exception {
        String base = "http://127.0.0.1:18080";
        check("normalize root", Client25.normalizeBaseUrl("http://h:3000").equals("http://h:3000/api/"), Client25.normalizeBaseUrl("http://h:3000"));
        check("normalize v1", Client25.normalizeBaseUrl("http://h:8000/v1").equals("http://h:8000/v1/"), null);

        try (OpenWebUIEngine25 e = new OpenWebUIEngine25(props(base, "sk-test", null))) {
            List<AIModel> models = e.getModels(MON);
            check("models", models.size() == 2 && Integer.valueOf(8192).equals(models.getFirst().contextWindowSize()), models);
        }
        try (OpenWebUIEngine25 e = new OpenWebUIEngine25(props(base, "bad", null))) { e.getModels(MON); check("401", false, null); }
        catch (Exception ex) { check("401 message", ex.getMessage().contains("401") && ex.getMessage().contains("Not authenticated"), ex.getMessage()); }
        try (OpenWebUIEngine25 e = new OpenWebUIEngine25(props(base + "/ui", "sk-test", null))) { e.getModels(MON); check("html", false, null); }
        catch (Exception ex) { check("html message", ex.getMessage().contains("/api"), ex.getMessage()); }

        try (OpenWebUIEngine25 e = new OpenWebUIEngine25(props(base, "sk-test", "llama3.1:8b"))) {
            AIEngineResponse r = e.requestCompletion(MON, req());
            check("sync text, think stripped", "SELECT 1;".equals(r.getVariants().getFirst()), r);
            check("sync tokens", r.getInputTokensConsumed() == 10 && r.getOutputTokensConsumed() == 3, r.getInputTokensConsumed());
            String l = last();
            check("local message not sent", !l.contains("local") && l.contains("\"role\": \"system\""), l);
        }
        AIFunctionDescriptor fd = new AIFunctionDescriptor("listTableNames", "Returns tables",
            new AIFunctionDescriptor.Parameter[]{ new AIFunctionDescriptor.Parameter("schemaNames", "string", "schemas", null) });
        AIEngineRequest toolReq = req(); toolReq.setFunctions(List.of(fd));
        try (OpenWebUIEngine25 e = new OpenWebUIEngine25(props(base, "sk-test", "tool-model"))) {
            AIEngineResponse r = e.requestCompletion(MON, toolReq);
            check("sync tool call", r.getFunctionCall() != null && "public".equals(r.getFunctionCall().getArguments().get("schemaNames")), r);
            check("tools sent", last().replace(" ", "").contains("\"name\":\"listTableNames\""), last());
        }
        try (OpenWebUIEngine25 e = new OpenWebUIEngine25(props(base, "sk-test", "notemp-model"))) {
            AIEngineResponse r = e.requestCompletion(MON, req());
            check("temperature retry", "SELECT 1;".equals(r.getVariants().getFirst()) && !last().contains("temperature"), r);
        }
        try (OpenWebUIEngine25 e = new OpenWebUIEngine25(props(base, "sk-test", "err-model"))) { e.requestCompletion(MON, req()); check("err", false, null); }
        catch (Exception ex) { check("error detail", ex.getMessage().contains("Model not found"), ex.getMessage()); }

        Rec r = new Rec(); new OpenWebUIEngine25(props(base, "sk-test", "llama3.1:8b")).requestCompletionStream(MON, req(), r); r.await();
        check("stream text", "SELECT * FROM users;".equals(r.text.toString()) && r.closed == 1 && r.error == null, "[" + r.text + "] closed=" + r.closed + " " + r.error);
        r = new Rec(); new OpenWebUIEngine25(props(base, "sk-test", "tool-model")).requestCompletionStream(MON, toolReq, r); r.await();
        check("stream one tool call", r.calls.size() == 1 && "db_listTableNames".equals(r.calls.getFirst().getFunctionName()), r.calls);
        r = new Rec(); new OpenWebUIEngine25(props(base, "sk-test", "err-model")).requestCompletionStream(MON, req(), r); r.await();
        check("stream error, no close", r.error != null && r.closed == 0 && r.error.getMessage().contains("Model not found"), r.error + " closed=" + r.closed);

        OpenWebUIProperties25 p = props(base, "sk-secret", "m"); p.saveSecrets();
        OpenWebUIProperties25 p2 = new OpenWebUIProperties25(); p2.resolveSecrets();
        check("secret roundtrip", "sk-secret".equals(p2.getToken()), p2.getToken());
        String json = new com.google.gson.GsonBuilder().setExclusionStrategies(new com.google.gson.ExclusionStrategy() {
            public boolean shouldSkipField(com.google.gson.FieldAttributes f) { return f.getAnnotation(org.jkiss.dbeaver.model.meta.SecureProperty.class) != null; }
            public boolean shouldSkipClass(Class<?> c) { return false; } }).create().toJson(p);
        check("token not in json, same keys as 26.x", !json.contains("sk-secret") && json.contains("openwebui.base_url") && json.contains("openwebui.model"), json);

        String sys = "Instructions:\n- SQL\nContext:\n- SQL dialect: Oracle\n- DBeaver connection name: secret-host\n"
            + "Database snapshot:\n- Datasource schema list: HR\nCREATE TABLE HR.EMP (ID NUMBER);\n";
        OpenWebUIProperties25 pm = props(base, "sk-test", "llama3.1:8b");
        pm.setMetaHideConnectionInfo(true); pm.setMetaSnapshot(MetadataFilter.Snapshot.NAMES); pm.setMetaAllowTableDdl(false);
        AIFunctionDescriptor ddl = new AIFunctionDescriptor("getTableDetails", "DDL", new AIFunctionDescriptor.Parameter[0]);
        AIEngineRequest mreq = new AIEngineRequest(List.of(new AIMessage(AIMessageType.SYSTEM, sys), new AIMessage(AIMessageType.USER, "q")));
        mreq.setFunctions(List.of(fd, ddl));
        try (OpenWebUIEngine25 e = new OpenWebUIEngine25(pm)) { e.requestCompletion(MON, mreq); }
        String sent = last();
        check("meta 25: filtered", !sent.contains("secret-host") && !sent.contains("CREATE TABLE") && sent.contains("schema list: HR")
            && sent.contains("listTableNames") && !sent.contains("getTableDetails"), sent);

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
