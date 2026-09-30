package io.dbtools.openwebui.ai;

import io.dbtools.openwebui.api.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Шаблоны промптов для задач плагина. Не зависит от DBeaver: на вход — готовые строки контекста.
 */
public final class PromptFactory {

    /** Тип действия над запросом. */
    public enum QueryAction {
        EXPLAIN("Объяснение запроса"),
        OPTIMIZE("Оптимизация запроса"),
        REVIEW("Проверка запроса на ошибки");

        private final String title;

        QueryAction(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    /** Контекст подключения, который передаётся модели. */
    public record DbContext(String databaseProduct, String dialect, String defaultSchema, String schemaDescription) {

        String render() {
            StringBuilder sb = new StringBuilder();
            if (notBlank(databaseProduct)) {
                sb.append("СУБД: ").append(databaseProduct).append('\n');
            }
            if (notBlank(dialect)) {
                sb.append("SQL-диалект: ").append(dialect).append('\n');
            }
            if (notBlank(defaultSchema)) {
                sb.append("Схема по умолчанию: ").append(defaultSchema).append('\n');
            }
            if (notBlank(schemaDescription)) {
                sb.append("\nСтруктура доступных таблиц (имя(колонка тип, …), PK — первичный ключ, FK — внешний ключ):\n")
                    .append(schemaDescription).append('\n');
            }
            return sb.toString();
        }
    }

    public static final String DEFAULT_SYSTEM_PROMPT = """
        Ты — опытный разработчик баз данных и эксперт по SQL, встроенный в DBeaver.
        Отвечай на русском языке, кратко и по делу.
        Пиши SQL строго для указанной СУБД и диалекта, используй только таблицы и колонки из переданной структуры.
        Если данных для ответа не хватает — прямо скажи, каких именно.
        Никогда не предлагай DROP, TRUNCATE, DELETE или UPDATE без WHERE, если об этом явно не просили.
        SQL оформляй в блоке ```sql.""";

    private PromptFactory() {
    }

    /** Генерация SQL по описанию на естественном языке. */
    public static List<ChatMessage> generateSql(String systemPrompt, DbContext ctx, String request, String currentScript) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(orDefault(systemPrompt)));
        StringBuilder user = new StringBuilder();
        user.append(ctx.render()).append('\n');
        if (notBlank(currentScript)) {
            user.append("Текущий запрос в редакторе (для контекста, можно доработать):\n```sql\n")
                .append(currentScript.strip()).append("\n```\n\n");
        }
        user.append("Задача: ").append(request.strip()).append("\n\n")
            .append("Верни ровно один блок ```sql с готовым к выполнению запросом. ")
            .append("Пояснения, если нужны, — только в виде SQL-комментариев внутри блока (-- …), не длиннее 3 строк.");
        messages.add(ChatMessage.user(user.toString()));
        return messages;
    }

    /** Объяснение / оптимизация / проверка существующего запроса. */
    public static List<ChatMessage> queryAction(String systemPrompt, DbContext ctx, QueryAction action, String query) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(orDefault(systemPrompt)));
        String task = switch (action) {
            case EXPLAIN -> """
                Объясни, что делает этот запрос: какие данные выбираются или меняются, как соединяются таблицы,
                какие условия и агрегации применяются. Отметь неочевидные места. Без переписывания запроса.""";
            case OPTIMIZE -> """
                Проанализируй производительность запроса. Укажи узкие места (полные сканы, неэффективные JOIN,
                функции над индексируемыми колонками, лишние подзапросы, SELECT *), предложи оптимизированный вариант
                в блоке ```sql с тем же результатом и, если уместно, индексы (CREATE INDEX отдельным блоком).
                В конце — кратко, почему новый вариант быстрее.""";
            case REVIEW -> """
                Проверь запрос на ошибки: синтаксис для указанного диалекта, несуществующие таблицы и колонки
                (по переданной структуре), логические ошибки (декартово произведение, NULL в NOT IN, неверная
                группировка), риски (UPDATE/DELETE без WHERE). Для каждой проблемы — строка и исправление.
                Если ошибок нет — так и скажи. Исправленный запрос, если он нужен, — в блоке ```sql.""";
        };
        String user = ctx.render() + "\nЗапрос:\n```sql\n" + query.strip() + "\n```\n\n" + task;
        messages.add(ChatMessage.user(user));
        return messages;
    }

    /** Системное сообщение для свободного чата с контекстом подключения (если он есть). */
    public static ChatMessage chatSystem(String systemPrompt, DbContext ctx) {
        String base = orDefault(systemPrompt);
        if (ctx == null) {
            return ChatMessage.system(base);
        }
        String rendered = ctx.render();
        return ChatMessage.system(rendered.isBlank() ? base : base + "\n\nКонтекст текущего подключения:\n" + rendered);
    }

    private static String orDefault(String systemPrompt) {
        return notBlank(systemPrompt) ? systemPrompt : DEFAULT_SYSTEM_PROMPT;
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
