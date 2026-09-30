package io.dbtools.openwebui.ui;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Диалог «Сгенерировать SQL»: задача на естественном языке.
 */
public class GenerateSqlDialog extends TitleAreaDialog {

    /** Последняя задача — чтобы её можно было поправить и отправить повторно. */
    private static String lastRequest = "";

    private final String contextNote;
    private final boolean hasSelection;
    private Text requestText;
    private Button useSelectionCheck;

    private String request;
    private boolean useSelection;

    /**
     * @param contextNote  подсказка о том, какой контекст БД получит модель
     */
    public GenerateSqlDialog(Shell parent, String contextNote, boolean hasSelection) {
        super(parent);
        this.contextNote = contextNote;
        this.hasSelection = hasSelection;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Open WebUI: генерация SQL");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        setTitle("Опишите, какие данные нужны");
        setMessage(contextNote);

        Composite c = new Composite(area, SWT.NONE);
        c.setLayout(new GridLayout(1, false));
        c.setLayoutData(new GridData(GridData.FILL_BOTH));

        requestText = new Text(c, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData gd = new GridData(GridData.FILL_BOTH);
        gd.heightHint = 120;
        gd.widthHint = 520;
        requestText.setLayoutData(gd);
        requestText.setMessage("Например: топ-10 клиентов по сумме заказов за прошлый месяц с количеством заказов");
        requestText.setText(lastRequest);
        requestText.selectAll();
        requestText.addListener(SWT.KeyDown, e -> {
            if ((e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) && (e.stateMask & SWT.MOD1) != 0) {
                e.doit = false;
                okPressed();
            }
        });

        useSelectionCheck = new Button(c, SWT.CHECK);
        useSelectionCheck.setText("Доработать выделенный запрос (передать его модели)");
        useSelectionCheck.setSelection(hasSelection);
        useSelectionCheck.setEnabled(hasSelection);
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Сгенерировать", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void okPressed() {
        String text = requestText.getText().strip();
        if (text.isEmpty()) {
            setErrorMessage("Опишите задачу");
            return;
        }
        request = text;
        lastRequest = text;
        useSelection = useSelectionCheck.getSelection();
        super.okPressed();
    }

    public String getRequest() {
        return request;
    }

    public boolean isUseSelection() {
        return useSelection;
    }
}
