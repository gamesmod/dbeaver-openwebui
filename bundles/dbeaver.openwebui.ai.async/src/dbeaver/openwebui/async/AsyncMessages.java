/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async;

import org.eclipse.osgi.util.NLS;

public class AsyncMessages extends NLS {
    private static final String BUNDLE_NAME = "dbeaver.openwebui.async.AsyncMessages"; //$NON-NLS-1$

    public static String placeholder;
    public static String placeholder_link;
    public static String connection_lost;
    public static String error_prefix;
    public static String not_supported;

    public static String page_description;
    public static String persist_label;
    public static String persist_tip;
    public static String mirror_label;
    public static String mirror_tip;
    public static String background_label;
    public static String background_tip;
    public static String mode_group;
    public static String mode_wait;
    public static String mode_wait_tip;
    public static String mode_detach;
    public static String mode_detach_tip;
    public static String poll_label;
    public static String functions_note;
    public static String restart_note;
    public static String pending_label;
    public static String pending_none;
    public static String pending_count;

    static {
        NLS.initializeMessages(BUNDLE_NAME, AsyncMessages.class);
    }

    private AsyncMessages() {
    }
}
