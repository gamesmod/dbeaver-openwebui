package org.jkiss.dbeaver.ui.ai.preferences;
import org.jkiss.code.NotNull; import org.eclipse.swt.widgets.Composite; import org.jkiss.dbeaver.model.ai.engine.AIEngineProperties; import org.jkiss.dbeaver.model.ai.registry.AIEngineDescriptor;
public abstract class AbstractAIEngineConfigurator<ENGINE extends AIEngineDescriptor, PROPERTIES extends AIEngineProperties> implements AIIObjectPropertyConfigurator<ENGINE, PROPERTIES> {
    @NotNull protected Composite createAdvancedSettings(@NotNull Composite parent) { return parent; }
    protected void loadAdvancedSettings(@NotNull AIEngineProperties configuration) {}
    protected void applyAdvancedSettings() {}
    protected void saveAdvancedSettings(@NotNull AIEngineProperties configuration) {} }
