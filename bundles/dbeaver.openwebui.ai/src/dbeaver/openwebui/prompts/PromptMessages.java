/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.prompts;

import org.eclipse.osgi.util.NLS;

public class PromptMessages extends NLS {
    private static final String BUNDLE_NAME = "dbeaver.openwebui.prompts.PromptMessages"; //$NON-NLS-1$

    public static String page_title;
    public static String page_description;
    public static String button_add;
    public static String button_delete;
    public static String label_name;
    public static String label_text;
    public static String text_tip;
    public static String label_send;
    public static String variables_hint;
    public static String new_prompt_name;
    public static String save_error;
    public static String menu_manage;

    static {
        NLS.initializeMessages(BUNDLE_NAME, PromptMessages.class);
    }

    private PromptMessages() {
    }
}
