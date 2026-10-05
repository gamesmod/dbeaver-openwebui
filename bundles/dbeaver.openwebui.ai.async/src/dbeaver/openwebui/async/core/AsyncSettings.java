/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async.core;

/**
 * Settings of the add-on (Window → Preferences → AI → Open WebUI: background chats).
 *
 * @param persistChats keep DBeaver AI chats between restarts (local files in the workspace)
 * @param mirrorChats  keep a copy of every conversation with an Open WebUI engine as a chat in Open WebUI
 * @param background   run requests as Open WebUI server tasks: generation continues without DBeaver
 * @param detach       do not wait for the answer: free the chat at once, the answer replaces a placeholder later
 * @param pollSeconds  how often DBeaver asks the server for the answer
 */
public record AsyncSettings(boolean persistChats, boolean mirrorChats, boolean background, boolean detach, int pollSeconds) {

    public static final AsyncSettings DEFAULTS = new AsyncSettings(true, true, true, false, 2);

    public static final String KEY_PERSIST = "persistChats";
    public static final String KEY_MIRROR = "mirrorChats";
    public static final String KEY_BACKGROUND = "background";
    public static final String KEY_DETACH = "detach";
    public static final String KEY_POLL = "pollSeconds";

    public AsyncSettings {
        pollSeconds = Math.max(1, Math.min(60, pollSeconds));
    }

    public long pollMillis() {
        return pollSeconds * 1000L;
    }
}
