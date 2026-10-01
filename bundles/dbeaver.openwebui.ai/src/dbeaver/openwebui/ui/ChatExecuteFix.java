/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.ui;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPDataSourceContainerProvider;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.IWorkbenchWindowInitializer;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.ai.chat.AIChatUtils;
import org.jkiss.dbeaver.ui.ai.chat.AIChatView;
import org.jkiss.dbeaver.ui.ai.chat.controls.AIChatControl;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorUtils;
import org.jkiss.dbeaver.ui.navigator.dialogs.SelectDataSourceDialog;

import java.util.List;

/**
 * Makes the "Execute" (▶) button under SQL blocks of the AI chat work when no connection is selected in the chat.
 * <p>
 * Out of the box DBeaver executes the query only through the connection selected in the chat; with
 * "No connection" the button silently does nothing (and the chat lists only already connected databases).
 * This class replaces the chat's {@code executeInEditor} browser function: if the chat has a connection,
 * the standard DBeaver behaviour is used; otherwise the connection is taken from the active SQL editor,
 * the navigator selection, the only connection of the project, or asked in a dialog. Then it is connected
 * and the query is executed in its SQL editor.
 */
public class ChatExecuteFix implements IWorkbenchWindowInitializer {

    private static final Log log = Log.getLog(ChatExecuteFix.class);

    static final String CHAT_VIEW_ID = "com.dbeaver.ai.chat";
    private static final String NAVIGATOR_VIEW_ID = "org.jkiss.dbeaver.core.databaseNavigator";
    private static final String FUNCTION_NAME = "executeInEditor";
    private static final String INSTALLED_KEY = "dbeaver.openwebui.executeFix";

    @Override
    public void initializeWorkbenchWindow(@NotNull IWorkbenchWindowConfigurer configurer) {
        // The window is not open yet: attach after the event loop processes its creation
        UIUtils.asyncExec(() -> attach(configurer.getWindow()));
    }

    private static void attach(@Nullable IWorkbenchWindow window) {
        if (window == null || window.getShell() == null || window.getShell().isDisposed()) {
            return;
        }
        window.getPartService().addPartListener(new IPartListener2() {
            @Override
            public void partOpened(@NotNull IWorkbenchPartReference ref) {
                install(ref.getPart(false));
            }

            @Override
            public void partActivated(@NotNull IWorkbenchPartReference ref) {
                install(ref.getPart(false));
            }

            @Override
            public void partVisible(@NotNull IWorkbenchPartReference ref) {
                install(ref.getPart(false));
            }
        });
        for (IWorkbenchPage page : window.getPages()) {
            install(page.findView(CHAT_VIEW_ID));
        }
    }

    private static void install(@Nullable IWorkbenchPart part) {
        if (!(part instanceof AIChatView chatView)) {
            return;
        }
        try {
            AIChatControl chat = chatView.getChat();
            if (chat == null || chat.isDisposed()) {
                return;
            }
            Browser browser = findBrowser(chat.getMessageList());
            if (browser == null || browser.isDisposed() || browser.getData(INSTALLED_KEY) != null) {
                return;
            }
            // A BrowserFunction with the same name replaces the one registered by DBeaver
            new BrowserFunction(browser, FUNCTION_NAME) {
                @Nullable
                @Override
                public Object function(@NotNull Object[] arguments) {
                    jsCalled = true;
                    if (arguments.length > 0 && arguments[0] != null) {
                        String sql = arguments[0].toString();
                        log.debug("Open WebUI: execute requested from AI chat (" + sql.length() + " chars)");
                        UIUtils.asyncExec(() -> {
                            try {
                                execute(chat, sql);
                            } catch (Throwable e) {
                                log.error("Open WebUI: error executing query from AI chat", e);
                                DBWorkbench.getPlatformUI().showError("Execute query", "Error executing query from AI chat", e);
                            }
                        });
                    }
                    return null;
                }
            };
            browser.setData(INSTALLED_KEY, Boolean.TRUE);
            log.debug("Open WebUI: AI chat execute handler installed");
            startSelfTest(chat, browser);
        } catch (Throwable e) {
            // Internal structure of the chat changed in a newer DBeaver: keep the standard behaviour
            log.debug("Open WebUI: can't install AI chat execute handler", e);
        }
    }

    private static volatile boolean jsCalled;

