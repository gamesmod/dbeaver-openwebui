package io.dbtools.openwebui.ui;

import io.dbtools.openwebui.Activator;
import io.dbtools.openwebui.ai.PromptFactory;
import io.dbtools.openwebui.ai.ResponseParser;
import io.dbtools.openwebui.api.ChatHistoryApi;
import io.dbtools.openwebui.api.ChatMessage;
import io.dbtools.openwebui.api.ModelInfo;
import io.dbtools.openwebui.api.OpenWebUIClient;
import io.dbtools.openwebui.api.OpenWebUIException;
import io.dbtools.openwebui.api.StreamListener;
import io.dbtools.openwebui.context.ConnectionCatalog;
import io.dbtools.openwebui.context.DbContextResolver;
import io.dbtools.openwebui.context.EditorInserter;
import io.dbtools.openwebui.context.SqlEditorContext;
import io.dbtools.openwebui.history.ChatSession;
import io.dbtools.openwebui.history.ChatStore;
import io.dbtools.openwebui.prefs.Prefs;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPDataSourceContainerProvider;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DefaultProgressMonitor;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Панель чата с моделью Open WebUI.
 * <ul>
 *     <li>выбор модели, подключения и схемы, чья структура уходит модели;</li>
 *     <li>история диалогов: локально всегда, в Open WebUI — по настройке;</li>
 *     <li>вывод результатов команд «Объяснить / Оптимизировать / Найти ошибки» с продолжением диалога.</li>
 * </ul>
 */
public class ChatView extends ViewPart {

    public static final String ID = "io.dbtools.openwebui.views.chat";
    private static final String NAVIGATOR_VIEW_ID = "org.jkiss.dbeaver.core.databaseNavigator";

    private static final Pattern CODE_BLOCK = Pattern.compile("```[a-zA-Z0-9_+-]*[ \\t]*\\r?\\n.*?```", Pattern.DOTALL);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm", Locale.ROOT)
        .withZone(ZoneId.systemDefault());
    private static final String AUTO_CONNECTION = "Авто — подключение активного SQL-редактора";
    private static final String DEFAULT_SCHEMA = "Схема по умолчанию";

    /** Сборщик сообщений для запроса; вызывается в фоновом потоке (может читать метаданные БД). */
    @FunctionalInterface
    public interface PromptBuilder {
        /**
         * @param note получатель пояснений для пользователя (какой контекст БД передан и т.п.)
         */
        List<ChatMessage> build(IProgressMonitor monitor, Consumer<String> note) throws Exception;
    }

    /** Строка списка истории: локальная и/или в Open WebUI. */
    private record HistoryRow(String localId, String remoteId, String title, long updatedMs, String connection) {
        String where() {
            if (localId != null && remoteId != null) {
                return "оба";
            }
            return localId != null ? "локально" : "Open WebUI";
        }
    }

    // --- виджеты
    private Combo modelCombo;
    private Button dbContextCheck;
    private Button historyToggle;
    private Combo connectionCombo;
    private Combo schemaCombo;
    private SashForm mainSash;
    private Composite historyPanel;
    private Text historyFilter;
    private Table historyTable;
    private Button stopButton;
    private Button insertButton;
    private StyledText history;
    private Text input;
    private Label status;

    // --- состояние
    private final List<String> connectionIds = new ArrayList<>();
    private List<HistoryRow> historyRows = new ArrayList<>();
    private ChatStore store;
    private ChatSession session = ChatSession.create();
    private String lastAnswer;
    private SqlEditorContext lastEditorContext;
    private Job runningJob;

    // ================================================================= UI

    @Override
    public void createPartControl(Composite parent) {
        store = new ChatStore(stateDir());

        Composite root = new Composite(parent, SWT.NONE);
        GridLayout rl = new GridLayout(1, false);
        rl.marginWidth = 4;
        rl.marginHeight = 4;
        root.setLayout(rl);

        createModelRow(root);
        createConnectionRow(root);

        mainSash = new SashForm(root, SWT.HORIZONTAL);
        mainSash.setLayoutData(new GridData(GridData.FILL_BOTH));
        createHistoryPanel(mainSash);
        createChatArea(mainSash);
        mainSash.setWeights(34, 66);
        mainSash.setMaximizedControl(mainSash.getChildren()[1]);

        refreshConnections();
        appendSystemNote(Prefs.isConfigured()
            ? "Готово. Задайте вопрос или используйте команды Open WebUI в контекстном меню SQL-редактора."
            : "Плагин не настроен: укажите адрес Open WebUI, API-ключ и модель в Окно → Параметры → Open WebUI.");
    }

