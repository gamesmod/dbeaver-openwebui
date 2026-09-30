package io.dbtools.openwebui.prefs;

import io.dbtools.openwebui.Activator;
import io.dbtools.openwebui.ai.PromptFactory;
import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

/**
 * Значения настроек по умолчанию.
 */
public class PrefsInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IPreferenceStore s = Activator.getDefault().getPreferenceStore();
        s.setDefault(Prefs.BASE_URL, "http://localhost:3000");
        s.setDefault(Prefs.MODEL, "");
        s.setDefault(Prefs.TEMPERATURE, "0.2");
        s.setDefault(Prefs.TIMEOUT_SEC, 120);
        s.setDefault(Prefs.STREAM, true);
        s.setDefault(Prefs.INCLUDE_SCHEMA, true);
        s.setDefault(Prefs.MAX_TABLES, 60);
        s.setDefault(Prefs.MAX_COLUMNS, 40);
        s.setDefault(Prefs.KNOWLEDGE_IDS, "");
        s.setDefault(Prefs.SYSTEM_PROMPT, PromptFactory.DEFAULT_SYSTEM_PROMPT);
        s.setDefault(Prefs.INSERT_MODE, Prefs.INSERT_NEW_LINE_BELOW);
        s.setDefault(Prefs.SYNC_HISTORY, false);
        s.setDefault(Prefs.CHAT_CONNECTION, "");
        s.setDefault(Prefs.CHAT_SCHEMA, "");
    }
}
