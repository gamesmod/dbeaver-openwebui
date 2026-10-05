/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async;

import dbeaver.openwebui.async.core.JobStore;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.ai.AIChatConversation;
import org.jkiss.dbeaver.model.ai.AIChatListener;
import org.jkiss.dbeaver.model.ai.AIChatMessage;
import org.jkiss.dbeaver.model.ai.AIChatSession;
import org.jkiss.dbeaver.model.ai.AIMessage;
import org.jkiss.dbeaver.model.ai.AIMessageType;
import org.jkiss.dbeaver.ui.IActionConstants;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.ai.chat.AIChatView;
import org.jkiss.dbeaver.ui.ai.chat.controls.AIChatControl;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Puts a finished background answer into its DBeaver conversation:
 * into the open AI chat (replacing the placeholder in place), or into the stored conversation file.
 */
final class ResultApplier {

    private static final Log log = Log.getLog(ResultApplier.class);
    /** A placeholder not found in a live conversation for this long is treated as removed: the answer is appended. */
    private static final long PLACEHOLDER_GRACE_MS = 30_000L;

    private static final int NOT_FOUND = 0;
    private static final int DELIVERED = 1;
    private static final int RETRY = 2;

    private ResultApplier() {
    }

    static boolean deliver(@NotNull JobStore.Job job) throws DBException {
        boolean error = JobStore.STATE_ERROR.equals(job.state);
        String text = error ? AsyncMessages.error_prefix + " " + job.deliveryText() : job.deliveryText();
        AtomicInteger live = new AtomicInteger(NOT_FOUND);
        UIUtils.syncExec(() -> live.set(deliverLive(job, text, error)));
        if (live.get() == DELIVERED) {
            return true;
        }
        if (live.get() == RETRY) {
            return false;
        }
        if (AsyncPlugin.getSettings().persistChats()) {
            return AsyncPlugin.getChatStorage().deliver(job.conversationId, job.placeholder, text, error);
        }
        return false;
    }

    private static int deliverLive(JobStore.Job job, String text, boolean error) {
        UUID conversationId;
        try {
            conversationId = UUID.fromString(job.conversationId);
        } catch (IllegalArgumentException e) {
            return NOT_FOUND;
        }
        int result = NOT_FOUND;
        for (AIChatSession session : liveSessions()) {
            AIChatConversation conv;
            try {
                conv = session.getConversation(conversationId);
            } catch (DBException e) {
                continue;
            }
            if (conv == null) {
                continue;
            }
            int r = deliverTo(session, conv, job, text, error);
            if (r == DELIVERED) {
                return DELIVERED;
            }
            if (r == RETRY) {
                result = RETRY;
            }
        }
        return result;
    }

    private static int deliverTo(AIChatSession session, AIChatConversation conv, JobStore.Job job, String text, boolean error) {
        AIMessage answer = error
            ? new AIMessage(AIMessageType.ERROR, text, null)
            : AIMessage.assistantMessage(text, null);
        if (job.placeholder != null) {
            AIChatMessage placeholder = findPlaceholder(conv, job.placeholder);
            if (placeholder == null) {
                // DBeaver adds the placeholder message right after the request returns: give it time
                if (System.currentTimeMillis() - job.created < PLACEHOLDER_GRACE_MS) {
                    return RETRY;
                }
            } else {
                if (replaceInPlace(conv, placeholder, answer)) {
                    if (AsyncPlugin.getSettings().persistChats()) {
                        try {
                            AsyncPlugin.getChatStorage().replaceMessage(conv.getId().toString(), placeholder.id(), text);
                        } catch (DBException e) {
                            log.debug("Can't save the delivered answer", e);
                        }
                    }
                    refreshIfShown(session, conv);
                    return DELIVERED;
                }
                session.notifyMessageRemove(conv, placeholder);
            }
        }
        AIChatMessage added = conv.addMessage(answer);
        session.notifyMessageAdd(conv, added);
        return DELIVERED;
    }

    @Nullable
    private static AIChatMessage findPlaceholder(AIChatConversation conv, String placeholder) {
        List<AIChatMessage> messages = conv.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            AIChatMessage m = messages.get(i);
            if (placeholder.equals(m.message().getContent())) {
                return m;
            }
        }
        return null;
    }

    /**
     * Replaces the placeholder at its position (the user may have asked more since then).
     * The conversation keeps messages in a private list, there is no public API for this.
     */
    @SuppressWarnings("unchecked")
    private static boolean replaceInPlace(AIChatConversation conv, AIChatMessage placeholder, AIMessage answer) {
        try {
            Field f = AIChatConversation.class.getDeclaredField("messages");
            f.setAccessible(true);
            List<AIChatMessage> list = (List<AIChatMessage>) f.get(conv);
            int index = list.indexOf(placeholder);
            if (index < 0) {
                return false;
            }
            list.set(index, new AIChatMessage(placeholder.id(), answer));
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.debug("Can't replace the placeholder in place: " + e.getMessage());
            return false;
        }
    }

    /** Re-renders the conversation in AI chat panels that show it. */
    private static void refreshIfShown(AIChatSession session, AIChatConversation conv) {
        for (AIChatControl chat : chatControls()) {
            if (chat.getChatSession() == session && chat.getActiveConversation() == conv) {
                session.notifyListeners(AIChatListener::conversationChanged, conv);
                return;
            }
        }
    }

    @NotNull
    private static List<AIChatSession> liveSessions() {
        Set<AIChatSession> sessions = new LinkedHashSet<>();
        for (AIChatControl chat : chatControls()) {
            sessions.add(chat.getChatSession());
        }
        sessions.addAll(RequestContext.knownSessions());
        return new ArrayList<>(sessions);
    }

    @NotNull
    private static List<AIChatControl> chatControls() {
        List<AIChatControl> result = new ArrayList<>();
        if (!PlatformUI.isWorkbenchRunning()) {
            return result;
        }
        IWorkbench workbench = PlatformUI.getWorkbench();
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                IViewPart view = page.findView(IActionConstants.CHAT_VIEW_ID);
                if (view instanceof AIChatView chatView && chatView.getChat() != null && !chatView.getChat().isDisposed()) {
                    result.add(chatView.getChat());
                }
            }
        }
        return result;
    }
}
