/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async;

import dbeaver.openwebui.async.core.AsyncSettings;
import dbeaver.openwebui.async.core.ChatsApi;
import dbeaver.openwebui.async.core.JobStore;
import dbeaver.openwebui.async.core.TurnMapper;
import dbeaver.openwebui.model.ChatDto;
import dbeaver.openwebui.model.OpenWebUIClient;
import dbeaver.openwebui.model.OpenWebUIEngine;
import dbeaver.openwebui.model.OpenWebUIProperties;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.ai.AIChatMessage;
import org.jkiss.dbeaver.model.ai.AIMessage;
import org.jkiss.dbeaver.model.ai.AIMessageType;
import org.jkiss.dbeaver.model.ai.engine.AIEngineRequest;
import org.jkiss.dbeaver.model.ai.engine.AIEngineResponseConsumer;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The Open WebUI engine with background requests and chats mirrored to Open WebUI.
 * Registered with {@code replaces="openwebui"}: existing profiles of the base engine use it while the
 * add-on is installed; the base engine declares {@code fallbacks="openwebui-async"} for the way back.
 */
public class AsyncOpenWebUIEngine extends OpenWebUIEngine {

    private static final Log log = Log.getLog(AsyncOpenWebUIEngine.class);

    public AsyncOpenWebUIEngine(@NotNull OpenWebUIProperties properties) {
        super(properties);
    }

    @Override
    public void requestCompletionStream(
        @NotNull DBRProgressMonitor monitor,
        @NotNull AIEngineRequest request,
        @NotNull AIEngineResponseConsumer listener
    ) throws DBException {
        AsyncSettings settings = AsyncPlugin.getSettings();
        JobStore store = AsyncPlugin.getJobStore();
        AIEngineRequest fixed = resolvePlaceholders(request, store);
        RequestContext ctx = RequestContext.current();
        String apiBase = OpenWebUIClient.normalizeBaseUrl(properties.getBaseUrl());
        boolean chatsApi = ChatsApi.supportsChats(apiBase);

        if (ctx != null && chatsApi && settings.background()) {
            if (BackgroundRunner.start(this, ctx, fixed, listener, settings, apiBase)) {
                return;
            }
            // not possible (old server, unexpected history): the usual request below
        } else if (ctx != null && settings.background() && !chatsApi) {
            log.debug("Open WebUI background requests need a base URL ending with /api: " + apiBase);
        }
        AIEngineResponseConsumer target = listener;
        if (ctx != null && chatsApi && settings.mirrorChats()) {
            target = new MirroringConsumer(listener, ctx, this, apiBase);
        }
        super.requestCompletionStream(monitor, fixed, target);
    }

    /** Request JSON as the base engine builds it (model, messages, temperature), without tools. */
    @NotNull
    ChatDto.ChatRequest buildChatRequest(@NotNull AIEngineRequest request) throws DBException {
        ChatDto.ChatRequest r = createChatRequest(request);
        r.tools = null;
        return r;
    }

    @NotNull
    OpenWebUIProperties props() {
        return properties;
    }

    @NotNull
    static ChatsApi createApi(@NotNull OpenWebUIProperties p, @NotNull String apiBase) {
        return new ChatsApi(apiBase, p.getToken(), p.getExtraHeadersMap(), Duration.ofSeconds(Math.max(p.getTimeout(), 30)));
    }

    /**
     * Placeholders of answers are replaced in the history sent to the model: by the answer when it is
     * known, otherwise the placeholder is dropped (the model must not see "the answer is being prepared").
     */
    @NotNull
    static AIEngineRequest resolvePlaceholders(@NotNull AIEngineRequest request, @NotNull JobStore store) {
        List<AIMessage> messages = request.getMessages();
        List<AIMessage> fixed = null;
        for (int i = 0; i < messages.size(); i++) {
            AIMessage m = messages.get(i);
            if (m.getRole() != AIMessageType.ASSISTANT || !store.isPlaceholder(m.getContent())) {
                if (fixed != null) {
                    fixed.add(m);
                }
                continue;
            }
            if (fixed == null) {
                fixed = new ArrayList<>(messages.subList(0, i));
            }
            String text = store.resolveText(m.getContent());
            if (text != null) {
                fixed.add(m.withContent(text));
            }
        }
        if (fixed == null) {
            return request;
        }
        AIEngineRequest r = new AIEngineRequest(fixed);
        r.setFunctions(request.getFunctions());
        r.setWasPromptTruncated(request.wasPromptTruncated());
        return r;
    }

    /** User and assistant messages of the conversation for mirroring. */
    @NotNull
    static List<TurnMapper.Item> historyOf(@NotNull List<AIChatMessage> messages, @Nullable String extraAssistant) {
        List<TurnMapper.Item> items = new ArrayList<>();
        for (AIChatMessage cm : messages) {
            AIMessage m = cm.message();
            if (m.getRole() != AIMessageType.USER && m.getRole() != AIMessageType.ASSISTANT) {
                continue;
            }
            long ts = m.getTime().atZone(ZoneId.systemDefault()).toEpochSecond();
            items.add(new TurnMapper.Item(m.getRole() == AIMessageType.USER, m.getContent(), ts));
        }
        if (extraAssistant != null && !extraAssistant.isBlank()) {
            items.add(new TurnMapper.Item(false, extraAssistant, System.currentTimeMillis() / 1000));
        }
        return items;
    }
}