    private void createModelRow(Composite root) {
        Composite top = row(root, 6);
        new Label(top, SWT.NONE).setText("Модель:");
        modelCombo = new Combo(top, SWT.DROP_DOWN);
        GridData cgd = new GridData(GridData.FILL_HORIZONTAL);
        cgd.widthHint = 160;
        modelCombo.setLayoutData(cgd);
        modelCombo.setText(Prefs.store().getString(Prefs.MODEL));
        modelCombo.setToolTipText("Модель для этого чата. По умолчанию — из настроек.");

        Button reloadModels = new Button(top, SWT.PUSH);
        reloadModels.setText("↻");
        reloadModels.setToolTipText("Обновить список моделей");
        reloadModels.addListener(SWT.Selection, e -> loadModels());

        dbContextCheck = new Button(top, SWT.TOGGLE);
        dbContextCheck.setToolTipText("Передавать модели структуру таблиц выбранного подключения. Данные таблиц не передаются.");
        dbContextCheck.setSelection(Prefs.includeSchema());
        updateContextToggle();
        dbContextCheck.addListener(SWT.Selection, e -> {
            updateContextToggle();
            updateConnectionRowEnabled();
        });

        historyToggle = new Button(top, SWT.TOGGLE);
        historyToggle.setText("История");
        historyToggle.setToolTipText("Показать сохранённые чаты");
        historyToggle.addListener(SWT.Selection, e -> toggleHistory(historyToggle.getSelection()));

        Button clear = new Button(top, SWT.PUSH);
        clear.setText("Новый чат");
        clear.addListener(SWT.Selection, e -> newChat(true));
    }

    private void createConnectionRow(Composite root) {
        Composite row = row(root, 5);
        new Label(row, SWT.NONE).setText("Подключение:");
        connectionCombo = new Combo(row, SWT.READ_ONLY);
        GridData cgd = new GridData(GridData.FILL_HORIZONTAL);
        cgd.widthHint = 200;
        connectionCombo.setLayoutData(cgd);
        connectionCombo.setToolTipText("Чью структуру БД получит модель. «Авто» — подключение активного SQL-редактора "
            + "(или выделенное в навигаторе).");
        connectionCombo.addListener(SWT.Selection, e -> onConnectionSelected(true));

        new Label(row, SWT.NONE).setText("Схема:");
        schemaCombo = new Combo(row, SWT.READ_ONLY);
        GridData sgd = new GridData(GridData.FILL_HORIZONTAL);
        sgd.widthHint = 150;
        schemaCombo.setLayoutData(sgd);
        schemaCombo.setItems(DEFAULT_SCHEMA);
        schemaCombo.select(0);
        schemaCombo.addListener(SWT.Selection, e -> {
            Prefs.store().setValue(Prefs.CHAT_SCHEMA, schemaCombo.getSelectionIndex() <= 0 ? "" : schemaCombo.getText());
            Prefs.flush();
        });

        Button refresh = new Button(row, SWT.PUSH);
        refresh.setText("↻");
        refresh.setToolTipText("Обновить список подключений и схем");
        refresh.addListener(SWT.Selection, e -> {
            refreshConnections();
            onConnectionSelected(false);
        });
    }

