package org.jkiss.dbeaver.ui.ai.preferences;
import org.jkiss.dbeaver.model.ai.engine.AIEngineProperties; import org.jkiss.dbeaver.model.ai.registry.AIEngineDescriptor; import org.jkiss.dbeaver.ui.IObjectPropertyConfigurator; import java.util.Optional;
public interface AIIObjectPropertyConfigurator<ENGINE extends AIEngineDescriptor, PROPERTIES extends AIEngineProperties> extends IObjectPropertyConfigurator<ENGINE, PROPERTIES> {
    default Optional<AIEngineProperties> getCurrentProperties() { return Optional.empty(); }
    default boolean supportsConnectionTest() { return true; } }
