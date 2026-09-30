package io.dbtools.openwebui.context;

import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.model.struct.rdb.DBSCatalog;
import org.jkiss.dbeaver.model.struct.rdb.DBSSchema;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Подключения рабочего пространства DBeaver и схемы внутри подключения — для выбора в панели чата.
 */
public final class ConnectionCatalog {

    private static final int MAX_SCHEMAS = 1000;

    private ConnectionCatalog() {
    }

    /** Все подключения всех проектов; сначала активный проект, внутри — по имени. */
    public static List<DBPDataSourceContainer> connections() {
        List<DBPDataSourceContainer> result = new ArrayList<>();
        try {
            var workspace = DBWorkbench.getPlatform().getWorkspace();
            DBPProject active = workspace.getActiveProject();
            List<DBPProject> projects = new ArrayList<>();
            if (active != null) {
                projects.add(active);
            }
            for (DBPProject p : workspace.getProjects()) {
                if (p != active) {
                    projects.add(p);
                }
            }
            for (DBPProject p : projects) {
                if (p.getDataSourceRegistry() == null) {
                    continue;
                }
                List<DBPDataSourceContainer> list = new ArrayList<>(p.getDataSourceRegistry().getDataSources());
                list.sort(Comparator.comparing(c -> c.getName() == null ? "" : c.getName(), String.CASE_INSENSITIVE_ORDER));
                result.addAll(list);
            }
        } catch (RuntimeException ignored) {
            // рабочее пространство ещё не готово — пустой список
        }
        return result;
    }

    public static DBPDataSourceContainer byId(String id) {
        if (id == null) {
            return null;
        }
        for (DBPDataSourceContainer c : connections()) {
            if (id.equals(c.getId())) {
                return c;
            }
        }
        return null;
    }

    /**
     * Схемы и каталоги подключения в виде путей «каталог.схема» / «схема». Нужна установленная связь.
     * Системные схемы идут в конце списка.
     */
    public static List<String> schemaPaths(DBRProgressMonitor monitor, DBPDataSource dataSource) throws Exception {
        List<String> result = new ArrayList<>();
        if (!(dataSource instanceof DBSObjectContainer root)) {
            return result;
        }
        Collection<? extends DBSObject> level1 = root.getChildren(monitor);
        if (level1 == null) {
            return result;
        }
        for (DBSObject o : level1) {
            if (result.size() >= MAX_SCHEMAS || monitor.isCanceled()) {
                break;
            }
            if (o instanceof DBSEntity) {
                continue;
            }
            boolean nested = false;
            if (o instanceof DBSCatalog && o instanceof DBSObjectContainer oc) {
                Collection<? extends DBSObject> level2 = oc.getChildren(monitor);
                if (level2 != null) {
                    for (DBSObject s : level2) {
                        if (s instanceof DBSSchema) {
                            result.add(o.getName() + "." + s.getName());
                            nested = true;
                        }
                    }
                }
            }
            if (!nested && (o instanceof DBSSchema || o instanceof DBSCatalog)) {
                result.add(o.getName());
            }
        }
        result.sort(Comparator.comparing((String p) -> isSystem(p) ? 1 : 0)
            .thenComparing(String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Находит схему/каталог по пути из {@link #schemaPaths}. */
    public static DBSObject findSchema(DBRProgressMonitor monitor, DBPDataSource dataSource, String path) {
        if (!(dataSource instanceof DBSObjectContainer root) || path == null) {
            return null;
        }
        try {
            // Сначала имя целиком: у схемы в имени может быть точка.
            DBSObject whole = root.getChild(monitor, path);
            if (whole != null && !(whole instanceof DBSEntity)) {
                return whole;
            }
            int dot = path.indexOf('.');
            if (dot > 0) {
                DBSObject parent = root.getChild(monitor, path.substring(0, dot));
                if (parent instanceof DBSObjectContainer pc) {
                    return pc.getChild(monitor, path.substring(dot + 1));
                }
            }
        } catch (Exception ignored) {
            // не найдено
        }
        return null;
    }

    static boolean isSystem(String path) {
        String n = path.toLowerCase(Locale.ROOT);
        String last = n.substring(n.lastIndexOf('.') + 1);
        return last.equals("information_schema") || last.equals("pg_catalog") || last.startsWith("pg_toast")
            || last.startsWith("pg_temp") || last.equals("sys") || last.equals("mysql")
            || last.equals("performance_schema") || last.equals("system");
    }
}
