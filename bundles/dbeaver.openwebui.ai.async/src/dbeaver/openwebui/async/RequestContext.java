/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.ai.AIChatConversation;
import org.jkiss.dbeaver.model.ai.AIChatSession;
import org.jkiss.dbeaver.model.ai.AIConfigurationProfile;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The chat conversation a request belongs to. DBeaver passes only messages to an engine; the assistant
 * of this add-on puts the conversation here for the duration of the (synchronous) engine call.
 */
public record RequestContext(
    @NotNull AIChatSession session,
    @NotNull AIChatConversation conversation,
    @Nullable AIConfigurationProfile profile
) {
    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();
    private static final List<WeakReference<AIChatSession>> SESSIONS = new ArrayList<>();

    @Nullable
    public static RequestContext current() {
        return CURRENT.get();
    }

    static void set(@Nullable RequestContext ctx) {
        if (ctx == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(ctx);
            remember(ctx.session());
        }
    }

    /** Chat sessions seen in this DBeaver run (AI chat panels), to deliver background answers. */
    static synchronized void remember(@NotNull AIChatSession session) {
        SESSIONS.removeIf(r -> r.get() == null || r.get() == session);
        SESSIONS.add(new WeakReference<>(session));
    }

    @NotNull
    static synchronized List<AIChatSession> knownSessions() {
        List<AIChatSession> result = new ArrayList<>();
        for (WeakReference<AIChatSession> r : SESSIONS) {
            AIChatSession s = r.get();
            if (s != null && !s.isClosed()) {
                result.add(s);
            }
        }
        return result;
    }
}
