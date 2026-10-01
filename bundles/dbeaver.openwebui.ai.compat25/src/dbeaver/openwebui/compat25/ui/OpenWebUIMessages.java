/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.compat25.ui;

import org.eclipse.osgi.util.NLS;

public class OpenWebUIMessages extends NLS {
    private static final String BUNDLE_NAME = "dbeaver.openwebui.compat25.ui.OpenWebUIMessages"; //$NON-NLS-1$

    public static String base_url_label;
    public static String base_url_tip;
    public static String base_url_hint;
    public static String token_label;
    public static String token_placeholder;
    public static String token_tip;
    public static String url_required;
    public static String temperature_label;
    public static String temperature_tip;
    public static String streaming_label;
    public static String streaming_tip;
    public static String functions_label;
    public static String functions_tip;
    public static String hide_thinking_label;
    public static String hide_thinking_tip;
    public static String headers_label;
    public static String headers_tip;
    public static String meta_group;
    public static String meta_info;
    public static String meta_hide_connection;
    public static String meta_hide_connection_tip;
    public static String meta_snapshot;
    public static String meta_snapshot_full;
    public static String meta_snapshot_names;
    public static String meta_snapshot_none;
    public static String meta_snapshot_tip;
    public static String meta_allow_ddl;
    public static String meta_allow_ddl_tip;
    public static String meta_allow_editor;
    public static String meta_allow_editor_tip;
    public static String meta_max_chars;
    public static String meta_max_chars_tip;

    static {
        NLS.initializeMessages(BUNDLE_NAME, OpenWebUIMessages.class);
    }

    private OpenWebUIMessages() {
    }
}
