package org.jkiss.dbeaver.ui; import org.jkiss.code.*; import org.eclipse.swt.widgets.*; import org.eclipse.swt.events.VerifyListener; import java.util.Locale;
public class UIUtils {
 public static Composite createComposite(@NotNull Composite parent, int columns){return new Composite();}
 public static Label createControlLabel(@NotNull Composite parent, @NotNull String label){return new Label(parent,0);}
 public static Text createLabelText(@NotNull Composite parent, @NotNull String label, @NotNull String value){return new Text(parent,0);}
 public static Button createCheckbox(@NotNull Composite parent, @NotNull String label, @Nullable String tooltip, boolean checked, int hSpan){return new Button();}
 public static VerifyListener getNumberVerifyListener(@NotNull Locale locale){return e->{};}
 public static int getFontHeight(@NotNull Control control){return 12;} }
