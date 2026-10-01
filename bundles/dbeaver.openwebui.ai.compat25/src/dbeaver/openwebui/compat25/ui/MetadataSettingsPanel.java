/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.compat25.ui;

import dbeaver.openwebui.compat25.model.MetadataFilter;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.jkiss.code.NotNull;

/**
 * "Metadata sent to the model" group of the engine settings (DBeaver 26.2+ and 25.2 bundles share the layout).
 */
final class MetadataSettingsPanel {

    private static final MetadataFilter.Snapshot[] SNAPSHOT_VALUES = MetadataFilter.Snapshot.values();

    final Button hideConnectionCheck;
    final Combo snapshotCombo;
    final Button allowDdlCheck;
    final Button allowEditorCheck;
    final Spinner maxCharsSpinner;

    MetadataSettingsPanel(@NotNull Composite parent, int hSpan) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(OpenWebUIMessages.meta_group);
        group.setLayout(GridLayoutFactory.swtDefaults().numColumns(2).create());
        group.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).span(hSpan, 1).create());

        Label info = new Label(group, SWT.WRAP);
        info.setText(OpenWebUIMessages.meta_info);
        info.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).span(2, 1).hint(300, SWT.DEFAULT).create());

        hideConnectionCheck = check(group, OpenWebUIMessages.meta_hide_connection, OpenWebUIMessages.meta_hide_connection_tip);

        new Label(group, SWT.NONE).setText(OpenWebUIMessages.meta_snapshot);
        snapshotCombo = new Combo(group, SWT.DROP_DOWN | SWT.READ_ONLY);
        snapshotCombo.setItems(
            OpenWebUIMessages.meta_snapshot_full,
            OpenWebUIMessages.meta_snapshot_names,
            OpenWebUIMessages.meta_snapshot_none);
        snapshotCombo.setToolTipText(OpenWebUIMessages.meta_snapshot_tip);
        snapshotCombo.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).create());

        allowDdlCheck = check(group, OpenWebUIMessages.meta_allow_ddl, OpenWebUIMessages.meta_allow_ddl_tip);
        allowEditorCheck = check(group, OpenWebUIMessages.meta_allow_editor, OpenWebUIMessages.meta_allow_editor_tip);

        new Label(group, SWT.NONE).setText(OpenWebUIMessages.meta_max_chars);
        maxCharsSpinner = new Spinner(group, SWT.BORDER);
        maxCharsSpinner.setValues(0, 0, 1_000_000, 0, 1000, 10_000);
        maxCharsSpinner.setToolTipText(OpenWebUIMessages.meta_max_chars_tip);
    }

    private static Button check(Composite parent, String text, String tip) {
        Button b = new Button(parent, SWT.CHECK);
        b.setText(text);
        b.setToolTipText(tip);
        b.setLayoutData(GridDataFactory.fillDefaults().span(2, 1).create());
        return b;
    }

    void load(boolean hideConnection, @NotNull MetadataFilter.Snapshot snapshot, boolean allowDdl, boolean allowEditor, int maxChars) {
        hideConnectionCheck.setSelection(hideConnection);
        snapshotCombo.select(snapshot.ordinal());
        allowDdlCheck.setSelection(allowDdl);
        allowEditorCheck.setSelection(allowEditor);
        maxCharsSpinner.setSelection(maxChars);
    }

    @NotNull
    MetadataFilter.Snapshot snapshot() {
        int i = snapshotCombo.getSelectionIndex();
        return i < 0 ? MetadataFilter.Snapshot.FULL : SNAPSHOT_VALUES[i];
    }
}
