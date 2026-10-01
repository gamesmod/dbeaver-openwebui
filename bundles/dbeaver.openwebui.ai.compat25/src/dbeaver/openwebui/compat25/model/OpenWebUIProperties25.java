/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.compat25.model;

import com.google.gson.annotations.SerializedName;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.engine.AIEngineProperties;
import org.jkiss.dbeaver.model.meta.SecureProperty;
import org.jkiss.dbeaver.model.secret.DBSSecretController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Settings of the Open WebUI engine for the AI API of DBeaver 25.2.4–25.2.5.
 * JSON keys are the same as in the main bundle (DBeaver 26.2+), so settings survive a DBeaver upgrade.
 */
public class OpenWebUIProperties25 implements AIEngineProperties {

    public static final String DEFAULT_BASE_URL = "http://localhost:3000/api";
    public static final int DEFAULT_CONTEXT_WINDOW = 32_768;
    public static final int DEFAULT_TIMEOUT = 60;
    private static final String SECRET_TOKEN = "openwebui.token";

    private static final Set<String> RESTRICTED_HEADERS = Set.of(
        "connection", "content-length", "expect", "host", "upgrade", "authorization", "content-type"
    );

    @Nullable
    @SerializedName("openwebui.base_url")
    private String baseUrl;

    @Nullable
    @SecureProperty
    @SerializedName("openwebui.token")
    private String token;

    @Nullable
    @SerializedName("openwebui.model")
    private String model;

    @Nullable
    @SerializedName("openwebui.contextWindowSize")
    private Integer contextWindowSize;

    @Nullable
    @SerializedName("gpt.model.temperature")
    private Double temperature;

    @Nullable
    @SerializedName("gpt.log.query")
    private Boolean loggingEnabled;

    @Nullable
    @SerializedName("gpt.timeout")
    private Integer timeout;

    @Nullable
    @SerializedName("openwebui.streaming")
    private Boolean streaming;

    @Nullable
    @SerializedName("openwebui.functions")
    private Boolean functionsEnabled;

    @Nullable
    @SerializedName("openwebui.hideThinking")
    private Boolean hideThinking;

    @Nullable
    @SerializedName("openwebui.extraHeaders")
    private String extraHeaders;

    @Nullable
    @SerializedName("openwebui.meta.hideConnectionInfo")
    private Boolean metaHideConnectionInfo;

    @Nullable
    @SerializedName("openwebui.meta.snapshot")
    private String metaSnapshot;

    @Nullable
    @SerializedName("openwebui.meta.allowTableDdl")
    private Boolean metaAllowTableDdl;

    @Nullable
    @SerializedName("openwebui.meta.allowEditorText")
    private Boolean metaAllowEditorText;

    @Nullable
    @SerializedName("openwebui.meta.maxChars")
    private Integer metaMaxChars;

    public OpenWebUIProperties25() {
    }

    @NotNull
    public String getBaseUrl() {
        return isEmpty(baseUrl) ? DEFAULT_BASE_URL : baseUrl.trim();
    }

    public void setBaseUrl(@Nullable String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Nullable
    public String getToken() {
        return token;
    }

    public void setToken(@Nullable String token) {
        this.token = token;
    }

    @Nullable
    // part of AIEngineProperties only since DBeaver 25.2.5: no @Override
    public String getModel() {
        return model;
    }

    public void setModel(@Nullable String model) {
        this.model = model;
    }

    @Nullable
    // part of AIEngineProperties only since DBeaver 25.2.5: no @Override
    public Integer getContextWindowSize() {
        return contextWindowSize;
    }

    public void setContextWindowSize(@Nullable Integer contextWindowSize) {
        this.contextWindowSize = contextWindowSize;
    }

    // part of AIEngineProperties only since DBeaver 25.2.5: no @Override
    public double getTemperature() {
        return temperature == null || !Double.isFinite(temperature) ? 0.0 : Math.max(0, Math.min(2, temperature));
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    @Override
    public boolean isLoggingEnabled() {
        return loggingEnabled != null && loggingEnabled;
    }

    public void setLoggingEnabled(boolean loggingEnabled) {
        this.loggingEnabled = loggingEnabled;
    }

    public int getTimeout() {
        return timeout != null && timeout > 0 ? timeout : DEFAULT_TIMEOUT;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public boolean isStreaming() {
        return streaming == null || streaming;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }

    public boolean isFunctionsEnabled() {
        return functionsEnabled == null || functionsEnabled;
    }

    public void setFunctionsEnabled(boolean functionsEnabled) {
        this.functionsEnabled = functionsEnabled;
    }

    public boolean isHideThinking() {
        return hideThinking == null || hideThinking;
    }

    public void setHideThinking(boolean hideThinking) {
        this.hideThinking = hideThinking;
    }

    @Nullable
    public String getExtraHeaders() {
        return extraHeaders;
    }

    public void setExtraHeaders(@Nullable String extraHeaders) {
        this.extraHeaders = extraHeaders;
    }

    public boolean isMetaHideConnectionInfo() {
        return metaHideConnectionInfo != null && metaHideConnectionInfo;
    }

    public void setMetaHideConnectionInfo(boolean value) {
        this.metaHideConnectionInfo = value;
    }

    @NotNull
    public MetadataFilter.Snapshot getMetaSnapshot() {
        return MetadataFilter.Snapshot.of(metaSnapshot);
    }

    public void setMetaSnapshot(@NotNull MetadataFilter.Snapshot value) {
        this.metaSnapshot = value.name();
    }

    public boolean isMetaAllowTableDdl() {
        return metaAllowTableDdl == null || metaAllowTableDdl;
    }

    public void setMetaAllowTableDdl(boolean value) {
        this.metaAllowTableDdl = value;
    }

    public boolean isMetaAllowEditorText() {
        return metaAllowEditorText == null || metaAllowEditorText;
    }

    public void setMetaAllowEditorText(boolean value) {
        this.metaAllowEditorText = value;
    }

    public int getMetaMaxChars() {
        return metaMaxChars == null || metaMaxChars < 0 ? 0 : metaMaxChars;
    }

    public void setMetaMaxChars(int value) {
        this.metaMaxChars = value;
    }

    @NotNull
    public MetadataFilter createMetadataFilter() {
        return new MetadataFilter(isMetaHideConnectionInfo(), getMetaSnapshot(), isMetaAllowTableDdl(),
            isMetaAllowEditorText(), getMetaMaxChars());
    }

    @NotNull
    public Map<String, String> getExtraHeadersMap() {
        Map<String, String> result = new LinkedHashMap<>();
        if (isEmpty(extraHeaders)) {
            return result;
        }
        for (String line : extraHeaders.split("\\R")) {
            int pos = line.indexOf(':');
            if (pos <= 0) {
                continue;
            }
            String name = line.substring(0, pos).trim();
            String value = line.substring(pos + 1).trim();
            if (name.isEmpty() || RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            result.put(name, value);
        }
        return result;
    }

    @Override
    public boolean isValidConfiguration() {
        return !isEmpty(getBaseUrl()) && !isEmpty(model);
    }

    @Override
    public void resolveSecrets() throws DBException {
        DBSSecretController controller = DBSSecretController.getGlobalSecretController();
        String value = controller.getPrivateSecretValue(SECRET_TOKEN);
        if (!isEmpty(value)) {
            token = value;
        }
    }

    @Override
    public void saveSecrets() throws DBException {
        DBSSecretController.getGlobalSecretController().setPrivateSecretValue(SECRET_TOKEN, token);
    }

    static boolean isEmpty(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
