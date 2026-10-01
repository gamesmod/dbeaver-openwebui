/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the Apache License, Version 2.0.
 */
package dbeaver.openwebui.ui;

import dbeaver.openwebui.model.OpenWebUIConstants;
import dbeaver.openwebui.model.OpenWebUIEngine;
import dbeaver.openwebui.model.OpenWebUIProperties;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
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
import org.jkiss.dbeaver.ui.ai.model.CachedValue;
import org.jkiss.dbeaver.ui.ai.model.ContextWindowSizeField;
import org.jkiss.dbeaver.ui.ai.model.ModelSelectorField;
import org.jkiss.dbeaver.ui.ai.preferences.AbstractAIEngineConfigurator;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Settings panel of the "Open WebUI" engine in Window > Preferences > AI > Engines.
 * Registered for {@link OpenWebUIEngine} via org.jkiss.dbeaver.ui.propertyConfigurator.
 */
public class OpenWebUIConfigurator extends AbstractAIEngineConfigurator<AIEngineDescriptor, OpenWebUIProperties> {

    private Text baseUrlText;
    private Text tokenText;
    private ModelSelectorField modelSelectorField;
    private ContextWindowSizeField contextWindowSizeField;
    private Text temperatureText;
    private Button streamingCheck;
    private Button functionsCheck;
    private Button hideThinkingCheck;
    private Text headersText;

    /** Values are mirrored from widgets, because the model list is loaded in a background job. */
    private volatile String baseUrl = OpenWebUIConstants.DEFAULT_BASE_URL;
    private volatile String token = "";
    private volatile String extraHeaders = "";
    private volatile int timeoutSeconds = AIEngineProperties.DEFAULT_TIMEOUT;

    /** Recreated when connection settings change, so a stale model list is never shown. */
    private volatile CachedValue<List<AIModel>> modelsCache = new CachedValue<>(this::fetchModels);
    private Runnable propertyChangeListener = () -> { };

