/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.prompts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import java.util.ArrayList;
import java.util.List;

/**
 * User's saved prompts. Stored in the workspace configuration folder next to DBeaver's
 * ai-configuration.json (file {@value #FILE_NAME}), so they move together with the workspace.
 */
public final class PromptLibrary {

    private static final Log log = Log.getLog(PromptLibrary.class);
    public static final String FILE_NAME = "openwebui-prompts.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Saved prompt. Text may contain ${selection} — text selected in the active SQL editor. */
    public static final class SavedPrompt {
        public String name;
        public String text;
        /** Send to the chat immediately instead of inserting into the prompt field. */
        public boolean send;

        public SavedPrompt() {
        }

        public SavedPrompt(@NotNull String name, @NotNull String text, boolean send) {
            this.name = name;
            this.text = text;
            this.send = send;
        }

        @NotNull
        public SavedPrompt copy() {
            return new SavedPrompt(name, text, send);
        }
    }

    private static final class Storage {
        int version = 1;
        List<SavedPrompt> prompts = new ArrayList<>();
    }

    private PromptLibrary() {
    }

    @NotNull
    public static synchronized List<SavedPrompt> load() {
        try {
            String content = DBWorkbench.getPlatform().getConfigurationController().loadConfigurationFile(FILE_NAME);
            if (content == null || content.isBlank()) {
                return defaults();
            }
            return fromJson(content);
        } catch (Exception e) {
            log.error("Error loading saved AI prompts", e);
            return new ArrayList<>();
        }
    }

    public static synchronized void save(@NotNull List<SavedPrompt> prompts) throws DBException {
        DBWorkbench.getPlatform().getConfigurationController().saveConfigurationFile(FILE_NAME, toJson(prompts));
    }

    @NotNull
    static List<SavedPrompt> fromJson(@NotNull String content) {
        Storage storage = GSON.fromJson(content, new TypeToken<Storage>() { }.getType());
        List<SavedPrompt> result = new ArrayList<>();
        if (storage != null && storage.prompts != null) {
            for (SavedPrompt p : storage.prompts) {
                if (p != null && p.name != null && !p.name.isBlank() && p.text != null) {
                    result.add(p);
                }
            }
        }
        return result;
    }

    @NotNull
    static String toJson(@NotNull List<SavedPrompt> prompts) {
        Storage storage = new Storage();
        storage.prompts = new ArrayList<>(prompts);
        return GSON.toJson(storage);
    }

    /** Examples shown until the user saves their own list. */
    @NotNull
    static List<SavedPrompt> defaults() {
        List<SavedPrompt> list = new ArrayList<>();
        list.add(new SavedPrompt("Объяснить запрос",
            "Объясни, что делает этот запрос, шаг за шагом:\n\n${selection}", true));
        list.add(new SavedPrompt("Оптимизировать запрос",
            "Найди узкие места в запросе и предложи оптимизированный вариант и индексы:\n\n${selection}", true));
        list.add(new SavedPrompt("Найти ошибки",
            "Проверь запрос на ошибки: синтаксис диалекта, несуществующие колонки, опасные UPDATE/DELETE без WHERE:\n\n${selection}", true));
        return list;
    }

    /** Substitutes ${selection}; unknown variables are left as is. */
    @NotNull
    public static String expand(@NotNull String text, @Nullable String selection) {
        return text.replace("${selection}", selection == null ? "" : selection);
    }
}
