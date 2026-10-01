package org.jkiss.dbeaver.ui; import org.jkiss.code.*; import org.eclipse.swt.widgets.Composite;
public interface IObjectPropertyConfigurator<OBJECT, SETTINGS> {
    void createControl(@NotNull Composite parent, OBJECT object, @NotNull Runnable propertyChangeListener);
    void loadSettings(@NotNull SETTINGS settings);
    void saveSettings(@NotNull SETTINGS settings);
    void resetSettings(@NotNull SETTINGS settings);
    boolean isComplete();
    default String getErrorMessage() { return null; } }
