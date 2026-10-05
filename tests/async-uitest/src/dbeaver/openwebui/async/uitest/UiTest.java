package dbeaver.openwebui.async.uitest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dbeaver.openwebui.async.AsyncPlugin;
import dbeaver.openwebui.async.core.AsyncSettings;
import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.jkiss.dbeaver.model.ai.AIChatConversation;
import org.jkiss.dbeaver.model.ai.AIChatMessage;
import org.jkiss.dbeaver.model.ai.AIMessageType;
import org.jkiss.dbeaver.ui.IWorkbenchWindowInitializer;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.ai.chat.AIChatView;
import org.jkiss.dbeaver.ui.ai.chat.controls.AIChatControl;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * End-to-end check of the add-on inside a real DBeaver (CI only, see scripts/ui-async-test.sh).
 * Phase 1: waiting mode, detached mode, chats on disk and in Open WebUI, then a detached request is left
 * unfinished and DBeaver is killed. Phase 2 (next start): conversations restored, the answer delivered.
 */
public class UiTest implements IWorkbenchWindowInitializer {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean started;

    private final Map<String, Object> results = new LinkedHashMap<>();
    private boolean ok = true;

    @Override
    public void initializeWorkbenchWindow(IWorkbenchWindowConfigurer configurer) {
        String phase = System.getProperty("owui.uitest.phase");
        if (phase == null || started) {
            return;
        }
        started = true;
        Thread t = new Thread(() -> run(phase), "owui-uitest");
        t.setDaemon(true);
        t.start();
    }

    private static void step(String text) {
        System.out.println("OWUI-STEP " + text);
        System.out.flush();
    }

