package org.jkiss.dbeaver.ui.ai.model;
import org.jkiss.code.*; import org.eclipse.swt.widgets.Composite; import org.eclipse.swt.layout.GridData;
public class ContextWindowSizeField { public static Builder builder(){return new Builder();}
 public void setValue(@Nullable Integer value){} @Nullable public Integer getValue(){return null;} public boolean isComplete(){return true;}
 public static class Builder { public Builder withParent(@NotNull Composite parent){return this;} public Builder withGridData(@NotNull GridData gridData){return this;} public ContextWindowSizeField build(){return new ContextWindowSizeField();} } }
