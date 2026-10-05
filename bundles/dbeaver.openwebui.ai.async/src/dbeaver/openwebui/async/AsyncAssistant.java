/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.ai.AIChatConversation;
import org.jkiss.dbeaver.model.ai.AIChatRequest;
import org.jkiss.dbeaver.model.ai.AIChatResponseConsumer;
import org.jkiss.dbeaver.model.ai.AIChatSession;
import org.jkiss.dbeaver.model.ai.AIConfigurationProfile;
import org.jkiss.dbeaver.model.ai.impl.AIAssistantImpl;
import org.jkiss.dbeaver.model.ai.qm.AIChatStorage;
import org.jkiss.dbeaver.model.ai.registry.AISettingsManager;
import org.jkiss.dbeaver.model.app.DBPWorkspace;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.util.concurrent.CompletableFuture;

/**
 * AI assistant of DBeaver with three changes (registered with a higher priority than the built-in one):
 * <ul>
 *   <li>chats are stored in workspace files instead of memory, so they survive restarts;</li>
 *   <li>the Open WebUI engine learns which conversation a request belongs to ({@link RequestContext});</li>
 *   <li>in the background mode DBeaver functions are not offered for Open WebUI profiles — the server
 *       can't call DBeaver, so the database structure is put into the prompt instead.</li>
 * </ul>
 * Everything else is the standard assistant of DBeaver.
 */
public class AsyncAssistant extends AIAssistantImpl {

    private static final Log log = Log.getLog(AsyncAssistant.class);
    /** Chats of all DBeaver runs are listed, not only of the current one. */
    private static final String PERSISTENT_SESSION_ID = "dbeaver-openwebui-local";

    public AsyncAssistant(@NotNull DBPWorkspace workspace) {
        super(workspace);
    }

    @NotNull
    @Override
    public AIChatStorage createChatStorage() {
        if (AsyncPlugin.getSettings().persistChats()) {
            try {
                return AsyncPlugin.getChatStorage();
            } catch (Exception e) {
                log.error("Can't open the AI chat storage, chats will be kept in memory", e);
            }
        }
        return super.createChatStorage();
    }

    @NotNull
    @Override
    public AIChatSession.SessionIdProvider getChatSessionProvider() {
        if (AsyncPlugin.getSettings().persistChats()) {
            return monitor -> PERSISTENT_SESSION_ID;
        }
        return super.getChatSessionProvider();
    }

    @Override
    public boolean isFunctionSupported(@NotNull AIConfigurationProfile profile) {
        if (AsyncPlugin.isOpenWebUIEngine(profile.getEngineId()) && AsyncPlugin.getSettings().background()) {
            return false;
        }
        return super.isFunctionSupported(profile);
    }

    @NotNull
    @Override
    public CompletableFuture<AIChatConversation> generateTextStream(
        @NotNull DBRProgressMonitor monitor,
        @NotNull AIChatSession chatSession,
        @NotNull AIChatConversation conversation,
        @NotNull AIChatRequest request,
        @NotNull AIChatResponseConsumer chatListener
    ) throws DBException {
        AIConfigurationProfile profile = conversation.getProfile();
        if (profile == null) {
            profile = AISettingsManager.getInstance().getSettings().getDefaultConfigurationOrNull();
        }
        RequestContext previous = RequestContext.current();
        RequestContext.set(new RequestContext(chatSession, conversation, profile));
        try {
            return super.generateTextStream(monitor, chatSession, conversation, request, chatListener);
        } finally {
            RequestContext.set(previous);
        }
    }
}