    /**
     * Test hook for CI (scripts/docs-screenshots.sh), inactive unless -Ddbeaver.openwebui.selftest=&lt;file&gt; is set:
     * when the file appears, its SQL is passed to the chat's executeInEditor JS function (like the ▶ button);
     * if the JS bridge does not respond, the same Java logic is called directly.
     */
    private static void startSelfTest(@NotNull AIChatControl chat, @NotNull Browser browser) {
        String file = System.getProperty("dbeaver.openwebui.selftest");
        if (file == null || file.isBlank()) {
            return;
        }
        java.nio.file.Path path = java.nio.file.Path.of(file);
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (browser.isDisposed()) {
                return;
            }
            if (!java.nio.file.Files.exists(path)) {
                browser.getDisplay().timerExec(1000, poll[0]);
                return;
            }
            try {
                String sql = java.nio.file.Files.readString(path);
                java.nio.file.Files.delete(path);
                jsCalled = false;
                String literal = "'" + sql.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "") + "'";
                boolean executed = browser.execute(FUNCTION_NAME + "(" + literal + ");");
                log.debug("Open WebUI selftest: JS call executed=" + executed);
                browser.getDisplay().timerExec(3000, () -> {
                    log.debug("Open WebUI selftest: JS bridge called handler=" + jsCalled);
                    if (!jsCalled) {
                        execute(chat, sql);
                    }
                });
            } catch (Exception e) {
                log.error("Open WebUI selftest failed", e);
            }
        };
        browser.getDisplay().timerExec(1000, poll[0]);
    }

    @Nullable
    private static Browser findBrowser(@Nullable Control control) {
        if (control instanceof Browser browser) {
            return browser;
        }
        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                Browser found = findBrowser(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    static void execute(@NotNull AIChatControl chat, @NotNull String sql) {
        if (chat.isDisposed() || sql.isBlank()) {
            return;
        }
        if (chat.getDataSourceContainer() != null) {
            // Connection selected in the chat: standard DBeaver behaviour
            AIChatUtils.executeInEditor(chat.getController(), sql);
            return;
        }
        DBPDataSourceContainer container = resolveContainer();
        log.debug("Open WebUI: chat has no connection, using " + (container == null ? "none" : container.getName()));
        if (container == null) {
            return;
        }
        SQLEditor editor = findEditor(container);
        if (editor != null && container.isConnected()) {
            runInEditor(editor, sql, false);
        } else {
            SQLEditorUtils.openNewSqlConsoleAndTryConnect(container, console -> {
                if (console != null) {
                    UIUtils.asyncExec(() -> runInEditor(console, sql, true));
                }
            });
        }
    }

    @Nullable
    private static DBPDataSourceContainer resolveContainer() {
        IWorkbenchWindow window = UIUtils.getActiveWorkbenchWindow();
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        if (page != null) {
            // 1. Active SQL editor (stays "active editor" while the chat view has focus)
            IEditorPart activeEditor = page.getActiveEditor();
            if (activeEditor instanceof DBPDataSourceContainerProvider provider && provider.getDataSourceContainer() != null) {
                return provider.getDataSourceContainer();
            }
            // 2. Selection in the database navigator
            IViewPart navigator = page.findView(NAVIGATOR_VIEW_ID);
            if (navigator != null) {
                ISelection selection = navigator.getSite().getSelectionProvider().getSelection();
                if (selection instanceof IStructuredSelection ss && ss.getFirstElement() instanceof DBNDatabaseNode node
                    && node.getDataSourceContainer() != null) {
                    return node.getDataSourceContainer();
                }
            }
        }
        // 3. The only connection of the active project, otherwise ask
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        List<? extends DBPDataSourceContainer> all = project == null ? List.of() : project.getDataSourceRegistry().getDataSources();
        if (all.size() == 1) {
            return all.getFirst();
        }
        SelectDataSourceDialog dialog = new SelectDataSourceDialog(UIUtils.getActiveWorkbenchShell(), project, null);
        if (dialog.open() == Window.OK) {
            return dialog.getDataSource();
        }
        return null;
    }

    @Nullable
    private static SQLEditor findEditor(@NotNull DBPDataSourceContainer container) {
        IWorkbenchWindow window = UIUtils.getActiveWorkbenchWindow();
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        if (page == null) {
            return null;
        }
        if (page.getActiveEditor() instanceof SQLEditor active && active.getDataSourceContainer() == container) {
            return active;
        }
        for (IEditorReference ref : page.getEditorReferences()) {
            if (ref.getEditor(false) instanceof SQLEditor editor && editor.getDataSourceContainer() == container) {
                return editor;
            }
        }
        return null;
    }

    private static void runInEditor(@NotNull SQLEditor editor, @NotNull String sql, boolean replaceText) {
        IDocument document = editor.getDocument();
        if (document == null) {
            return;
        }
        String text = sql.strip();
        int offset;
        try {
            if (replaceText || document.getLength() == 0) {
                document.set(text);
                offset = 0;
            } else {
                String separator = "\n\n";
                offset = document.getLength() + separator.length();
                document.replace(document.getLength(), 0, separator + text);
            }
        } catch (BadLocationException e) {
            log.error("Can't insert query into SQL editor", e);
            return;
        }
        IWorkbenchPage page = editor.getSite().getPage();
        page.activate(editor);
        editor.selectAndReveal(offset, text.length());
        List<SQLScriptElement> queries = editor.extractScriptQueries(offset, text.length(), true, false, true);
        if (queries == null || queries.isEmpty()) {
            return;
        }
        editor.processQueries(queries, queries.size() > 1, false, false, true, null, null);
    }
}
