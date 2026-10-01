/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.compat25.ui;

import dbeaver.openwebui.compat25.model.OpenWebUIEngine25;
import dbeaver.openwebui.compat25.model.OpenWebUIProperties25;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.engine.AIEngineProperties;
import org.jkiss.dbeaver.model.ai.engine.AIModel;
import org.jkiss.dbeaver.model.ai.registry.AIEngineDescriptor;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.ai.model.ContextWindowSizeField;
import org.jkiss.dbeaver.ui.ai.model.ModelSelectorField;
import org.jkiss.dbeaver.ui.ai.preferences.AIIObjectPropertyConfigurator;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Settings panel of the Open WebUI engine for DBeaver 25.2 (Window → Preferences → AI).
 */
public class OpenWebUIConfigurator25 implements AIIObjectPropertyConfigurator<AIEngineDescriptor, OpenWebUIProperties25> {

    private Text baseUrlText;
    private Text tokenText;
    private ModelSelectorField modelSelectorField;
    private ContextWindowSizeField contextWindowSizeField;
    private Text temperatureText;
    private Text timeoutText;
    private Button streamingCheck;
    private Button functionsCheck;
    private Button hideThinkingCheck;
    private Button logCheck;
    private Text headersText;
    private MetadataSettingsPanel metadataPanel;

    /** Mirrors of the widgets: the model list is loaded in a background job. */
    private volatile String baseUrl = OpenWebUIProperties25.DEFAULT_BASE_URL;
    private volatile String token = "";
    private volatile String extraHeaders = "";
    private volatile int timeoutSeconds = OpenWebUIProperties25.DEFAULT_TIMEOUT;
    private final Map<String, Integer> contextByModel = new ConcurrentHashMap<>();
    private Runnable propertyChangeListener = () -> { };

