package io.dbtools.openwebui.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Локальная история чатов: один JSON-файл на диалог в области состояния плагина
 * ({@code <workspace>/.metadata/.plugins/io.dbtools.openwebui/chats}).
 */
public final class ChatStore {

    /** Строка списка истории. */
    public record Summary(String id, String title, long updatedAt, String connection, String remoteId) {
    }

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final Path dir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public ChatStore(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    /** Сохраняет диалог (атомарно: запись во временный файл и переименование). Пустые диалоги не пишутся. */
    public synchronized void save(ChatSession session) throws IOException {
        if (session == null || !session.hasUserMessages() || !SAFE_ID.matcher(session.id).matches()) {
            return;
        }
        Files.createDirectories(dir);
        Path target = dir.resolve(session.id + ".json");
        Path tmp = dir.resolve(session.id + ".json.tmp");
        String json;
        synchronized (session) {
            json = gson.toJson(session);
        }
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write(json);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public synchronized ChatSession load(String id) throws IOException {
        if (!SAFE_ID.matcher(id).matches()) {
            throw new IOException("Некорректный id чата");
        }
        try (Reader r = Files.newBufferedReader(dir.resolve(id + ".json"), StandardCharsets.UTF_8)) {
            ChatSession s = gson.fromJson(r, ChatSession.class);
            if (s == null || s.id == null) {
                throw new IOException("Пустой файл чата");
            }
            if (s.messages == null) {
                s.messages = new ArrayList<>();
            }
            return s;
        } catch (JsonParseException e) {
            throw new IOException("Повреждён файл чата " + id + ": " + e.getMessage(), e);
        }
    }

    /** Все диалоги, новые сверху. Повреждённые файлы пропускаются. */
    public synchronized List<Summary> list() {
        List<Summary> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : files) {
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    ChatSession s = gson.fromJson(r, ChatSession.class);
                    if (s != null && s.id != null) {
                        result.add(new Summary(s.id, s.title == null ? "Без названия" : s.title,
                            s.updatedAt, s.connection, s.remoteId));
                    }
                } catch (IOException | JsonParseException ignored) {
                    // пропускаем повреждённый файл
                }
            }
        } catch (IOException ignored) {
            // каталог недоступен — пустой список
        }
        result.sort(Comparator.comparingLong(Summary::updatedAt).reversed());
        return result;
    }

    public synchronized void delete(String id) throws IOException {
        if (SAFE_ID.matcher(id).matches()) {
            Files.deleteIfExists(dir.resolve(id + ".json"));
        }
    }
}