    private void run(String phase) {
        Path out = Path.of(System.getProperty("owui.uitest.out"));
        step("started, phase " + phase);
        try {
            Thread.sleep(15_000);
            step("after delay");
            if ("1".equals(phase)) {
                phase1(out);
            } else {
                phase2(out);
            }
        } catch (Throwable e) {
            check("no exception", false, e.toString());
            e.printStackTrace();
        }
        results.put("ok", ok);
        try {
            Files.writeString(out.resolveSibling("phase" + phase + ".json"), GSON.toJson(results), StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("OWUI-UITEST phase " + phase + " ok=" + ok + " " + results);
        Runtime.getRuntime().halt(0);   // like a crash: nothing is saved on exit
    }

    private void phase1(Path out) throws Exception {
        AIChatControl chat = ui(() -> {
            try {
                return AIChatView.show().getChat();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        step("chat view open: " + chat);
        // 1. waiting mode
        AsyncPlugin.saveSettings(new AsyncSettings(true, true, true, false, 1));
        AIChatConversation c1 = ui(chat::getActiveConversation);
        step("submit 1, conversation " + c1.getId());
        ui(() -> chat.submitPrompt("all users"));
        step("submitted 1");
        boolean answered = waitFor(60, () -> hasAssistant(c1, "Answer 2 for: all users") && !ui(chat::isBusy));
        check("wait mode: answer in chat", answered, dump(c1));
        results.put("c1", c1.getId().toString());

        // 2. detached mode: the chat is free at once, the placeholder is replaced at its place
        AsyncPlugin.saveSettings(new AsyncSettings(true, true, true, true, 1));
        ui(chat::createNewConversation);
        AIChatConversation c2 = ui(chat::getActiveConversation);
        ui(() -> chat.submitPrompt("count orders"));
        boolean placeholder = waitFor(15, () -> lastAssistantStartsWith(c2, "⏳") && !ui(chat::isBusy));
        check("detached: placeholder and free chat", placeholder, dump(c2));
        int placeholderIndex = c2.getMessages().size() - 1;
        boolean replaced = waitFor(60, () -> {
            List<AIChatMessage> m = c2.getMessages();
            return m.size() > placeholderIndex && "Answer 2 for: count orders".equals(m.get(placeholderIndex).message().getContent());
        });
        check("detached: placeholder replaced by the answer", replaced, dump(c2));
        results.put("c2", c2.getId().toString());

        // 3. chats on disk and in Open WebUI
        Path dir = AsyncPlugin.getDataFolder().resolve("chats");
        Path f1 = dir.resolve(c1.getId() + ".json");
        Path f2 = dir.resolve(c2.getId() + ".json");
        boolean saved = waitFor(10, () -> read(f1).contains("Answer 2 for: all users") && read(f2).contains("Answer 2 for: count orders")
            && !read(f2).contains("⏳"));
        check("chats saved to workspace", saved, read(f2));
        String bg = http("http://127.0.0.1:3000/_bg");
        JsonObject chats = JsonParser.parseString(bg).getAsJsonObject().getAsJsonObject("chats");
        check("chats mirrored to Open WebUI", chats.size() >= 2, chats.keySet());

        // 4. a detached request left unfinished: DBeaver is killed now
        ui(chat::createNewConversation);
        AIChatConversation c3 = ui(chat::getActiveConversation);
        ui(() -> chat.submitPrompt("please answer slowly"));
        boolean ph3 = waitFor(15, () -> lastAssistantStartsWith(c3, "⏳"));
        check("unfinished request placeholder", ph3, dump(c3));
        waitFor(5, () -> read(dir.resolve(c3.getId() + ".json")).contains("⏳"));
        results.put("c3", c3.getId().toString());
    }

    private void phase2(Path out) throws Exception {
        JsonObject p1 = JsonParser.parseString(Files.readString(out.resolveSibling("phase1.json"))).getAsJsonObject();
        AIChatControl chat = ui(() -> {
            try {
                return AIChatView.show().getChat();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        AIChatConversation c1 = ui(() -> conversation(chat, p1.get("c1").getAsString()));
        check("conversation restored after restart", c1 != null && hasAssistant(c1, "Answer 2 for: all users"), c1 == null ? "missing" : dump(c1));
        AIChatConversation c3 = ui(() -> conversation(chat, p1.get("c3").getAsString()));
        check("unfinished conversation restored", c3 != null, "missing");
        if (c3 != null) {
            boolean delivered = waitFor(60, () -> hasAssistant(c3, "Answer 2 for: please answer slowly"));
            check("answer delivered after restart", delivered, dump(c3));
            check("placeholder gone", c3.getMessages().stream().noneMatch(m -> m.message().getContent().startsWith("⏳")), dump(c3));
        }
        boolean noJobs = waitFor(10, () -> AsyncPlugin.getJobStore().jobs().isEmpty());
        check("no pending jobs left", noJobs, AsyncPlugin.getJobStore().jobs().size());
    }

    // ------------------------------------------------------------------ helpers

    private static AIChatConversation conversation(AIChatControl chat, String id) {
        try {
            return chat.getChatSession().getConversation(UUID.fromString(id));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasAssistant(AIChatConversation c, String text) {
        return c.getMessages().stream().anyMatch(m -> m.message().getRole() == AIMessageType.ASSISTANT && text.equals(m.message().getContent()));
    }

    private static boolean lastAssistantStartsWith(AIChatConversation c, String prefix) {
        List<AIChatMessage> m = c.getMessages();
        return !m.isEmpty() && m.getLast().message().getRole() == AIMessageType.ASSISTANT && m.getLast().message().getContent().startsWith(prefix);
    }

    private static String dump(AIChatConversation c) {
        StringBuilder sb = new StringBuilder();
        for (AIChatMessage m : c.getMessages()) {
            sb.append(m.id()).append(' ').append(m.message().getRole()).append(": ")
                .append(m.message().getContent().replace('\n', ' ')).append(" | ");
        }
        return sb.toString();
    }

    private void check(String name, boolean passed, Object info) {
        step("check " + name);
        results.put(name, passed ? "PASS" : "FAIL: " + info);
        ok &= passed;
        System.out.println((passed ? "PASS " : "FAIL ") + name + (passed ? "" : " -> " + info));
    }

    private static boolean waitFor(int seconds, BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // state not ready yet
            }
            Thread.sleep(300);
        }
        return condition.getAsBoolean();
    }

    private static <T> T ui(Supplier<T> s) {
        AtomicReference<T> r = new AtomicReference<>();
        UIUtils.syncExec(() -> r.set(s.get()));
        return r.get();
    }

    private static void ui(Runnable r) {
        UIUtils.syncExec(r);
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            return "";
        }
    }

    private static String http(String url) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }
}
