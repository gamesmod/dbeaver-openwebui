package io.dbtools.openwebui.context;

import io.dbtools.openwebui.Activator;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionContextDefaults;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAssociation;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSEntityAttributeRef;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraint;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraintType;
import org.jkiss.dbeaver.model.struct.DBSEntityReferrer;
import org.jkiss.dbeaver.model.struct.DBSEntityType;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Собирает компактное текстовое описание структуры БД для промпта.
 * <p>
 * Передаются только метаданные (имена таблиц/представлений, колонки, типы, PK, FK) — данные таблиц не читаются.
 * Если таблиц больше лимита, в приоритете те, чьи имена встречаются в запросе или в задаче пользователя.
 * Работает в фоновом потоке: метаданные могут подгружаться из БД.
 */
public final class SchemaContextBuilder {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}_$#]+");
    private static final int MAX_DEPTH = 3;
    private static final int CANDIDATE_FACTOR = 20;

    private final int maxTables;
    private final int maxColumns;

    public SchemaContextBuilder(int maxTables, int maxColumns) {
        this.maxTables = maxTables;
        this.maxColumns = maxColumns;
    }

    /** Слова из текста в нижнем регистре — для приоритизации таблиц. */
    public static Set<String> tokens(String... texts) {
        Set<String> result = new HashSet<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            for (String t : TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT))) {
                if (t.length() > 1) {
                    result.add(t);
                    result.add(stem(t));
                }
            }
        }
        return result;
    }

    /**
     * Результат сбора контекста.
     *
     * @param text        описание таблиц для промпта (пустое, если ничего не найдено)
     * @param tables      сколько таблиц/представлений попало в описание
     * @param found       сколько найдено всего
     * @param rootName    откуда начинался обход (схема, каталог или подключение)
     * @param problem     причина, по которой описание пустое, или null
     */
    public record Result(String text, int tables, int found, String rootName, String problem) {
        static Result empty(String problem) {
            return new Result("", 0, 0, null, problem);
        }
    }

    /**
     * Строит описание структуры БД. Если схема/каталог по умолчанию пусты или недоступны,
     * обходит всё подключение.
     */
    public Result build(DBRProgressMonitor monitor, DBPDataSourceContainer container,
                        DBCExecutionContext executionContext, Set<String> hints) {
        return build(monitor, container, executionContext, null, hints);
    }

    /**
     * @param explicitRoot схема/каталог, выбранные пользователем; если заданы, обход идёт только по ним
     */
    public Result build(DBRProgressMonitor monitor, DBPDataSourceContainer container,
                        DBCExecutionContext executionContext, DBSObject explicitRoot, Set<String> hints) {
        if (container == null || container.getDataSource() == null) {
            return Result.empty("подключение не установлено");
        }
        DBPDataSource dataSource = container.getDataSource();
        int candidateLimit = Math.max(maxTables * CANDIDATE_FACTOR, 500);
        List<DBSEntity> entities = new ArrayList<>();
        String lastError = null;

        List<DBSObject> roots = new ArrayList<>();
        if (explicitRoot != null) {
            roots.add(explicitRoot);
        } else {
            DBSObject defaultRoot = resolveDefaultRoot(executionContext);
            if (defaultRoot != null) {
                roots.add(defaultRoot);
            }
            if (dataSource instanceof DBSObjectContainer) {
                roots.add((DBSObject) dataSource);
            }
        }
        if (roots.isEmpty()) {
            return Result.empty("драйвер не предоставляет структуру объектов");
        }
        DBSObject root = null;
        for (DBSObject candidate : roots) {
            try {
                collect(monitor, candidate, 0, entities, candidateLimit);
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Activator.logError("Не удалось прочитать метаданные для контекста модели", e);
            }
            if (!entities.isEmpty()) {
                root = candidate;
                break;
            }
        }
        if (entities.isEmpty()) {
            return Result.empty(lastError != null ? "ошибка чтения метаданных: " + lastError
                : explicitRoot != null ? "в «" + explicitRoot.getName() + "» нет таблиц"
                : "в схеме по умолчанию и в подключении не найдено таблиц");
        }

        Set<String> h = hints == null ? Set.of() : hints;
        entities.sort(Comparator
            .comparingInt((DBSEntity e) -> relevance(e, h)).reversed()
            .thenComparing(e -> e.getName() == null ? "" : e.getName(), String.CASE_INSENSITIVE_ORDER));

        boolean qualify = !(root instanceof DBSEntity) && hasSeveralParents(entities);
        StringBuilder sb = new StringBuilder();
        int written = 0;
        for (DBSEntity entity : entities) {
            if (monitor.isCanceled() || written >= maxTables) {
                break;
            }
            String line = describe(monitor, entity, qualify);
            if (line != null) {
                sb.append(line).append('\n');
                written++;
            }
        }
        if (entities.size() > written) {
            sb.append("… и ещё ").append(entities.size() - written)
                .append(" объектов (не показаны из-за лимита контекста)\n");
        }
        return new Result(sb.toString(), written, entities.size(), root.getName(), null);
    }

    @SuppressWarnings("rawtypes")
    private static DBSObject resolveDefaultRoot(DBCExecutionContext ctx) {
        if (ctx == null) {
            return null;
        }
        DBCExecutionContextDefaults defaults = ctx.getContextDefaults();
        if (defaults == null) {
            return null;
        }
        if (defaults.getDefaultSchema() != null) {
            return defaults.getDefaultSchema();
        }
        return defaults.getDefaultCatalog();
    }

    private static void collect(DBRProgressMonitor monitor, DBSObject object, int depth,
                                List<DBSEntity> out, int limit) throws Exception {
        if (monitor.isCanceled() || out.size() >= limit || !(object instanceof DBSObjectContainer container)) {
            return;
        }
        Collection<? extends DBSObject> children = container.getChildren(monitor);
        if (children == null) {
            return;
        }
        for (DBSObject child : children) {
            if (out.size() >= limit || monitor.isCanceled()) {
                return;
            }
            if (child instanceof DBSEntity entity) {
                if (isTableLike(entity)) {
                    out.add(entity);
                }
            } else if (child instanceof DBSObjectContainer && depth < MAX_DEPTH && !isSystemContainer(child)) {
                try {
                    collect(monitor, child, depth + 1, out, limit);
                } catch (Exception e) {
                    // недоступная схема/каталог — пропускаем, но не прерываем сбор
                    Activator.logInfo("Пропущен контейнер " + child.getName() + ": " + e.getMessage());
                }
            }
        }
    }

    private static boolean isTableLike(DBSEntity entity) {
        DBSEntityType type = entity.getEntityType();
        return type == null || type == DBSEntityType.TABLE || type == DBSEntityType.VIEW
            || type == DBSEntityType.VIRTUAL_ENTITY;
    }

    private static boolean isSystemContainer(DBSObject obj) {
        String n = obj.getName() == null ? "" : obj.getName().toLowerCase(Locale.ROOT);
        return n.equals("information_schema") || n.equals("pg_catalog") || n.equals("sys")
            || n.equals("mysql") || n.equals("performance_schema") || n.startsWith("pg_toast");
    }

    private static int relevance(DBSEntity entity, Set<String> hints) {
        if (hints.isEmpty() || entity.getName() == null) {
            return 0;
        }
        String name = entity.getName().toLowerCase(Locale.ROOT);
        if (hints.contains(name) || hints.contains(stem(name))) {
            return 4;
        }
        // «заказы клиентов» ~ orders / customer_orders: совпадение по части имени
        int best = 0;
        for (String part : name.split("_")) {
            if (part.length() > 2 && (hints.contains(part) || hints.contains(stem(part)))) {
                best = Math.max(best, part.equals(name.substring(name.lastIndexOf('_') + 1)) ? 3 : 2);
            }
        }
        if (best > 0) {
            return best;
        }
        for (String hint : hints) {
            if (hint.length() > 3 && name.contains(hint)) {
                return 1;
            }
        }
        return 0;
    }

    /** Простейшая нормализация английского множественного числа: orders → order. */
    private static String stem(String word) {
        return word.length() > 3 && word.endsWith("s") && !word.endsWith("ss") ? word.substring(0, word.length() - 1) : word;
    }

    private static boolean hasSeveralParents(List<DBSEntity> entities) {
        Set<DBSObject> parents = new HashSet<>();
        for (DBSEntity e : entities) {
            parents.add(e.getParentObject());
            if (parents.size() > 1) {
                return true;
            }
        }
        return false;
    }

    private String describe(DBRProgressMonitor monitor, DBSEntity entity, boolean qualify) {
        try {
            String name = qualify && entity.getParentObject() != null
                ? entity.getParentObject().getName() + "." + entity.getName()
                : entity.getName();

            Set<String> pk = new HashSet<>();
            Map<String, String> fk = new HashMap<>();
            readConstraints(monitor, entity, pk, fk);

            List<? extends DBSEntityAttribute> attributes = entity.getAttributes(monitor);
            List<String> cols = new ArrayList<>();
            int total = attributes == null ? 0 : attributes.size();
            if (attributes != null) {
                for (DBSEntityAttribute attr : attributes) {
                    if (cols.size() >= maxColumns) {
                        break;
                    }
                    StringBuilder c = new StringBuilder(attr.getName());
                    String type = attr.getFullTypeName();
                    if (type != null && !type.isBlank()) {
                        c.append(' ').append(type);
                    }
                    if (pk.contains(attr.getName())) {
                        c.append(" PK");
                    }
                    String ref = fk.get(attr.getName());
                    if (ref != null) {
                        c.append(" FK→").append(ref);
                    }
                    if (attr.isRequired() && !pk.contains(attr.getName())) {
                        c.append(" NOT NULL");
                    }
                    cols.add(c.toString());
                }
            }
            if (total > cols.size()) {
                cols.add("…+" + (total - cols.size()));
            }
            String kind = entity.getEntityType() == DBSEntityType.VIEW ? " [VIEW]" : "";
            return name + "(" + String.join(", ", cols) + ")" + kind;
        } catch (Exception e) {
            return entity.getName() + "(?)";
        }
    }

    private static void readConstraints(DBRProgressMonitor monitor, DBSEntity entity,
                                        Set<String> pk, Map<String, String> fk) {
        try {
            Collection<? extends DBSEntityConstraint> constraints = entity.getConstraints(monitor);
            if (constraints != null) {
                for (DBSEntityConstraint c : constraints) {
                    if (c.getConstraintType() == DBSEntityConstraintType.PRIMARY_KEY && c instanceof DBSEntityReferrer r) {
                        pk.addAll(attributeNames(monitor, r));
                    }
                }
            }
        } catch (Exception ignored) {
            // не все драйверы отдают ограничения — работаем без PK
        }
        try {
            Collection<? extends DBSEntityAssociation> associations = entity.getAssociations(monitor);
            if (associations != null) {
                for (DBSEntityAssociation a : associations) {
                    if (!(a instanceof DBSEntityReferrer r) || a.getAssociatedEntity() == null) {
                        continue;
                    }
                    List<String> local = new ArrayList<>(attributeNames(monitor, r));
                    List<String> remote = a.getReferencedConstraint() instanceof DBSEntityReferrer rr
                        ? new ArrayList<>(attributeNames(monitor, rr)) : List.of();
                    String target = a.getAssociatedEntity().getName();
                    for (int i = 0; i < local.size(); i++) {
                        String col = i < remote.size() ? "." + remote.get(i) : "";
                        fk.put(local.get(i), target + col);
                    }
                }
            }
        } catch (Exception ignored) {
            // то же для внешних ключей
        }
    }

    private static Set<String> attributeNames(DBRProgressMonitor monitor, DBSEntityReferrer referrer) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        List<? extends DBSEntityAttributeRef> refs = referrer.getAttributeReferences(monitor);
        if (refs != null) {
            for (DBSEntityAttributeRef ref : refs) {
                if (ref.getAttribute() != null) {
                    names.add(ref.getAttribute().getName());
                }
            }
        }
        return names;
    }
}
