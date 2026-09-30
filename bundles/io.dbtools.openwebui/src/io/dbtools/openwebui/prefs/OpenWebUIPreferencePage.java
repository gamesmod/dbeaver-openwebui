package io.dbtools.openwebui.prefs;

import io.dbtools.openwebui.Activator;
import io.dbtools.openwebui.ai.PromptFactory;
import io.dbtools.openwebui.api.ModelInfo;
import io.dbtools.openwebui.api.OpenWebUIClient;
import io.dbtools.openwebui.api.OpenWebUIException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import java.util.List;

/**
 * Окно → Параметры → Open WebUI.
 */
public class OpenWebUIPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private Text urlText;
    private Text apiKeyText;
    private Combo modelCombo;
    private Label statusLabel;
    private Text temperatureText;
    private Spinner timeoutSpinner;
    private Button streamCheck;
    private Button schemaCheck;
    private Spinner maxTablesSpinner;
    private Spinner maxColumnsSpinner;
    private Text knowledgeText;
    private Combo insertModeCombo;
    private Button syncHistoryCheck;
    private Text systemPromptText;

    private static final String[] INSERT_MODES = {
        Prefs.INSERT_NEW_LINE_BELOW, Prefs.INSERT_AT_CURSOR, Prefs.INSERT_REPLACE_SELECTION
    };
    private static final String[] INSERT_MODE_LABELS = {
        "Новой строкой после текущего запроса", "В позицию курсора", "Заменить выделение"
    };

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("Подключение DBeaver к Open WebUI (OpenAI-совместимый API /api/chat/completions).");
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        // --- Подключение
        Group conn = group(root, "Подключение", 3);
        label(conn, "Адрес сервера:");
        urlText = new Text(conn, SWT.BORDER);
        urlText.setLayoutData(span(2));
        urlText.setMessage("http://localhost:3000");

        label(conn, "API-ключ:");
        apiKeyText = new Text(conn, SWT.BORDER | SWT.PASSWORD);
        apiKeyText.setLayoutData(span(2));
        apiKeyText.setMessage("sk-… (Open WebUI → Settings → Account → API Keys)");

        label(conn, "Модель:");
        modelCombo = new Combo(conn, SWT.DROP_DOWN);
        modelCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        Button refresh = new Button(conn, SWT.PUSH);
        refresh.setText("Проверить и загрузить модели");
        refresh.addListener(SWT.Selection, e -> loadModels());

        new Label(conn, SWT.NONE);
        statusLabel = new Label(conn, SWT.WRAP);
        statusLabel.setLayoutData(span(2));

        // --- Генерация
        Group gen = group(root, "Генерация", 2);
        label(gen, "Температура (пусто — по умолчанию модели):");
        temperatureText = new Text(gen, SWT.BORDER);
        temperatureText.setLayoutData(new GridData(80, SWT.DEFAULT));
        label(gen, "Таймаут ответа, сек:");
        timeoutSpinner = spinner(gen, 5, 3600);
        streamCheck = check(gen, "Потоковый вывод ответа (SSE)");
        label(gen, "Вставка сгенерированного SQL:");
        insertModeCombo = new Combo(gen, SWT.READ_ONLY);
        insertModeCombo.setItems(INSERT_MODE_LABELS);

        // --- Контекст
        Group ctx = group(root, "Контекст базы данных", 2);
        schemaCheck = check(ctx, "Передавать модели структуру таблиц (имена, колонки, типы, PK/FK) — без данных");
        label(ctx, "Максимум таблиц в контексте:");
        maxTablesSpinner = spinner(ctx, 1, 2000);
        label(ctx, "Максимум колонок на таблицу:");
        maxColumnsSpinner = spinner(ctx, 1, 500);
        label(ctx, "ID коллекций знаний Open WebUI (через запятую):");
        knowledgeText = new Text(ctx, SWT.BORDER);
        knowledgeText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        knowledgeText.setMessage("необязательно: словарь данных, регламенты и т.п.");

        // --- История
        Group hist = group(root, "История чатов", 1);
        Label histInfo = new Label(hist, SWT.WRAP);
        histInfo.setText("Локально история сохраняется всегда (в рабочем пространстве DBeaver).");
        syncHistoryCheck = check(hist, "Также сохранять чаты в Open WebUI — они будут видны в веб-интерфейсе");

        // --- Системный промпт
        Group sp = group(root, "Системный промпт", 1);
        systemPromptText = new Text(sp, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData gd = new GridData(GridData.FILL_BOTH);
        gd.heightHint = 72;
        gd.widthHint = 420;
        systemPromptText.setLayoutData(gd);

        loadValues();
        return root;
    }

    private void loadValues() {
        IPreferenceStore s = getPreferenceStore();
        urlText.setText(s.getString(Prefs.BASE_URL));
        apiKeyText.setText(Prefs.getStoredApiKey());
        modelCombo.setText(s.getString(Prefs.MODEL));
        temperatureText.setText(s.getString(Prefs.TEMPERATURE));
        timeoutSpinner.setSelection(s.getInt(Prefs.TIMEOUT_SEC));
        streamCheck.setSelection(s.getBoolean(Prefs.STREAM));
        schemaCheck.setSelection(s.getBoolean(Prefs.INCLUDE_SCHEMA));
        maxTablesSpinner.setSelection(s.getInt(Prefs.MAX_TABLES));
        maxColumnsSpinner.setSelection(s.getInt(Prefs.MAX_COLUMNS));
        knowledgeText.setText(s.getString(Prefs.KNOWLEDGE_IDS));
        systemPromptText.setText(s.getString(Prefs.SYSTEM_PROMPT));
        syncHistoryCheck.setSelection(s.getBoolean(Prefs.SYNC_HISTORY));
        selectInsertMode(s.getString(Prefs.INSERT_MODE));
    }

    private void selectInsertMode(String mode) {
        int idx = 0;
        for (int i = 0; i < INSERT_MODES.length; i++) {
            if (INSERT_MODES[i].equals(mode)) {
                idx = i;
            }
        }
        insertModeCombo.select(idx);
    }

    @Override
    protected void performDefaults() {
        IPreferenceStore s = getPreferenceStore();
        urlText.setText(s.getDefaultString(Prefs.BASE_URL));
        modelCombo.setText(s.getDefaultString(Prefs.MODEL));
        temperatureText.setText(s.getDefaultString(Prefs.TEMPERATURE));
        timeoutSpinner.setSelection(s.getDefaultInt(Prefs.TIMEOUT_SEC));
        streamCheck.setSelection(s.getDefaultBoolean(Prefs.STREAM));
        schemaCheck.setSelection(s.getDefaultBoolean(Prefs.INCLUDE_SCHEMA));
        maxTablesSpinner.setSelection(s.getDefaultInt(Prefs.MAX_TABLES));
        maxColumnsSpinner.setSelection(s.getDefaultInt(Prefs.MAX_COLUMNS));
        knowledgeText.setText(s.getDefaultString(Prefs.KNOWLEDGE_IDS));
        systemPromptText.setText(PromptFactory.DEFAULT_SYSTEM_PROMPT);
        syncHistoryCheck.setSelection(s.getDefaultBoolean(Prefs.SYNC_HISTORY));
        selectInsertMode(s.getDefaultString(Prefs.INSERT_MODE));
        super.performDefaults();
    }

    @Override
    public boolean performOk() {
        String temp = temperatureText.getText().trim().replace(',', '.');
        if (!temp.isEmpty()) {
            try {
                double v = Double.parseDouble(temp);
                if (v < 0 || v > 2) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                setErrorMessage("Температура — число от 0 до 2 или пустое значение");
                return false;
            }
        }
        setErrorMessage(null);
        IPreferenceStore s = getPreferenceStore();
        s.setValue(Prefs.BASE_URL, urlText.getText().trim());
        s.setValue(Prefs.MODEL, selectedModelId());
        s.setValue(Prefs.TEMPERATURE, temp);
        s.setValue(Prefs.TIMEOUT_SEC, timeoutSpinner.getSelection());
        s.setValue(Prefs.STREAM, streamCheck.getSelection());
        s.setValue(Prefs.INCLUDE_SCHEMA, schemaCheck.getSelection());
        s.setValue(Prefs.MAX_TABLES, maxTablesSpinner.getSelection());
        s.setValue(Prefs.MAX_COLUMNS, maxColumnsSpinner.getSelection());
        s.setValue(Prefs.KNOWLEDGE_IDS, knowledgeText.getText().trim());
        s.setValue(Prefs.SYSTEM_PROMPT, systemPromptText.getText());
        s.setValue(Prefs.SYNC_HISTORY, syncHistoryCheck.getSelection());
        s.setValue(Prefs.INSERT_MODE, INSERT_MODES[Math.max(0, insertModeCombo.getSelectionIndex())]);
        Prefs.setApiKey(apiKeyText.getText());
        return super.performOk();
    }

    /** В комбобоксе показываем «Имя (id)», а сохраняем только id. */
    private String selectedModelId() {
        String text = modelCombo.getText().trim();
        Object data = modelCombo.getData();
        if (data instanceof List<?> models) {
            for (Object o : models) {
                if (o instanceof ModelInfo mi && mi.toString().equals(text)) {
                    return mi.id();
                }
            }
        }
        return text;
    }

    private void loadModels() {
        String url = urlText.getText().trim();
        String key = apiKeyText.getText().trim();
        if (key.isEmpty()) {
            key = Prefs.getApiKey(); // может прийти из OPENWEBUI_API_KEY
        }
        String current = selectedModelId();
        statusLabel.setText("Подключение…");
        statusLabel.getParent().layout();

        OpenWebUIClient client = new OpenWebUIClient(Prefs.clientSettings(url, key, current));
        Display display = statusLabel.getDisplay();
        Job job = new Job("Open WebUI: загрузка списка моделей") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                List<ModelInfo> models;
                String error = null;
                try {
                    models = client.listModels();
                } catch (OpenWebUIException e) {
                    models = List.of();
                    error = e.getMessage();
                }
                List<ModelInfo> result = models;
                String err = error;
                if (display.isDisposed()) {
                    return Status.OK_STATUS;
                }
                display.asyncExec(() -> {
                    if (statusLabel.isDisposed()) {
                        return;
                    }
                    if (err != null) {
                        statusLabel.setText("✖ " + err);
                    } else {
                        modelCombo.setData(result);
                        modelCombo.setItems(result.stream().map(ModelInfo::toString).toArray(String[]::new));
                        ModelInfo keep = result.stream().filter(m -> m.id().equals(current)).findFirst()
                            .orElse(result.isEmpty() ? null : result.get(0));
                        if (keep != null) {
                            modelCombo.setText(keep.toString());
                        }
                        statusLabel.setText("✔ Подключено. Доступно моделей: " + result.size());
                    }
                    statusLabel.getParent().layout();
                });
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        job.schedule();
    }

    // ---------------------------------------------------------------- layout helpers

    private static Group group(Composite parent, String title, int columns) {
        Group g = new Group(parent, SWT.NONE);
        g.setText(title);
        g.setLayout(new GridLayout(columns, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        return g;
    }

    private static void label(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
    }

    private static GridData span(int columns) {
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        gd.horizontalSpan = columns;
        gd.widthHint = 300;
        return gd;
    }

    private static Spinner spinner(Composite parent, int min, int max) {
        Spinner sp = new Spinner(parent, SWT.BORDER);
        sp.setMinimum(min);
        sp.setMaximum(max);
        return sp;
    }

    private static Button check(Composite parent, String text) {
        Button b = new Button(parent, SWT.CHECK);
        b.setText(text);
        GridData gd = new GridData();
        gd.horizontalSpan = ((GridLayout) parent.getLayout()).numColumns;
        b.setLayoutData(gd);
        return b;
    }
}
