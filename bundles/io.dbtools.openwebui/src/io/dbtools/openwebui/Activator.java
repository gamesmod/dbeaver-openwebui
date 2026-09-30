package io.dbtools.openwebui;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * Активатор плагина. Хранит ссылку на экземпляр для доступа к хранилищу настроек и журналу.
 */
public class Activator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "io.dbtools.openwebui";

    private static Activator instance;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        instance = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return instance;
    }

    public static void logError(String message, Throwable e) {
        if (instance != null) {
            ILog.of(instance.getBundle()).log(new Status(IStatus.ERROR, PLUGIN_ID, message, e));
        }
    }

    public static void logInfo(String message) {
        if (instance != null) {
            ILog.of(instance.getBundle()).log(new Status(IStatus.INFO, PLUGIN_ID, message));
        }
    }
}