    private void createHistoryPanel(Composite parent) {
        historyPanel = new Composite(parent, SWT.NONE);
        GridLayout hl = new GridLayout(3, false);
        hl.marginWidth = 0;
        hl.marginHeight = 0;
        historyPanel.setLayout(hl);

        historyFilter = new Text(historyPanel, SWT.BORDER | SWT.SEARCH | SWT.ICON_CANCEL);
        GridData fgd = new GridData(GridData.FILL_HORIZONTAL);
        fgd.horizontalSpan = 3;
        historyFilter.setLayoutData(fgd);
        historyFilter.setMessage("Поиск по названию");
        historyFilter.addListener(SWT.Modify, e -> fillHistoryTable());

        historyTable = new Table(historyPanel, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        historyTable.setHeaderVisible(true);
        GridData tgd = new GridData(GridData.FILL_BOTH);
        tgd.horizontalSpan = 3;
        historyTable.setLayoutData(tgd);
        column(historyTable, "Чат", 160);
        column(historyTable, "Изменён", 112);
        column(historyTable, "Где", 84);
        // Колонка «Чат» забирает всё свободное место (пересчёт после раскладки, иначе ширина ещё нулевая).
        historyTable.addListener(SWT.Resize, e -> historyTable.getDisplay().asyncExec(this::fitHistoryColumns));
        historyTable.addListener(SWT.DefaultSelection, e -> openSelectedHistory());

        Button open = new Button(historyPanel, SWT.PUSH);
        open.setText("Открыть");
        open.addListener(SWT.Selection, e -> openSelectedHistory());
        Button delete = new Button(historyPanel, SWT.PUSH);
        delete.setText("Удалить");
        delete.addListener(SWT.Selection, e -> deleteSelectedHistory());
        Button reload = new Button(historyPanel, SWT.PUSH);
        reload.setText("↻");
        reload.setToolTipText("Обновить список (включая чаты Open WebUI, если синхронизация включена)");
        reload.addListener(SWT.Selection, e -> reloadHistory());
    }

    private void createChatArea(Composite parent) {
        SashForm sash = new SashForm(parent, SWT.VERTICAL);

        history = new StyledText(sash, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.WRAP | SWT.READ_ONLY);
        history.setMargins(6, 6, 6, 6);

        Composite bottom = new Composite(sash, SWT.NONE);
        GridLayout bl = new GridLayout(4, false);
        bl.marginWidth = 0;
        bl.marginHeight = 0;
        bottom.setLayout(bl);

        input = new Text(bottom, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData igd = new GridData(GridData.FILL_BOTH);
        igd.horizontalSpan = 4;
        input.setLayoutData(igd);
        input.setMessage("Вопрос модели… (Ctrl+Enter — отправить)");
        input.addListener(SWT.KeyDown, e -> {
            if ((e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) && (e.stateMask & SWT.MOD1) != 0) {
                e.doit = false;
                sendFromInput();
            }
        });

        status = new Label(bottom, SWT.NONE);
        status.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        insertButton = new Button(bottom, SWT.PUSH);
        insertButton.setText("Вставить SQL в редактор");
        insertButton.setEnabled(false);
        insertButton.addListener(SWT.Selection, e -> insertLastSql());

        stopButton = new Button(bottom, SWT.PUSH);
        stopButton.setText("Стоп");
        stopButton.setEnabled(false);
        stopButton.addListener(SWT.Selection, e -> {
            if (runningJob != null) {
                runningJob.cancel();
            }
        });

        Button send = new Button(bottom, SWT.PUSH);
        send.setText("Отправить");
        send.addListener(SWT.Selection, e -> sendFromInput());

        sash.setWeights(75, 25);
    }

    @Override
    public void setFocus() {
        if (input != null && !input.isDisposed()) {
            input.setFocus();
        }
    }

    @Override
    public void dispose() {
        if (runningJob != null) {
            runningJob.cancel();
        }
        super.dispose();
    }

    // ================================================================= публичный API для команд

    /** Открывает (или активирует) панель чата. Вызывать из UI-потока. */
    public static ChatView open() {
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        try {
            return (ChatView) page.showView(ID);
        } catch (PartInitException e) {
            Activator.logError("Не удалось открыть панель Open WebUI", e);
            return null;
        }
    }

    /**
     * Начинает новый чат по команде редактора: сообщения строятся в фоне, ответ потоково выводится в панель.
     *
     * @param title          заголовок, который увидит пользователь вместо полного промпта
     * @param shownQuery     SQL, который показать под заголовком (может быть null)
     * @param editorContext  редактор, в который потом можно вставить SQL из ответа
     * @param builder        сборщик сообщений (system + user)
     */
    public void startTask(String title, String shownQuery, SqlEditorContext editorContext, PromptBuilder builder) {
        if (isBusy()) {
            appendSystemNote("Дождитесь окончания текущего ответа или нажмите «Стоп».");
            return;
        }
        newChat(false);
        lastEditorContext = editorContext;
        ChatSession target = session;
        target.connection = editorContext == null ? null : editorContext.connectionName();
        String display = shownQuery == null || shownQuery.isBlank() ? title : title + "\n" + shownQuery.strip();
        appendHeader("Вы", title);
        if (shownQuery != null && !shownQuery.isBlank()) {
            appendCode(shownQuery.strip());
        }
        runRequest(target, (monitor, note) -> {
            List<ChatMessage> messages = builder.build(monitor, note);
            target.replaceWith(messages, display);
            return target.toRequest();
        });
    }

    /**
     * Показывает уже полученный ответ (например, когда генерация SQL вернула текст без запроса)
     * и делает его текущим чатом, чтобы можно было задать уточняющий вопрос.
     */
    public void showExchange(String title, List<ChatMessage> messages, String answer, SqlEditorContext editorContext) {
        if (isBusy()) {
            return;
        }
        newChat(false);
        lastEditorContext = editorContext;
        session.connection = editorContext == null ? null : editorContext.connectionName();
        session.replaceWith(messages, title);
        session.addAssistant(answer);
        session.model = selectedModel();
        appendHeader("Вы", title);
        appendHeader("Модель", null);
        int start = history.getCharCount();
        history.append(answer);
        finishAnswer(start, answer, false);
        persistAsync(session);
    }

    // ================================================================= свободный чат

    private void sendFromInput() {
        String text = input.getText().strip();
        if (text.isEmpty() || isBusy()) {
            return;
        }
        input.setText("");
        appendHeader("Вы", text);

        boolean withDb = dbContextCheck.getSelection();
        SqlEditorContext editorCtx = SqlEditorContext.from(activeEditor());
        if (editorCtx != null) {
            lastEditorContext = editorCtx;
        }

        // Явно выбранное подключение важнее редактора; «Авто» — редактор, затем навигатор.
        DBPDataSourceContainer chosen = selectedConnection();
        DBPDataSourceContainer target;
        Supplier<DBCExecutionContext> execSupplier = null;
        if (chosen != null) {
            target = chosen;
            if (editorCtx != null && editorCtx.container() == chosen) {
                execSupplier = () -> editorCtx.editor().getExecutionContext();
            }
        } else if (editorCtx != null && editorCtx.container() != null) {
            target = editorCtx.container();
            execSupplier = () -> editorCtx.editor().getExecutionContext();
        } else {
            target = navigatorSelection();
        }
        Supplier<DBCExecutionContext> exec = execSupplier;
        String schemaPath = chosen != null && schemaCombo.getSelectionIndex() > 0 ? schemaCombo.getText() : null;
        String query = editorCtx == null ? null : editorCtx.queryText();
        ChatSession current = session;
        boolean firstMessage = !current.hasUserMessages();
        if (target != null) {
            current.connection = target.getName() + (schemaPath != null ? " / " + schemaPath : "");
        }

        runRequest(current, (monitor, note) -> {
            // Контекст пересобирается на каждое сообщение: пользователь мог сменить подключение или схему.
            if (withDb) {
                if (target == null) {
                    note.accept("Контекст БД не передан: выберите подключение вверху панели, "
                        + "откройте SQL-редактор или выделите подключение в навигаторе");
                } else {
                    DbContextResolver.Resolved r = DbContextResolver.resolve(monitor, target, exec, schemaPath,
                        true, text, query);
                    note.accept(r.note());
                    current.setSystem(PromptFactory.chatSystem(Prefs.systemPrompt(), r.db()).content());
                }
            } else if (firstMessage) {
                note.accept("Контекст БД выключен — включите кнопку «Контекст БД» вверху панели");
            }
            if (current.isEmpty()) {
                current.setSystem(PromptFactory.chatSystem(Prefs.systemPrompt(), null).content());
            }
            current.addUser(text, null);
            return current.toRequest();
        });
    }

    /** Подключение, выделенное в навигаторе баз данных, если SQL-редактор не активен. */
    private DBPDataSourceContainer navigatorSelection() {
        try {
            ISelection sel = getSite().getPage().getSelection(NAVIGATOR_VIEW_ID);
            if (sel instanceof IStructuredSelection ss && !ss.isEmpty()) {
                Object o = ss.getFirstElement();
                if (o instanceof DBPDataSourceContainerProvider p) {
                    return p.getDataSourceContainer();
                }
                return DBUtils.getAdapter(DBPDataSourceContainer.class, o);
            }
        } catch (RuntimeException e) {
            Activator.logError("Не удалось прочитать выделение навигатора", e);
        }
        return null;
    }

    // ================================================================= подключение и схема

    private void refreshConnections() {
        String keep = connectionIds.isEmpty()
            ? Prefs.store().getString(Prefs.CHAT_CONNECTION)
            : (connectionCombo.getSelectionIndex() > 0 ? connectionIds.get(connectionCombo.getSelectionIndex() - 1) : "");
        List<DBPDataSourceContainer> list = ConnectionCatalog.connections();
        connectionIds.clear();
        List<String> names = new ArrayList<>();
        names.add(AUTO_CONNECTION);
        int select = 0;
        for (DBPDataSourceContainer c : list) {
            connectionIds.add(c.getId());
            names.add(c.getName());
            if (c.getId().equals(keep)) {
                select = names.size() - 1;
            }
        }
        connectionCombo.setItems(names.toArray(String[]::new));
        connectionCombo.select(select);
        if (select > 0 && schemaCombo.getItemCount() <= 1) {
            onConnectionSelected(false);
        }
        updateConnectionRowEnabled();
    }

    private DBPDataSourceContainer selectedConnection() {
        int idx = connectionCombo.getSelectionIndex();
        if (idx <= 0 || idx - 1 >= connectionIds.size()) {
            return null;
        }
        return ConnectionCatalog.byId(connectionIds.get(idx - 1));
    }

    private void onConnectionSelected(boolean userAction) {
        DBPDataSourceContainer c = selectedConnection();
        Prefs.store().setValue(Prefs.CHAT_CONNECTION, c == null ? "" : c.getId());
        if (userAction) {
            Prefs.store().setValue(Prefs.CHAT_SCHEMA, "");
        }
        Prefs.flush();
        schemaCombo.setItems(DEFAULT_SCHEMA);
        schemaCombo.select(0);
        updateConnectionRowEnabled();
        if (c == null) {
            return;
        }
        String wanted = Prefs.store().getString(Prefs.CHAT_SCHEMA);
        Display display = schemaCombo.getDisplay();
        status.setText("Загружаю схемы «" + c.getName() + "»…");
        Job job = new Job("Open WebUI: схемы подключения " + c.getName()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                List<String> paths = new ArrayList<>();
                String error = null;
                try {
                    if (!c.isConnected() || c.getDataSource() == null) {
                        c.connect(new DefaultProgressMonitor(monitor), true, true);
                    }
                    if (c.getDataSource() != null) {
                        paths = ConnectionCatalog.schemaPaths(new DefaultProgressMonitor(monitor), c.getDataSource());
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                List<String> result = paths;
                String err = error;
                ui(display, () -> {
                    if (schemaCombo.isDisposed() || selectedConnection() != c) {
                        return;
                    }
                    List<String> items = new ArrayList<>();
                    items.add(DEFAULT_SCHEMA);
                    items.addAll(result);
                    schemaCombo.setItems(items.toArray(String[]::new));
                    int idx = wanted.isEmpty() ? 0 : Math.max(0, items.indexOf(wanted));
                    schemaCombo.select(idx);
                    status.setText(err != null ? "Не удалось получить схемы «" + c.getName() + "»: " + err
                        : result.isEmpty() ? "" : "Схем: " + result.size());
                });
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        job.schedule();
    }

    private void updateConnectionRowEnabled() {
        boolean on = dbContextCheck.getSelection();
        connectionCombo.setEnabled(on);
        schemaCombo.setEnabled(on && connectionCombo.getSelectionIndex() > 0);
    }

    private void updateContextToggle() {
        dbContextCheck.setText(dbContextCheck.getSelection() ? "Контекст БД: вкл" : "Контекст БД: выкл");
        dbContextCheck.getParent().layout();
    }

    // ================================================================= выполнение запроса

    private void runRequest(ChatSession target, PromptBuilder builder) {
        if (!Prefs.isConfigured()) {
            appendSystemNote("Сначала настройте подключение: Окно → Параметры → Open WebUI.");
            return;
        }
        String model = selectedModel();
        OpenWebUIClient client = Prefs.client();
        boolean stream = Prefs.useStreaming();
        Display display = history.getDisplay();

        int[] answerStart = {history.getCharCount()};
        setBusy(true, "Готовлю запрос…");

        Job job = new Job("Open WebUI: запрос к модели") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                StreamBuffer buffer = new StreamBuffer(display);
                try {
                    List<ChatMessage> messages = builder.build(monitor,
                        note -> syncUi(display, () -> appendSystemNote(note)));
                    syncUi(display, () -> {
                        appendHeader("Модель", null);
                        answerStart[0] = history.getCharCount();
                    });
                    if (monitor.isCanceled()) {
                        target.removeTrailingUser();
                        ui(display, () -> setBusy(false, "Остановлено пользователем"));
                        return Status.CANCEL_STATUS;
                    }
                    ui(display, () -> status.setText("Модель отвечает…"));
                    String answer;
                    if (stream) {
                        answer = client.chatStream(messages, model, new StreamListener() {
                            @Override
                            public void onDelta(String text) {
                                buffer.add(text);
                            }

                            @Override
                            public boolean isCancelled() {
                                return monitor.isCanceled();
                            }
                        });
                    } else {
                        answer = client.chat(messages, model);
                        buffer.add(answer);
                    }
                    buffer.flushNow();
                    target.addAssistant(answer);
                    if (model != null) {
                        target.model = model;
                    }
                    ui(display, () -> finishAnswer(answerStart[0], answer, monitor.isCanceled()));
                    persist(target, client, note -> ui(display, () -> appendSystemNote(note)));
                } catch (OpenWebUIException e) {
                    buffer.flushNow();
                    target.removeTrailingUser();
                    ui(display, () -> {
                        appendError(e.getMessage());
                        setBusy(false, null);
                    });
                } catch (Exception e) {
                    buffer.flushNow();
                    target.removeTrailingUser();
                    Activator.logError("Ошибка запроса к Open WebUI", e);
                    ui(display, () -> {
                        appendError(e.getClass().getSimpleName() + ": " + e.getMessage());
                        setBusy(false, null);
                    });
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        runningJob = job;
        job.schedule();
    }

    // ================================================================= история

    /** Сохраняет чат локально и, если включено, в Open WebUI. Вызывается в фоне. */
    private void persist(ChatSession s, OpenWebUIClient client, Consumer<String> note) {
        try {
            store.save(s);
        } catch (Exception e) {
            Activator.logError("Не удалось сохранить чат локально", e);
            note.accept("Не удалось сохранить чат в историю: " + e.getMessage());
        }
        if (!Prefs.syncHistory()) {
            return;
        }
        try {
            String remoteId = new ChatHistoryApi(client).save(s.remoteId, s.id, s.title, s.model, s.toRequest(),
                s.remoteMessageIds());
            if (!remoteId.equals(s.remoteId)) {
                s.remoteId = remoteId;
                store.save(s);
            }
        } catch (Exception e) {
            Activator.logError("Не удалось сохранить чат в Open WebUI", e);
            note.accept("Чат сохранён локально, но не в Open WebUI: " + e.getMessage());
        }
        Display display = PlatformUI.getWorkbench().getDisplay();
        ui(display, () -> {
            if (historyToggle != null && !historyToggle.isDisposed() && historyToggle.getSelection()) {
                reloadHistory();
            }
        });
    }

    private void persistAsync(ChatSession s) {
        OpenWebUIClient client = Prefs.client();
        Display display = history.getDisplay();
        Job job = new Job("Open WebUI: сохранение чата") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                persist(s, client, note -> ui(display, () -> appendSystemNote(note)));
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    private void toggleHistory(boolean show) {
        historyToggle.setSelection(show);
        mainSash.setMaximizedControl(show ? null : mainSash.getChildren()[1]);
        if (show) {
            reloadHistory();
            mainSash.layout(true, true);
            historyTable.getDisplay().asyncExec(this::fitHistoryColumns);
        }
    }

    /** Локальный список сразу, чаты Open WebUI — в фоне, если синхронизация включена. */
    private void reloadHistory() {
        List<HistoryRow> rows = new ArrayList<>();
        Set<String> knownRemote = new HashSet<>();
        for (ChatStore.Summary s : store.list()) {
            rows.add(new HistoryRow(s.id(), s.remoteId(), s.title(), s.updatedAt(), s.connection()));
            if (s.remoteId() != null) {
                knownRemote.add(s.remoteId());
            }
        }
        historyRows = rows;
        fillHistoryTable();
        if (!Prefs.syncHistory() || !Prefs.isConfigured()) {
            return;
        }
        OpenWebUIClient client = Prefs.client();
        Display display = historyTable.getDisplay();
        Job job = new Job("Open WebUI: список чатов") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    List<ChatHistoryApi.RemoteChat> remote = new ChatHistoryApi(client).list(1);
                    ui(display, () -> {
                        if (historyTable.isDisposed()) {
                            return;
                        }
                        List<HistoryRow> merged = new ArrayList<>(historyRows);
                        for (ChatHistoryApi.RemoteChat r : remote) {
                            if (!knownRemote.contains(r.id())) {
                                merged.add(new HistoryRow(null, r.id(), r.title(), r.updatedAt() * 1000, null));
                            }
                        }
                        merged.sort((a, b) -> Long.compare(b.updatedMs(), a.updatedMs()));
                        historyRows = merged;
                        fillHistoryTable();
                    });
                } catch (OpenWebUIException e) {
                    ui(display, () -> status.setText("История Open WebUI недоступна: " + e.getMessage()));
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        job.schedule();
    }

    private void fitHistoryColumns() {
        if (historyTable == null || historyTable.isDisposed()) {
            return;
        }
        int width = historyTable.getClientArea().width;
        if (width <= 0) {
            return;
        }
        int fixed = 112 + 84;
        historyTable.getColumn(1).setWidth(112);
        historyTable.getColumn(2).setWidth(84);
        historyTable.getColumn(0).setWidth(Math.max(100, width - fixed - 2));
    }

    private void fillHistoryTable() {
        if (historyTable == null || historyTable.isDisposed()) {
            return;
        }
        String filter = historyFilter.getText().strip().toLowerCase(Locale.ROOT);
        historyTable.removeAll();
        for (HistoryRow r : historyRows) {
            String title = r.title() == null ? "" : r.title();
            String haystack = (title + " " + (r.connection() == null ? "" : r.connection())).toLowerCase(Locale.ROOT);
            if (!filter.isEmpty() && !haystack.contains(filter)) {
                continue;
            }
            TableItem item = new TableItem(historyTable, SWT.NONE);
            item.setText(0, title);
            item.setText(1, r.updatedMs() > 0 ? WHEN.format(Instant.ofEpochMilli(r.updatedMs())) : "");
            item.setText(2, r.where());
            item.setData(r);
        }
    }

    private HistoryRow selectedHistoryRow() {
        TableItem[] sel = historyTable.getSelection();
        return sel.length == 0 ? null : (HistoryRow) sel[0].getData();
    }

    private void openSelectedHistory() {
        HistoryRow row = selectedHistoryRow();
        if (row == null || isBusy()) {
            return;
        }
        if (row.localId() != null) {
            try {
                showSession(store.load(row.localId()));
            } catch (Exception e) {
                appendError("Не удалось открыть чат: " + e.getMessage());
            }
            return;
        }
        OpenWebUIClient client = Prefs.client();
        Display display = history.getDisplay();
        status.setText("Загружаю чат из Open WebUI…");
        Job job = new Job("Open WebUI: загрузка чата") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    ChatHistoryApi.RemoteChatContent c = new ChatHistoryApi(client).get(row.remoteId());
                    ChatSession s = ChatSession.fromRemote(row.remoteId(), c.title(), c.model(), c.messages(), c.messageIds());
                    store.save(s);
                    ui(display, () -> {
                        showSession(s);
                        reloadHistory();
                    });
                } catch (Exception e) {
                    ui(display, () -> status.setText("Не удалось загрузить чат: " + e.getMessage()));
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        job.schedule();
    }

    private void deleteSelectedHistory() {
        HistoryRow row = selectedHistoryRow();
        if (row == null) {
            return;
        }
        boolean remoteToo = false;
        if (row.remoteId() != null && Prefs.syncHistory()) {
            if (row.localId() == null) {
                if (!MessageDialog.openConfirm(getSite().getShell(), "Удаление чата",
                    "Удалить чат «" + row.title() + "» из Open WebUI?")) {
                    return;
                }
                remoteToo = true;
            } else {
                int choice = new MessageDialog(getSite().getShell(), "Удаление чата", null,
                    "Чат «" + row.title() + "» есть и здесь, и в Open WebUI. Откуда удалить?",
                    MessageDialog.QUESTION, 0, "Только здесь", "Здесь и в Open WebUI", "Отмена").open();
                if (choice == 2 || choice < 0) {
                    return;
                }
                remoteToo = choice == 1;
            }
        } else if (!MessageDialog.openConfirm(getSite().getShell(), "Удаление чата",
            "Удалить чат «" + row.title() + "» из истории?")) {
            return;
        }
        try {
            if (row.localId() != null) {
                store.delete(row.localId());
                if (row.localId().equals(session.id)) {
                    newChat(true);
                }
            }
        } catch (Exception e) {
            appendError("Не удалось удалить чат: " + e.getMessage());
        }
        if (remoteToo) {
            OpenWebUIClient client = Prefs.client();
            Display display = history.getDisplay();
            Job job = new Job("Open WebUI: удаление чата") {
                @Override
                protected IStatus run(IProgressMonitor monitor) {
                    try {
                        new ChatHistoryApi(client).delete(row.remoteId());
                    } catch (OpenWebUIException e) {
                        ui(display, () -> status.setText("Не удалось удалить в Open WebUI: " + e.getMessage()));
                    }
                    ui(display, () -> reloadHistory());
                    return Status.OK_STATUS;
                }
            };
            job.schedule();
        } else {
            reloadHistory();
        }
    }

    /** Показывает сохранённый чат и делает его текущим: следующий вопрос продолжит этот диалог. */
    private void showSession(ChatSession s) {
        if (runningJob != null) {
            runningJob.cancel();
        }
        session = s;
        lastAnswer = null;
        history.setText("");
        if (s.model != null && !s.model.isBlank()) {
            modelCombo.setText(s.model);
        }
        appendSystemNote("Чат из истории: " + (s.title == null ? "без названия" : s.title)
            + (s.connection != null ? " · " + s.connection : ""));
        for (ChatSession.Entry e : s.entries()) {
            if ("user".equals(e.role)) {
                appendHeader("Вы", e.shown());
            } else if ("assistant".equals(e.role)) {
                appendHeader("Модель", null);
                int start = history.getCharCount();
                history.append(e.content == null ? "" : e.content);
                history.append("\n");
                styleCodeBlocks(start);
                lastAnswer = e.content;
            }
        }
        insertButton.setEnabled(lastAnswer != null && ResponseParser.extractSql(lastAnswer) != null);
        setBusy(false, null);
        scrollToEnd();
    }

    private void newChat(boolean clearView) {
        if (runningJob != null) {
            runningJob.cancel();
        }
        session = ChatSession.create();
        lastAnswer = null;
        insertButton.setEnabled(false);
        history.setText("");
        status.setText("");
        if (clearView) {
            appendSystemNote("Новый чат.");
        }
    }

    // ================================================================= ответ

    private void finishAnswer(int answerStart, String answer, boolean cancelled) {
        if (history.isDisposed()) {
            return;
        }
        history.append("\n");
        styleCodeBlocks(answerStart);
        lastAnswer = answer;
        String sql = ResponseParser.extractSql(answer);
        insertButton.setEnabled(sql != null);
        setBusy(false, cancelled ? "Остановлено пользователем" : null);
        scrollToEnd();
    }

    private void insertLastSql() {
        String sql = lastAnswer == null ? null : ResponseParser.extractSql(lastAnswer);
        if (sql == null) {
            return;
        }
        // Берём актуальное состояние редактора (курсор мог сместиться).
        SqlEditorContext ctx = SqlEditorContext.from(activeEditor());
        if (ctx == null && lastEditorContext != null) {
            ctx = SqlEditorContext.from(lastEditorContext.editor());
        }
        if (ctx == null || !EditorInserter.insert(ctx, sql)) {
            copyToClipboard(sql);
            status.setText("SQL-редактор не найден — запрос скопирован в буфер обмена");
        }
    }

    // ================================================================= модели

    private String selectedModel() {
        String text = modelCombo.getText().trim();
        Object data = modelCombo.getData();
        if (data instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof ModelInfo mi && mi.toString().equals(text)) {
                    return mi.id();
                }
            }
        }
        return text.isEmpty() ? null : text;
    }

    private void loadModels() {
        OpenWebUIClient client = Prefs.client();
        Display display = modelCombo.getDisplay();
        String current = selectedModel();
        status.setText("Загружаю список моделей…");
        Job job = new Job("Open WebUI: список моделей") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    List<ModelInfo> models = client.listModels();
                    ui(display, () -> {
                        modelCombo.setData(models);
                        modelCombo.setItems(models.stream().map(ModelInfo::toString).toArray(String[]::new));
                        models.stream().filter(m -> m.id().equals(current)).findFirst()
                            .ifPresent(m -> modelCombo.setText(m.toString()));
                        status.setText("Моделей: " + models.size());
                    });
                } catch (OpenWebUIException e) {
                    ui(display, () -> status.setText(e.getMessage()));
                }
                return Status.OK_STATUS;
            }
        };
        job.schedule();
    }

    // ================================================================= вывод

    private void appendHeader(String who, String text) {
        if (history.getCharCount() > 0 && !history.getText().endsWith("\n\n")) {
            history.append(history.getText().endsWith("\n") ? "\n" : "\n\n");
        }
        int start = history.getCharCount();
        history.append(who + ":\n");
        StyleRange sr = new StyleRange(start, who.length() + 1, null, null, SWT.BOLD);
        history.setStyleRange(sr);
        if (text != null) {
            history.append(text + "\n");
        }
        scrollToEnd();
    }

    private void appendCode(String code) {
        int start = history.getCharCount();
        history.append(code + "\n");
        StyleRange sr = new StyleRange();
        sr.start = start;
        sr.length = code.length();
        sr.font = monoFont();
        history.setStyleRange(sr);
    }

    private void appendSystemNote(String text) {
        if (history == null || history.isDisposed()) {
            return;
        }
        int start = history.getCharCount();
        history.append(text + "\n");
        StyleRange sr = new StyleRange(start, text.length(), history.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY), null, SWT.ITALIC);
        history.setStyleRange(sr);
        scrollToEnd();
    }

    private void appendError(String text) {
        int start = history.getCharCount();
        String line = "✖ " + text + "\n";
        history.append(line);
        history.setStyleRange(new StyleRange(start, line.length() - 1, history.getDisplay().getSystemColor(SWT.COLOR_RED), null));
        scrollToEnd();
    }

    private void styleCodeBlocks(int from) {
        String text = history.getText();
        if (from >= text.length()) {
            return;
        }
        Matcher m = CODE_BLOCK.matcher(text);
        m.region(from, text.length());
        while (m.find()) {
            StyleRange sr = new StyleRange();
            sr.start = m.start();
            sr.length = m.end() - m.start();
            sr.font = monoFont();
            history.setStyleRange(sr);
        }
    }

    private static Font monoFont() {
        return JFaceResources.getFont(JFaceResources.TEXT_FONT);
    }

    private void scrollToEnd() {
        history.setTopIndex(history.getLineCount() - 1);
    }

    private boolean isBusy() {
        return runningJob != null && runningJob.getState() != Job.NONE && stopButton.isEnabled();
    }

    private void setBusy(boolean busy, String message) {
        if (stopButton.isDisposed()) {
            return;
        }
        stopButton.setEnabled(busy);
        if (busy) {
            insertButton.setEnabled(false);
        }
        status.setText(message == null ? "" : message);
        status.getParent().layout();
    }

    private void copyToClipboard(String text) {
        Clipboard cb = new Clipboard(history.getDisplay());
        try {
            cb.setContents(new Object[]{text}, new Transfer[]{TextTransfer.getInstance()});
        } finally {
            cb.dispose();
        }
    }

    private IEditorPart activeEditor() {
        IWorkbenchPage page = getSite().getPage();
        return page == null ? null : page.getActiveEditor();
    }

    private static Path stateDir() {
        return Activator.getDefault().getStateLocation().append("chats").toFile().toPath();
    }

    private static Composite row(Composite parent, int columns) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        GridLayout l = new GridLayout(columns, false);
        l.marginWidth = 0;
        l.marginHeight = 0;
        c.setLayout(l);
        return c;
    }