    @Override
    public void createControl(
        @NotNull Composite parent,
        @Nullable AIEngineDescriptor engine,
        @NotNull Runnable propertyChangeListener
    ) {
        this.propertyChangeListener = propertyChangeListener;
        Composite composite = UIUtils.createComposite(parent, 3);
        composite.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        // --- connection
        Label urlLabel = UIUtils.createControlLabel(composite, OpenWebUIMessages.base_url_label);
        urlLabel.setToolTipText(OpenWebUIMessages.base_url_tip);
        baseUrlText = new Text(composite, SWT.BORDER);
        baseUrlText.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).span(2, 1).hint(150, SWT.DEFAULT).create());
        baseUrlText.setMessage(OpenWebUIConstants.DEFAULT_BASE_URL);
        baseUrlText.setToolTipText(OpenWebUIMessages.base_url_tip);
        baseUrlText.addModifyListener(e -> {
            baseUrl = baseUrlText.getText();
            onConnectionChanged();
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
            onConnectionChanged();
        });

        // --- model
        modelSelectorField = ModelSelectorField.builder()
            .withParent(composite)
            .withGridData(new GridData(GridData.FILL_HORIZONTAL))
            .withRequiredSetting(baseUrlText, OpenWebUIMessages.url_required)
            .withModelListSupplier((monitor, forceRefresh) -> modelsCache.get(monitor, forceRefresh))
            .withModifyListener(this::onModelChanged)
            .build();

        contextWindowSizeField = ContextWindowSizeField.builder()
            .withParent(composite)
            .withGridData(GridDataFactory.fillDefaults().span(2, 1).create())
            .build();

        temperatureText = UIUtils.createLabelText(composite, OpenWebUIMessages.temperature_label, "0.0");
        temperatureText.setLayoutData(GridDataFactory.fillDefaults().span(2, 1).create());
        temperatureText.addVerifyListener(UIUtils.getNumberVerifyListener(Locale.getDefault()));
        temperatureText.setToolTipText(OpenWebUIMessages.temperature_tip);

        // --- behaviour
        streamingCheck = UIUtils.createCheckbox(
            composite, OpenWebUIMessages.streaming_label, OpenWebUIMessages.streaming_tip, true, 3);
        functionsCheck = UIUtils.createCheckbox(
            composite, OpenWebUIMessages.functions_label, OpenWebUIMessages.functions_tip, true, 3);
        hideThinkingCheck = UIUtils.createCheckbox(
            composite, OpenWebUIMessages.hide_thinking_label, OpenWebUIMessages.hide_thinking_tip, true, 3);

        Label headersLabel = UIUtils.createControlLabel(composite, OpenWebUIMessages.headers_label);
        headersLabel.setLayoutData(new GridData(GridData.VERTICAL_ALIGN_BEGINNING));
        headersText = new Text(composite, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL);
        headersText.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).span(2, 1)
            .hint(150, UIUtils.getFontHeight(headersText) * 3).create());
        headersText.setToolTipText(OpenWebUIMessages.headers_tip);
        headersText.addModifyListener(e -> {
            extraHeaders = headersText.getText();
            onConnectionChanged();
        });

        // --- timeout / logging
        createAdvancedSettings(composite);
    }

    @Override
    public void loadSettings(@NotNull OpenWebUIProperties configuration) {
        baseUrlText.setText(configuration.getBaseUrl());
        tokenText.setText(nvl(configuration.getToken()));
        headersText.setText(nvl(configuration.getExtraHeaders()));
        timeoutSeconds = configuration.getTimeout();
        modelSelectorField.setSelectedModel(configuration.getModel());
        contextWindowSizeField.setValue(configuration.getContextWindowSize() != null
            ? configuration.getContextWindowSize()
            : Integer.valueOf(OpenWebUIConstants.DEFAULT_CONTEXT_WINDOW));
        temperatureText.setText(String.valueOf(configuration.getTemperature()));
        streamingCheck.setSelection(configuration.isStreaming());
        functionsCheck.setSelection(configuration.isFunctionsEnabled());
        hideThinkingCheck.setSelection(configuration.isHideThinking());
        loadAdvancedSettings(configuration);

        modelSelectorField.refreshModelListSilently(true);
    }

    @Override
    public void saveSettings(@NotNull OpenWebUIProperties configuration) {
        configuration.setBaseUrl(baseUrlText.getText().strip());
        configuration.setToken(tokenText.getText().strip());
        String model = modelSelectorField.getSelectedModelName();
        if (model != null && !model.isBlank()) {
            configuration.setModel(model.strip());
        }
        configuration.setContextWindowSize(contextWindowSizeField.getValue());
        configuration.setTemperature(parseDouble(temperatureText.getText()));
        configuration.setStreaming(streamingCheck.getSelection());
        configuration.setFunctionsEnabled(functionsCheck.getSelection());
        configuration.setHideThinking(hideThinkingCheck.getSelection());
        configuration.setExtraHeaders(headersText.getText());
        saveAdvancedSettings(configuration);
    }

    @Override
    public void resetSettings(@NotNull OpenWebUIProperties configuration) {
        // nothing to reset
    }

    @Override
    public boolean isComplete() {
        String model = modelSelectorField == null ? null : modelSelectorField.getSelectedModelName();
        return baseUrlText != null
            && !baseUrlText.getText().isBlank()
            && model != null && !model.isBlank()
            && contextWindowSizeField.isComplete();
    }

    /**
     * Used by the "Test connection" button of the settings page.
     */
    @NotNull
    @Override
    public Optional<AIEngineProperties> getCurrentProperties() {
        OpenWebUIProperties copy = new OpenWebUIProperties();
        saveSettings(copy);
        return Optional.of(copy);
    }

    // ------------------------------------------------------------------ internals

    private void onConnectionChanged() {
        modelsCache = new CachedValue<>(this::fetchModels);
        propertyChangeListener.run();
    }

    private void onModelChanged() {
        AIModel model = modelSelectorField.getSelectedModel();
        if (model != null && model.contextWindowSize() != null) {
            contextWindowSizeField.setValue(model.contextWindowSize());
        } else if (contextWindowSizeField.getValue() == null) {
            contextWindowSizeField.setValue(OpenWebUIConstants.DEFAULT_CONTEXT_WINDOW);
        }
        propertyChangeListener.run();
    }

    @NotNull
    private List<AIModel> fetchModels(@NotNull DBRProgressMonitor monitor) throws DBException {
        OpenWebUIProperties props = new OpenWebUIProperties();
        props.setBaseUrl(baseUrl);
        props.setToken(token);
        props.setExtraHeaders(extraHeaders);
        props.setTimeout(timeoutSeconds);
        try (OpenWebUIEngine engine = new OpenWebUIEngine(props)) {
            return engine.getModels(monitor);
        }
    }

    private static double parseDouble(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(text.strip().replace(',', '.'));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    @NotNull
    private static String nvl(@Nullable String s) {
        return s == null ? "" : s;
    }
}
