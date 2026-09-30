package io.dbtools.openwebui.context;

import io.dbtools.openwebui.Activator;
import io.dbtools.openwebui.ai.PromptFactory;
import io.dbtools.openwebui.prefs.Prefs;
import org.eclipse.core.runtime.IProgressMonitor;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPDataSourceInfo;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionContextDefaults;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DefaultProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.struct.DBSObject;

import java.util.function.Supplier;

/**
 * Готовит контекст подключения для промпта. Выполняется в фоновом потоке.
 * <p>
 * DBeaver открывает соединения лениво: после перезапуска, простоя или «Disconnect» подключение
 * существует, но не установлено. Раньше в этом случае структура БД молча не передавалась —
 * теперь резолвер сам подключается (с обычным запросом пароля, если он нужен) и сообщает,
 * что именно ушло модели.
 */
public final class DbContextResolver {

    /**
     * @param db   контекст для промпта (никогда не null; поля могут быть пустыми)
     * @param note строка для пользователя: что передано или почему нет
     * @param ok   true, если структура таблиц передана
     */
    public record Resolved(PromptFactory.DbContext db, String note, boolean ok) {
    }

    private DbContextResolver() {
    }

    /**
     * @param container        подключение (редактора или выбранное в навигаторе), может быть null
     * @param editorContext    источник контекста выполнения редактора; null — взять контекст по умолчанию
     * @param includeSchema    передавать ли структуру таблиц
     * @param hintTexts        тексты для приоритизации таблиц (задача, запрос)
     */
    public static Resolved resolve(IProgressMonitor monitor, DBPDataSourceContainer container,
                                   Supplier<DBCExecutionContext> editorContext, boolean includeSchema,
                                   String... hintTexts) {
        return resolve(monitor, container, editorContext, null, includeSchema, hintTexts);
    }

    /**
     * @param schemaPath  путь выбранной пользователем схемы («db.schema» или «schema»); null — схема по умолчанию
     */
    public static Resolved resolve(IProgressMonitor monitor, DBPDataSourceContainer container,
                                   Supplier<DBCExecutionContext> editorContext, String schemaPath,
                                   boolean includeSchema, String... hintTexts) {
        if (container == null) {
            return new Resolved(new PromptFactory.DbContext(null, null, null, null),
                "Контекст БД не передан: у SQL-редактора не выбрано подключение", false);
        }
        String name = container.getName();
        DBRProgressMonitor dbMonitor = new DefaultProgressMonitor(monitor);

        if (!container.isConnected() || container.getDataSource() == null) {
            monitor.subTask("Подключение к " + name + "…");
            try {
                container.connect(dbMonitor, true, true);
            } catch (Exception e) {
                Activator.logError("Не удалось подключиться к " + name, e);
                return new Resolved(new PromptFactory.DbContext(driverName(container), null, null, null),
                    "Контекст БД не передан: не удалось подключиться к «" + name + "»: " + message(e), false);
            }
            if (!container.isConnected() || container.getDataSource() == null) {
                return new Resolved(new PromptFactory.DbContext(driverName(container), null, null, null),
                    "Контекст БД не передан: подключение к «" + name + "» отменено", false);
            }
        }

        DBPDataSource ds = container.getDataSource();
        DBCExecutionContext exec = editorContext == null ? null : editorContext.get();
        if (exec == null) {
            exec = DBUtils.getDefaultContext(ds, true);
        }

        String product = productName(container, ds);
        SQLDialect dialect = ds.getSQLDialect();
        String dialectName = dialect == null ? null : dialect.getDialectName();
        String defaultSchema = defaultSchemaName(exec);
        DBSObject explicitRoot = null;
        if (schemaPath != null) {
            explicitRoot = ConnectionCatalog.findSchema(dbMonitor, ds, schemaPath);
            if (explicitRoot == null) {
                return new Resolved(new PromptFactory.DbContext(product, dialectName, defaultSchema, null),
                    "Структура БД не передана: схема «" + schemaPath + "» не найдена в «" + name + "»", false);
            }
            defaultSchema = schemaPath;
        }

        if (!includeSchema) {
            return new Resolved(new PromptFactory.DbContext(product, dialectName, defaultSchema, null),
                "Контекст: «" + name + "», " + product + " — структура таблиц отключена в настройках", true);
        }

        monitor.subTask("Чтение структуры базы данных…");
        SchemaContextBuilder.Result schema = new SchemaContextBuilder(Prefs.maxTables(), Prefs.maxColumns())
            .build(dbMonitor, container, exec, explicitRoot, SchemaContextBuilder.tokens(hintTexts));

        PromptFactory.DbContext db = new PromptFactory.DbContext(product, dialectName, defaultSchema, schema.text());
        if (schema.problem() != null) {
            return new Resolved(db, "Структура БД «" + name + "» не передана: " + schema.problem(), false);
        }
        String where = schema.rootName() != null ? ", " + schema.rootName() : "";
        String count = schema.found() > schema.tables()
            ? schema.tables() + " из " + schema.found() + " таблиц"
            : "таблиц: " + schema.tables();
        return new Resolved(db, "Контекст: «" + name + "» (" + product + where + "), " + count, true);
    }

    @SuppressWarnings("rawtypes")
    static String defaultSchemaName(DBCExecutionContext ctx) {
        if (ctx == null) {
            return null;
        }
        DBCExecutionContextDefaults defaults = ctx.getContextDefaults();
        if (defaults == null) {
            return null;
        }
        DBSObject schema = defaults.getDefaultSchema();
        DBSObject catalog = defaults.getDefaultCatalog();
        if (schema != null && catalog != null) {
            return catalog.getName() + "." + schema.getName();
        }
        if (schema != null) {
            return schema.getName();
        }
        return catalog == null ? null : catalog.getName();
    }

    private static String productName(DBPDataSourceContainer container, DBPDataSource ds) {
        DBPDataSourceInfo info = ds.getInfo();
        if (info != null && info.getDatabaseProductName() != null) {
            String version = info.getDatabaseProductVersion();
            return info.getDatabaseProductName() + (version != null ? " " + version : "");
        }
        return driverName(container);
    }

    private static String driverName(DBPDataSourceContainer container) {
        return container.getDriver() == null ? null : container.getDriver().getName();
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