    private static void column(Table table, String name, int width) {
        TableColumn col = new TableColumn(table, SWT.LEFT);
        col.setText(name);
        col.setWidth(width);
    }

    private static void syncUi(Display display, Runnable r) {
        if (!display.isDisposed()) {
            display.syncExec(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    Activator.logError("Ошибка обновления панели", e);
                }
            });
        }
    }

    private static void ui(Display display, Runnable r) {
        if (!display.isDisposed()) {
            display.asyncExec(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    Activator.logError("Ошибка обновления панели", e);
                }
            });
        }
    }

    /**
     * Буфер потокового вывода: копит фрагменты и сбрасывает их в UI не чаще раза в 60 мс,
     * чтобы не заваливать UI-поток тысячами asyncExec.
     */
    private final class StreamBuffer {
        private final Display display;
        private final StringBuilder pending = new StringBuilder();
        private boolean scheduled;

        StreamBuffer(Display display) {
            this.display = display;
        }

        synchronized void add(String text) {
            pending.append(text);
            if (!scheduled && !display.isDisposed()) {
                scheduled = true;
                display.asyncExec(() -> display.timerExec(60, this::flush));
            }
        }

        void flushNow() {
            if (!display.isDisposed()) {
                display.syncExec(this::flush);
            }
        }

        private void flush() {
            String chunk;
            synchronized (this) {
                chunk = pending.toString();
                pending.setLength(0);
                scheduled = false;
            }
            if (!chunk.isEmpty() && !history.isDisposed()) {
                history.append(chunk);
                scrollToEnd();
            }
        }
    }
}