    @Override
    public void createControl(@NotNull Composite parent, @Nullable AIEngineDescriptor engine, @NotNull Runnable propertyChangeListener) {
        this.propertyChangeListener = propertyChangeListener;
        Composite composite = UIUtils.createComposite(parent, 3);
        composite.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Label urlLabel = UIUtils.createControlLabel(composite, OpenWebUIMessages.base_url_label);
        urlLabel.setToolTipText(OpenWebUIMessages.base_url_tip);
        baseUrlText = new Text(composite, SWT.BORDER);
        baseUrlText.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).span(2, 1).hint(150, SWT.DEFAULT).create());
        baseUrlText.setMessage(OpenWebUIProperties25.DEFAULT_BASE_URL);
        baseUrlText.setToolTipText(OpenWebUIMessages.base_url_tip);
        baseUrlText.addModifyListener(e -> {
            baseUrl = baseUrlText.getText();
            propertyChangeListener.run();
        });
        new Label(composite, SWT.NONE);
        Label hint = new Label(composite, SWT.WRAP);
        hint.setText(OpenWebUIMessages.base_url_hint);
        hint.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).span(2, 1).hint(150, SWT.DEFAULT).create());

        Label tokenLabel = UIUtils.createControlLabel(composite, OpenWebUIMessages.token_label);
        tokenLabel.setToolTipText(OpenWebUIMessages.token_tip);
        tokenText = new Text(composite, SWT.BORDER | SWT.PASSWORD);
        tokenText.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).span(2, 1).hint(150, SWT.DEFAULT).create());
        tokenText.setMessage(OpenWebUIMessages.token_placeholder);
        tokenText.setToolTipText(OpenWebUIMessages.token_tip);
        tokenText.addModifyListener(e -> {
            token = tokenText.getText();
            propertyChangeListener.run();
        });

        modelSelectorField = ModelSelectorField.builder()
            .withParent(composite)
            .withGridData(new GridData(GridData.FILL_HORIZONTAL))
            .withModelListSupplier((monitor, forceRefresh) -> fetchModels(monitor))
            .withSelectionListener(SelectionListener.widgetSelectedAdapter(e -> onModelSelected()))
            .build();
        // ModelSelectorField of DBeaver 25.2 has its own refresh button

        contextWindowSizeField = ContextWindowSizeField.builder()
            .withParent(composite)
            .withGridData(GridDataFactory.fillDefaults().span(2, 1).create())
            .build();

        temperatureText = UIUtils.createLabelText(composite, OpenWebUIMessages.temperature_label, "0.0");
        temperatureText.setLayoutData(GridDataFactory.fillDefaults().span(2, 1).create());
        temperatureText.addVerifyListener(UIUtils.getNumberVerifyListener(Locale.getDefault()));
        temperatureText.setToolTipText(OpenWebUIMessages.temperature_tip);

        streamingCheck = UIUtils.createCheckbox(composite, OpenWebUIMessages.streaming_label, OpenWebUIMessages.streaming_tip, true, 3);
        functionsCheck = UIUtils.createCheckbox(composite, OpenWebUIMessages.functions_label, OpenWebUIMessages.functions_tip, true, 3);
        hideThinkingCheck = UIUtils.createCheckbox(composite, OpenWebUIMessages.hide_thinking_label, OpenWebUIMessages.hide_thinking_tip, true, 3);

        Label headersLabel = UIUtils.createControlLabel(composite, OpenWebUIMessages.headers_label);
        headersLabel.setLayoutData(new GridData(GridData.VERTICAL_ALIGN_BEGINNING));
        headersText = new Text(composite, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL);
        headersText.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).span(2, 1)
            .hint(150, UIUtils.getFontHeight(headersText) * 3).create());
        headersText.setToolTipText(OpenWebUIMessages.headers_tip);
        headersText.addModifyListener(e -> extraHeaders = headersText.getText());

        metadataPanel = new MetadataSettingsPanel(composite, 3);

        timeoutText = UIUtils.createLabelText(composite, "Timeout (s)", String.valueOf(OpenWebUIProperties25.DEFAULT_TIMEOUT));
        timeoutText.setLayoutData(GridDataFactory.fillDefaults().span(2, 1).create());
        timeoutText.addModifyListener(e -> timeoutSeconds = parseInt(timeoutText.getText(), OpenWebUIProperties25.DEFAULT_TIMEOUT));
        logCheck = UIUtils.createCheckbox(composite, "Write AI queries to debug log", null, false, 3);

        // DBeaver 25.2 re-lays out only the "Engine Settings" group, which keeps the height of the previous
        // engine's panel: re-layout the whole page so that all fields are visible.
        UIUtils.asyncExec(() -> {
            if (!composite.isDisposed()) {
                composite.getShell().layout(true, true);
            }
        });
    }

    @Override
    public void loadSettings(@NotNull OpenWebUIProperties25 configuration) {
        baseUrlText.setText(configuration.getBaseUrl());
        tokenText.setText(nvl(configuration.getToken()));
        headersText.setText(nvl(configuration.getExtraHeaders()));
        timeoutText.setText(String.valueOf(configuration.getTimeout()));
        modelSelectorField.setSelectedModel(configuration.getModel());
        contextWindowSizeField.setValue(configuration.getContextWindowSize() != null
            ? configuration.getContextWindowSize()
            : Integer.valueOf(OpenWebUIProperties25.DEFAULT_CONTEXT_WINDOW));
        temperatureText.setText(String.valueOf(configuration.getTemperature()));
        streamingCheck.setSelection(configuration.isStreaming());
        functionsCheck.setSelection(configuration.isFunctionsEnabled());
        hideThinkingCheck.setSelection(configuration.isHideThinking());
        logCheck.setSelection(configuration.isLoggingEnabled());
        metadataPanel.load(configuration.isMetaHideConnectionInfo(), configuration.getMetaSnapshot(),
            configuration.isMetaAllowTableDdl(), configuration.isMetaAllowEditorText(), configuration.getMetaMaxChars());
        modelSelectorField.refreshModelListSilently(true);
    }

    @Override
    public void saveSettings(@NotNull OpenWebUIProperties25 configuration) {
        configuration.setBaseUrl(baseUrlText.getText().strip());
        configuration.setToken(tokenText.getText().strip());
        String model = modelSelectorField.getSelectedModel();
        if (model != null && !model.isBlank()) {
            configuration.setModel(model.strip());
        }
        configuration.setContextWindowSize(contextWindowSizeField.getValue());
        configuration.setTemperature(parseDouble(temperatureText.getText()));
        configuration.setStreaming(streamingCheck.getSelection());
        configuration.setFunctionsEnabled(functionsCheck.getSelection());
        configuration.setHideThinking(hideThinkingCheck.getSelection());
        configuration.setExtraHeaders(headersText.getText());
        configuration.setTimeout(parseInt(timeoutText.getText(), OpenWebUIProperties25.DEFAULT_TIMEOUT));
        configuration.setLoggingEnabled(logCheck.getSelection());
        configuration.setMetaHideConnectionInfo(metadataPanel.hideConnectionCheck.getSelection());
        configuration.setMetaSnapshot(metadataPanel.snapshot());
        configuration.setMetaAllowTableDdl(metadataPanel.allowDdlCheck.getSelection());
        configuration.setMetaAllowEditorText(metadataPanel.allowEditorCheck.getSelection());
        configuration.setMetaMaxChars(metadataPanel.maxCharsSpinner.getSelection());
    }

    @Override
    public void resetSettings(@NotNull OpenWebUIProperties25 configuration) {
        // nothing to reset
    }

    @Override
    public boolean isComplete() {
        String model = modelSelectorField == null ? null : modelSelectorField.getSelectedModel();
        return baseUrlText != null && !baseUrlText.getText().isBlank()
            && model != null && !model.isBlank()
            && contextWindowSizeField.isComplete();
    }

    @NotNull
    @Override
    public Optional<AIEngineProperties> getCurrentProperties() {
        OpenWebUIProperties25 copy = new OpenWebUIProperties25();
        saveSettings(copy);
        return Optional.of(copy);
    }

    private void onModelSelected() {
        String model = modelSelectorField.getSelectedModel();
        Integer ctx = model == null ? null : contextByModel.get(model);
        if (ctx != null) {
            contextWindowSizeField.setValue(ctx);
        } else if (contextWindowSizeField.getValue() == null) {
            contextWindowSizeField.setValue(OpenWebUIProperties25.DEFAULT_CONTEXT_WINDOW);
        }
        propertyChangeListener.run();
    }

    @NotNull
    private List<String> fetchModels(@NotNull DBRProgressMonitor monitor) throws DBException {
        OpenWebUIProperties25 props = new OpenWebUIProperties25();
        props.setBaseUrl(baseUrl);
        props.setToken(token);
        props.setExtraHeaders(extraHeaders);
        props.setTimeout(timeoutSeconds);
        try (OpenWebUIEngine25 engine = new OpenWebUIEngine25(props)) {
            List<AIModel> models = engine.getModels(monitor);
            for (AIModel m : models) {
                if (m.contextWindowSize() != null) {
                    contextByModel.put(m.name(), m.contextWindowSize());
                }
            }
            return models.stream().map(AIModel::name).toList();
        }
    }

    private static double parseDouble(@Nullable String text) {
        try {
            return text == null || text.isBlank() ? 0.0 : Double.parseDouble(text.strip().replace(',', '.'));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static int parseInt(@Nullable String text, int def) {
        try {
            int v = text == null ? def : Integer.parseInt(text.strip());
            return v > 0 ? v : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @NotNull
    private static String nvl(@Nullable String s) {
        return s == null ? "" : s;
    }
}
