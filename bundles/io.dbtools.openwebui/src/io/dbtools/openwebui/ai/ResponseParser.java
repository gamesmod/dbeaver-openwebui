package io.dbtools.openwebui.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Извлечение SQL из ответа модели.
 */
public final class ResponseParser {

    private static final Pattern FENCE = Pattern.compile("```([a-zA-Z0-9_+-]*)[ \\t]*\\r?\\n(.*?)```", Pattern.DOTALL);

    /** Модели-«рассуждатели» иногда возвращают блок размышлений — его в редактор не вставляем. */
    private static final Pattern THINK = Pattern.compile("<think>.*?</think>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private ResponseParser() {
    }

    /** Убирает блоки &lt;think&gt;…&lt;/think&gt;. */
    public static String stripReasoning(String text) {
        return text == null ? "" : THINK.matcher(text).replaceAll("").strip();
    }

    /** Все блоки кода с пометкой sql (или без языка). */
    public static List<String> extractSqlBlocks(String text) {
        List<String> result = new ArrayList<>();
        Matcher m = FENCE.matcher(stripReasoning(text));
        while (m.find()) {
            String lang = m.group(1).toLowerCase();
            if (lang.isEmpty() || lang.equals("sql") || lang.endsWith("sql") || lang.equals("plsql")) {
                String body = m.group(2).strip();
                if (!body.isEmpty()) {
                    result.add(body);
                }
            }
        }
        return result;
    }

    /**
     * SQL для вставки в редактор: первый sql-блок; если блоков нет — весь ответ, но только если он похож на SQL.
     *
     * @return SQL или null, если в ответе нет запроса
     */
    public static String extractSql(String text) {
        List<String> blocks = extractSqlBlocks(text);
        if (!blocks.isEmpty()) {
            return blocks.get(0);
        }
        String plain = stripReasoning(text);
        return looksLikeSql(plain) ? plain : null;
    }

    static boolean looksLikeSql(String text) {
        String t = text.stripLeading().toUpperCase();
        return t.startsWith("SELECT") || t.startsWith("WITH") || t.startsWith("INSERT") || t.startsWith("UPDATE")
            || t.startsWith("DELETE") || t.startsWith("CREATE") || t.startsWith("ALTER") || t.startsWith("MERGE")
            || t.startsWith("--");
    }
}
