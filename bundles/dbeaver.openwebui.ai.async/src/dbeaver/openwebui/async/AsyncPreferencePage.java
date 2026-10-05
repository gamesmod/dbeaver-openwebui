/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async;

import dbeaver.openwebui.async.core.AsyncSettings;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/**
 * Window → Preferences → AI → Open WebUI: background chats.
 */
public class AsyncPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private Button persistCheck;
    private Button mirrorCheck;
    private Button backgroundCheck;
    private Button waitRadio;
    private Button detachRadio;
    private Spinner pollSpinner;

    @Override
    public void init(IWorkbench workbench) {
        // nothing to initialize
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(GridLayoutFactory.fillDefaults().numColumns(2).create());
        c.setLayoutData(GridDataFactory.fillDefaults().grab(true, true).create());

        wrapLabel(c, AsyncMessages.page_description);

        persistCheck = check(c, AsyncMessages.persist_label, AsyncMessages.persist_tip);
        mirrorCheck = check(c, AsyncMessages.mirror_label, AsyncMessages.mirror_tip);
        backgroundCheck = check(c, AsyncMessages.background_label, AsyncMessages.background_tip);

        Group mode = new Group(c, SWT.NONE);
        mode.setText(AsyncMessages.mode_group);
        mode.setLayout(GridLayoutFactory.swtDefaults().create());
        mode.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).span(2, 1).indent(16, 0).create());
        waitRadio = new Button(mode, SWT.RADIO);
        waitRadio.setText(AsyncMessages.mode_wait);
        waitRadio.setToolTipText(AsyncMessages.mode_wait_tip);
        detachRadio = new Button(mode, SWT.RADIO);
        detachRadio.setText(AsyncMessages.mode_detach);
        detachRadio.setToolTipText(AsyncMessages.mode_detach_tip);

        Label pollLabel = new Label(c, SWT.NONE);
        pollLabel.setText(AsyncMessages.poll_label);
        pollLabel.setLayoutData(GridDataFactory.fillDefaults().indent(16, 0).create());
        pollSpinner = new Spinner(c, SWT.BORDER);
        pollSpinner.setValues(2, 1, 60, 0, 1, 5);

        wrapLabel(c, AsyncMessages.functions_note);
        wrapLabel(c, AsyncMessages.restart_note);

        Label pending = new Label(c, SWT.NONE);
        int count = JobPoller.pendingCount();
        pending.setText(AsyncMessages.pending_label + " "
            + (count == 0 ? AsyncMessages.pending_none : NLS.bind(AsyncMessages.pending_count, count)));
        pending.setLayoutData(GridDataFactory.fillDefaults().span(2, 1).create());

        backgroundCheck.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> updateEnablement()));
        load(AsyncPlugin.getSettings());
        return c;
    }

    private void load(AsyncSettings s) {
        persistCheck.setSelection(s.persistChats());
        mirrorCheck.setSelection(s.mirrorChats());
        backgroundCheck.setSelection(s.background());
        waitRadio.setSelection(!s.detach());
        detachRadio.setSelection(s.detach());
        pollSpinner.setSelection(s.pollSeconds());
        updateEnablement();
    }

    private void updateEnablement() {
        boolean bg = backgroundCheck.getSelection();
        waitRadio.setEnabled(bg);
        detachRadio.setEnabled(bg);
        pollSpinner.setEnabled(bg);
    }

    @Override
    protected void performDefaults() {
        load(AsyncSettings.DEFAULTS);
        super.performDefaults();
    }

    @Override
    public boolean performOk() {
        AsyncPlugin.saveSettings(new AsyncSettings(
            persistCheck.getSelection(),
            mirrorCheck.getSelection(),
            backgroundCheck.getSelection(),
            detachRadio.getSelection(),
            pollSpinner.getSelection()));
        JobPoller.start();
        return true;
    }

    private static Button check(Composite parent, String text, String tip) {
        Button b = new Button(parent, SWT.CHECK);
        b.setText(text);
        b.setToolTipText(tip);
        b.setLayoutData(GridDataFactory.fillDefaults().span(2, 1).create());
        return b;
    }

    private static void wrapLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.WRAP);
        l.setText(text);
        l.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).span(2, 1).hint(420, SWT.DEFAULT).create());
    }
}
