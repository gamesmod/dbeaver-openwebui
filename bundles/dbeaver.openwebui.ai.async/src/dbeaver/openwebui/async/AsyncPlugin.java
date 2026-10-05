/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async;

import dbeaver.openwebui.async.core.AsyncSettings;
import dbeaver.openwebui.async.core.FileChatStorage;
import dbeaver.openwebui.async.core.JobStore;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.impl.preferences.BundlePreferenceStore;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Shared state of the add-on: settings, the job store and the local chat storage.
 * Files live in {@code <workspace>/.metadata/dbeaver-openwebui/}.
 */
public final class AsyncPlugin {

    public static final String BUNDLE_ID = "dbeaver.openwebui.ai.async";
    /** Engine ids of the base plugin and of this add-on. */
    public static final String ENGINE_ID = "openwebui";
    public static final String ASYNC_ENGINE_ID = "openwebui-async";

    private static final Log log = Log.getLog(AsyncPlugin.class);

    private static DBPPreferenceStore preferences;
    private static JobStore jobStore;
    private static FileChatStorage chatStorage;

    private AsyncPlugin() {
    }

    @NotNull
    public static synchronized DBPPreferenceStore getPreferences() {
        if (preferences == null) {
            preferences = new BundlePreferenceStore(BUNDLE_ID);
            AsyncSettings d = AsyncSettings.DEFAULTS;
            preferences.setDefault(AsyncSettings.KEY_PERSIST, d.persistChats());
            preferences.setDefault(AsyncSettings.KEY_MIRROR, d.mirrorChats());
            preferences.setDefault(AsyncSettings.KEY_BACKGROUND, d.background());
            preferences.setDefault(AsyncSettings.KEY_DETACH, d.detach());
            preferences.setDefault(AsyncSettings.KEY_POLL, d.pollSeconds());
        }
        return preferences;
    }

    @NotNull
    public static AsyncSettings getSettings() {
        DBPPreferenceStore p = getPreferences();
        return new AsyncSettings(
            p.getBoolean(AsyncSettings.KEY_PERSIST),
            p.getBoolean(AsyncSettings.KEY_MIRROR),
            p.getBoolean(AsyncSettings.KEY_BACKGROUND),
            p.getBoolean(AsyncSettings.KEY_DETACH),
            p.getInt(AsyncSettings.KEY_POLL));
    }

    public static void saveSettings(@NotNull AsyncSettings s) {
        DBPPreferenceStore p = getPreferences();
        p.setValue(AsyncSettings.KEY_PERSIST, s.persistChats());
        p.setValue(AsyncSettings.KEY_MIRROR, s.mirrorChats());
        p.setValue(AsyncSettings.KEY_BACKGROUND, s.background());
        p.setValue(AsyncSettings.KEY_DETACH, s.detach());
        p.setValue(AsyncSettings.KEY_POLL, s.pollSeconds());
        try {
            p.save();
        } catch (IOException e) {
            log.error("Can't save Open WebUI background chat settings", e);
        }
    }

    @NotNull
    public static Path getDataFolder() {
        return DBWorkbench.getPlatform().getWorkspace().getMetadataFolder().resolve("dbeaver-openwebui");
    }

    @NotNull
    public static synchronized JobStore getJobStore() {
        if (jobStore == null) {
            jobStore = new JobStore(getDataFolder().resolve("background-chats.json"));
        }
        return jobStore;
    }

    @NotNull
    public static synchronized FileChatStorage getChatStorage() {
        if (chatStorage == null) {
            chatStorage = new FileChatStorage(getDataFolder().resolve("chats"));
            JobStore store = getJobStore();
            // Copies saved before an answer was delivered get the answer on load and save
            chatStorage.setContentFixer(text -> {
                if (!store.isPlaceholder(text)) {
                    return text;
                }
                String resolved = store.resolveText(text);
                return resolved == null ? text : resolved;
            });
        }
        return chatStorage;
    }

    public static boolean isOpenWebUIEngine(String engineId) {
        return ENGINE_ID.equals(engineId) || ASYNC_ENGINE_ID.equals(engineId);
    }
}
