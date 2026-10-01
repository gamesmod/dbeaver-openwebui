package org.jkiss.dbeaver.model.runtime; public interface DBRProgressMonitor { boolean isCanceled(); void beginTask(String n, int w); void subTask(String n); void done(); }
