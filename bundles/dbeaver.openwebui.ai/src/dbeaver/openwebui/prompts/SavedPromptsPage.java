/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.prompts;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.IWorkbenchPropertyPage;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import java.util.ArrayList;

/**
 * AI → Prompts → «Мои промпты»: list of the user's saved prompts.
 * Prompts are used from the «Мои промпты» drop-down in the AI chat toolbar.
 */
public class SavedPromptsPage extends PreferencePage implements IWorkbenchPreferencePage, IWorkbenchPropertyPage {

    private final java.util.List<PromptLibrary.SavedPrompt> prompts = new ArrayList<>();
    private int selected = -1;
    private boolean updating;
    @Nullable
    private IAdaptable element;

    private List list;
    private Text nameText;
    private Text promptText;
    private Button sendCheck;
    private Button deleteButton;
    private Button upButton;
    private Button downButton;

    @Override
    public void init(@NotNull IWorkbench workbench) {
        noDefaultAndApplyButton();
    }

    @Override
    public IAdaptable getElement() {
        return element;
    }

    @Override
    public void setElement(IAdaptable element) {
        this.element = element;
    }

    @NotNull
    @Override
    protected Control createContents(@NotNull Composite parent) {
        for (PromptLibrary.SavedPrompt p : PromptLibrary.load()) {
            prompts.add(p.copy());
        }
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(GridLayoutFactory.fillDefaults().create());
        root.setLayoutData(new GridData(GridData.FILL_BOTH));

        Label info = new Label(root, SWT.WRAP);
        info.setText(PromptMessages.page_description);
        info.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).hint(400, SWT.DEFAULT).create());

        SashForm sash = new SashForm(root, SWT.HORIZONTAL | SWT.SMOOTH);
        sash.setLayoutData(GridDataFactory.fillDefaults().grab(true, true).hint(600, 380).create());

        Composite left = new Composite(sash, SWT.NONE);
        left.setLayout(GridLayoutFactory.fillDefaults().create());
        list = new List(left, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
        list.setLayoutData(new GridData(GridData.FILL_BOTH));
        list.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> select(list.getSelectionIndex())));

        Composite buttons = new Composite(left, SWT.NONE);
        buttons.setLayout(GridLayoutFactory.fillDefaults().numColumns(4).equalWidth(true).create());
        buttons.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        Button add = button(buttons, PromptMessages.button_add);
        add.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> addPrompt()));
        deleteButton = button(buttons, PromptMessages.button_delete);
        deleteButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> deletePrompt()));
        upButton = button(buttons, "↑");
        upButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> move(-1)));
        downButton = button(buttons, "↓");
        downButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> move(1)));

        Composite right = new Composite(sash, SWT.NONE);
        right.setLayout(GridLayoutFactory.fillDefaults().numColumns(2).create());
        new Label(right, SWT.NONE).setText(PromptMessages.label_name);
        nameText = new Text(right, SWT.BORDER);
        nameText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        nameText.addModifyListener(e -> {
            if (!updating && selected >= 0) {
                prompts.get(selected).name = nameText.getText();
                list.setItem(selected, displayName(prompts.get(selected)));
            }
        });
        Label textLabel = new Label(right, SWT.NONE);
        textLabel.setText(PromptMessages.label_text);
        textLabel.setLayoutData(new GridData(GridData.VERTICAL_ALIGN_BEGINNING));
        promptText = new Text(right, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        promptText.setLayoutData(new GridData(GridData.FILL_BOTH));
        promptText.setToolTipText(PromptMessages.text_tip);
        promptText.addModifyListener(e -> {
            if (!updating && selected >= 0) {
                prompts.get(selected).text = promptText.getText();
            }
        });
        new Label(right, SWT.NONE);
        sendCheck = new Button(right, SWT.CHECK);
        sendCheck.setText(PromptMessages.label_send);
        sendCheck.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
            if (selected >= 0) {
                prompts.get(selected).send = sendCheck.getSelection();
            }
        }));
        new Label(right, SWT.NONE);
        Label hint = new Label(right, SWT.WRAP);
        hint.setText(PromptMessages.variables_hint);
        hint.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).hint(250, SWT.DEFAULT).create());

        sash.setWeights(35, 65);
        refreshList();
        select(prompts.isEmpty() ? -1 : 0);
        return root;
    }

    @Override
    public boolean performOk() {
        java.util.List<PromptLibrary.SavedPrompt> toSave = new ArrayList<>();
        for (PromptLibrary.SavedPrompt p : prompts) {
            if (p.name != null && !p.name.isBlank() && p.text != null && !p.text.isBlank()) {
                p.name = p.name.strip();
                toSave.add(p);
            }
        }
        try {
            PromptLibrary.save(toSave);
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(PromptMessages.page_title, PromptMessages.save_error, e);
            return false;
        }
        return super.performOk();
    }

    @Override
    protected void performDefaults() {
        prompts.clear();
        prompts.addAll(PromptLibrary.defaults());
        refreshList();
        select(prompts.isEmpty() ? -1 : 0);
        super.performDefaults();
    }

    private void addPrompt() {
        prompts.add(new PromptLibrary.SavedPrompt(PromptMessages.new_prompt_name, "", false));
        refreshList();
        select(prompts.size() - 1);
        nameText.setFocus();
        nameText.selectAll();
    }

    private void deletePrompt() {
        if (selected < 0) {
            return;
        }
        prompts.remove(selected);
        refreshList();
        select(Math.min(selected, prompts.size() - 1));
    }

    private void move(int delta) {
        int target = selected + delta;
        if (selected < 0 || target < 0 || target >= prompts.size()) {
            return;
        }
        PromptLibrary.SavedPrompt p = prompts.remove(selected);
        prompts.add(target, p);
        refreshList();
        select(target);
    }

    private void refreshList() {
        list.removeAll();
        for (PromptLibrary.SavedPrompt p : prompts) {
            list.add(displayName(p));
        }
    }

    private void select(int index) {
        selected = index;
        updating = true;
        try {
            boolean has = index >= 0 && index < prompts.size();
            if (has) {
                list.setSelection(index);
                PromptLibrary.SavedPrompt p = prompts.get(index);
                nameText.setText(p.name == null ? "" : p.name);
                promptText.setText(p.text == null ? "" : p.text);
                sendCheck.setSelection(p.send);
            } else {
                nameText.setText("");
                promptText.setText("");
                sendCheck.setSelection(false);
            }
            nameText.setEnabled(has);
            promptText.setEnabled(has);
            sendCheck.setEnabled(has);
            deleteButton.setEnabled(has);
            upButton.setEnabled(has && index > 0);
            downButton.setEnabled(has && index < prompts.size() - 1);
        } finally {
            updating = false;
        }
    }

    @NotNull
    private static String displayName(@NotNull PromptLibrary.SavedPrompt p) {
        return p.name == null || p.name.isBlank() ? "?" : p.name;
    }

    @NotNull
    private static Button button(@NotNull Composite parent, @NotNull String text) {
        Button b = new Button(parent, SWT.PUSH);
        b.setText(text);
        b.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        return b;
    }
}
