/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Limits database metadata that DBeaver puts into the request before it leaves the computer.
 * <p>
 * What DBeaver sends (same format in DBeaver 25.2 and 26.2):
 * <ul>
 *   <li>system message, section "Context:" — SQL dialect, server version, DBeaver connection name, JDBC driver,
 *       default catalog/schema, current date;</li>
 *   <li>system message, section "Database snapshot:" (always the last one) — lists of catalogs/schemas and,
 *       when function calling is off, DDL of tables of the selected scope;</li>
 *   <li>results of functions the model calls: lists of schemas/tables and DDL (getTableDetails),
 *       text of the SQL editor (getCurrentScript).</li>
 * </ul>
 * Pure string processing: shared by both bundles (the compat25 copy is generated from this file).
 */
public final class MetadataFilter {

    /** What to keep of the "Database snapshot" section. */
    public enum Snapshot {
        /** As DBeaver builds it. */
        FULL,
        /** Only "... list: ..." lines (catalog and schema names), without table DDL. */
        NAMES,
        /** Remove the section. */
        NONE;

        public static Snapshot of(String value) {
            if (value != null) {
                for (Snapshot s : values()) {
                    if (s.name().equalsIgnoreCase(value.trim())) {
                        return s;
                    }
                }
            }
            return FULL;
        }
    }

    public static final String SNAPSHOT_HEADER = "Database snapshot:";
    static final String TRUNCATED_MARK = "\n…[truncated by Open WebUI plugin settings]";
    static final String REDACTED_RESULT = "This information is not available: it is hidden by the Open WebUI plugin "
        + "settings in DBeaver. Do not request it again; write the query from the names you already know.";

    private static final Pattern CONNECTION_LINE = Pattern.compile(
        "^-\\s*(Server version|DBeaver connection name|JDBC driver|Java driver):.*$", Pattern.MULTILINE);
    private static final Pattern NAMES_LINE = Pattern.compile("^-\\s.*\\blist:\\s.*$");

    private final boolean hideConnectionInfo;
    private final Snapshot snapshot;
    private final boolean allowTableDdl;
    private final boolean allowEditorText;
    private final int maxChars;

    public MetadataFilter(boolean hideConnectionInfo, Snapshot snapshot, boolean allowTableDdl, boolean allowEditorText, int maxChars) {
        this.hideConnectionInfo = hideConnectionInfo;
        this.snapshot = snapshot == null ? Snapshot.FULL : snapshot;
        this.allowTableDdl = allowTableDdl;
        this.allowEditorText = allowEditorText;
        this.maxChars = Math.max(0, maxChars);
    }

    public boolean isPassThrough() {
        return !hideConnectionInfo && snapshot == Snapshot.FULL && allowTableDdl && allowEditorText && maxChars == 0;
    }

    /** System prompt: connection details and the DB snapshot section. */
    public String filterSystem(String text) {
        if (text == null || isPassThrough()) {
            return text;
        }
        String result = text;
        if (hideConnectionInfo) {
            result = CONNECTION_LINE.matcher(result).replaceAll("").replaceAll("\n{2,}(?=- )", "\n");
        }
        int pos = result.indexOf(SNAPSHOT_HEADER);
        if (pos >= 0) {
            String head = result.substring(0, pos);
            String section = result.substring(pos + SNAPSHOT_HEADER.length());
            switch (snapshot) {
                case NONE -> result = head.stripTrailing() + "\n";
                case NAMES -> {
                    List<String> kept = new ArrayList<>();
                    for (String line : section.split("\n")) {
                        if (NAMES_LINE.matcher(line).matches()) {
                            kept.add(line);
                        }
                    }
                    result = kept.isEmpty()
                        ? head.stripTrailing() + "\n"
                        : head + SNAPSHOT_HEADER + "\n" + limit(String.join("\n", kept)) + "\n";
                }
                default -> result = head + SNAPSHOT_HEADER + limit(section);
            }
        }
        return result;
    }

    /** Whether the function may be offered to the model (by full id, e.g. "db_getTableDetails"). */
    public boolean isFunctionAllowed(String functionName) {
        String name = functionName == null ? "" : functionName.toLowerCase(Locale.ROOT);
        if (!allowTableDdl && name.endsWith("gettabledetails")) {
            return false;
        }
        return allowEditorText || !name.endsWith("getcurrentscript");
    }

    /** Result of a function call that goes back to the model. */
    public String filterFunctionResult(String functionName, String result) {
        if (result == null) {
            return null;
        }
        if (!isFunctionAllowed(functionName)) {
            return REDACTED_RESULT;
        }
        return limit(result);
    }

    String limit(String text) {
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + TRUNCATED_MARK;
    }
}
