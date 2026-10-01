package org.jkiss.dbeaver.ui.ai.model;
import org.jkiss.code.NotNull; import org.jkiss.dbeaver.DBException; import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor; import org.jkiss.utils.function.ThrowableFunction;
public class CachedValue<T> { private final ThrowableFunction<DBRProgressMonitor, T, DBException> s;
 public CachedValue(@NotNull ThrowableFunction<DBRProgressMonitor, T, DBException> supplier){s=supplier;}
 @NotNull public T get(@NotNull DBRProgressMonitor monitor, boolean refresh) throws DBException { return s.apply(monitor);} }
