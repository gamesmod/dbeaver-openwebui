package org.jkiss.dbeaver.ui.ai.model;
import org.jkiss.code.*; import org.eclipse.swt.widgets.*; import org.eclipse.swt.layout.GridData; import org.jkiss.dbeaver.DBException; import org.jkiss.dbeaver.model.ai.engine.AIModel; import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor; import java.util.List;
public class ModelSelectorField { public static Builder builder(){return new Builder();}
 @Nullable public String getSelectedModelName(){return null;} @Nullable public AIModel getSelectedModel(){return null;}
 public void setSelectedModel(@Nullable String model){} public void refreshModelListSilently(boolean refresh){}
 public static class Builder { public Builder withParent(@NotNull Composite parent){return this;} public Builder withGridData(@NotNull GridData gridData){return this;}
  public Builder withModifyListener(@NotNull Runnable onModify){return this;} public Builder withModelListSupplier(@NotNull ModelListProvider p){return this;}
  public Builder withModelLabel(@NotNull String modelLabel){return this;} public Builder withRequiredSetting(@NotNull Text control, @NotNull String messageWhenEmpty){return this;}
  public ModelSelectorField build(){return new ModelSelectorField();} }
 public interface ModelListProvider { @NotNull List<AIModel> getModels(@NotNull DBRProgressMonitor monitor, boolean forceRefresh) throws DBException; } }
