package io.dbtools.openwebui.prefs;

import io.dbtools.openwebui.Activator;
import io.dbtools.openwebui.api.ClientSettings;
import io.dbtools.openwebui.api.OpenWebUIClient;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.preference.IPersistentPreferenceStore;
import org.eclipse.jface.preference.IPreferenceStore;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Ключи и доступ к настройкам плагина.
 * API-ключ хранится в защищённом хранилище Eclipse (Secure Storage), остальное — в обычных preferences.
 */
public final class Prefs {

    public static final String BASE_URL = "baseUrl";
    public static final String MODEL = "model";
    public static final String TEMPERATURE = "temperature";
    public static final String TIMEOUT_SEC = "timeoutSec";
    public static final String STREAM = "stream";
    public static final String INCLUDE_SCHEMA = "includeSchema";
    public static final String MAX_TABLES = "maxTables";
    public static final String MAX_COLUMNS = "maxColumns";
    public static final String KNOWLEDGE_IDS = "knowledgeIds";
    public static final String SYSTEM_PROMPT = "systemPrompt";
    public static final String INSERT_MODE = "insertMode";
    /** Сохранять историю чатов также на сервер Open WebUI (локально — всегда). */
    public static final String SYNC_HISTORY = "syncHistory";
    /** Последнее выбранное в панели чата подключение (id) и схема; пусто — «Авто». */
    public static final String CHAT_CONNECTION = "chatConnection";
    public static final String CHAT_SCHEMA = "chatSchema";

    /** Режимы вставки сгенерированного SQL. */
    public static final String INSERT_AT_CURSOR = "cursor";
    public static final String INSERT_REPLACE_SELECTION = "replace";
    public static final String INSERT_NEW_LINE_BELOW = "below";

    private static final String SECURE_NODE = Activator.PLUGIN_ID;
    private static final String SECURE_API_KEY = "apiKey";

    private Prefs() {
    }

    public static IPreferenceStore store() {
        return Activator.getDefault().getPreferenceStore();
    }

    // ---------------------------------------------------------------- API key

    /** Переменная окружения — запасной источник ключа (CI, Linux без хранилища ключей). */
    public static final String ENV_API_KEY = "OPENWEBUI_API_KEY";

    /** Ключ для запросов: из защищённого хранилища, иначе из переменной окружения OPENWEBUI_API_KEY. */
    public static String getApiKey() {
        String key = getStoredApiKey();
        if (key.isBlank()) {
            String env = System.getenv(ENV_API_KEY);
            return env == null ? "" : env.trim();
        }
        return key;
    }

    /** Ключ, сохранённый пользователем в защищённом хранилище (без учёта переменной окружения). */
    public static String getStoredApiKey() {
        try {
            String key = secureNode().get(SECURE_API_KEY, "");
            return key == null ? "" : key;
        } catch (StorageException e) {
            Activator.logError("Не удалось прочитать API-ключ из защищённого хранилища", e);
            return "";
        }
    }

    public static void setApiKey(String key) {
        try {
            ISecurePreferences node = secureNode();
            if (key == null || key.isBlank()) {
                node.remove(SECURE_API_KEY);
            } else {
                node.put(SECURE_API_KEY, key.trim(), true);
            }
            node.flush();
        } catch (Exception e) {
            Activator.logError("Не удалось сохранить API-ключ в защищённое хранилище", e);
        }
    }

    private static ISecurePreferences secureNode() {
        return SecurePreferencesFactory.getDefault().node(SECURE_NODE);
    }

    // ---------------------------------------------------------------- settings snapshot

    public static ClientSettings clientSettings() {
        return clientSettings(store().getString(BASE_URL), getApiKey(), store().getString(MODEL));
    }

    /** Снимок настроек с явно заданными адресом/ключом/моделью (используется на странице настроек до сохранения). */
    public static ClientSettings clientSettings(String baseUrl, String apiKey, String model) {
        IPreferenceStore s = store();
        Double temperature = null;
        String t = s.getString(TEMPERATURE);
        if (t != null && !t.isBlank()) {
            try {
                temperature = Double.valueOf(t.trim().replace(',', '.'));
            } catch (NumberFormatException ignored) {
                // пусто или мусор — используем значение по умолчанию на сервере
            }
        }
        int timeout = Math.max(5, s.getInt(TIMEOUT_SEC));
        return new ClientSettings(baseUrl, apiKey, model, temperature, Duration.ofSeconds(timeout), knowledgeIds());
    }

    public static OpenWebUIClient client() {
        return new OpenWebUIClient(clientSettings());
    }

    public static List<String> knowledgeIds() {
        String raw = store().getString(KNOWLEDGE_IDS);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,;\\s]+")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    public static boolean isConfigured() {
        return !store().getString(BASE_URL).isBlank() && !store().getString(MODEL).isBlank();
    }

    public static boolean useStreaming() {
        return store().getBoolean(STREAM);
    }

    public static boolean includeSchema() {
        return store().getBoolean(INCLUDE_SCHEMA);
    }

    public static int maxTables() {
        return Math.max(1, store().getInt(MAX_TABLES));
    }

    public static int maxColumns() {
        return Math.max(1, store().getInt(MAX_COLUMNS));
    }

    public static String systemPrompt() {
        return store().getString(SYSTEM_PROMPT);
    }

    /** Сразу записывает настройки на диск (иначе они сохранятся только при штатном закрытии DBeaver). */
    public static void flush() {
        if (store() instanceof IPersistentPreferenceStore p && p.needsSaving()) {
            try {
                p.save();
            } catch (Exception e) {
                Activator.logError("Не удалось сохранить настройки Open WebUI", e);
            }
        }
    }

    public static boolean syncHistory() {
        return store().getBoolean(SYNC_HISTORY);
    }

    public static String insertMode() {
        return store().getString(INSERT_MODE);
    }
}
