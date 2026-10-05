/*
 * Open WebUI background chats for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.async;

import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ui.IWorkbenchWindowInitializer;

/**
 * Resumes delivery of background answers after DBeaver start (answers generated while it was closed).
 */
public class AsyncStartup implements IWorkbenchWindowInitializer {

    private static final Log log = Log.getLog(AsyncStartup.class);

    @Override
    public void initializeWorkbenchWindow(@NotNull IWorkbenchWindowConfigurer configurer) {
        try {
            if (JobPoller.pendingCount() > 0) {
                JobPoller.wake();
            }
        } catch (RuntimeException e) {
            log.debug("Can't resume Open WebUI background answers", e);
        }
    }
}
