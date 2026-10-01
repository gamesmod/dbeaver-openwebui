/*
 * Open WebUI engine for DBeaver AI assistant.
 * Licensed under the MIT License, see LICENSE in the repository root.
 */
package dbeaver.openwebui.model;

import com.google.gson.annotations.SerializedName;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.AIConfigurationProfile;
import org.jkiss.dbeaver.model.ai.engine.AIModel;
import org.jkiss.dbeaver.model.ai.engine.BaseAIEngineProperties;
import org.jkiss.dbeaver.model.ai.utils.AIUtils;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.meta.SecureProperty;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Settings of an Open WebUI (or any OpenAI Chat Completions compatible) connection.
 * Non-secure fields are serialized by DBeaver into the AI profile JSON,
 * the API key goes to DBeaver secure storage (see {@link #saveSecrets}).
 */
public class OpenWebUIProperties extends BaseAIEngineProperties {

    /** Headers java.net.http.HttpClient refuses to set, or that we set ourselves. */
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

    // --- Ограничение передаваемых метаданных (см. MetadataFilter)
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

    public OpenWebUIProperties() {
    }

    @NotNull
    @Property(order = 1, required = true)
    public String getBaseUrl() {
        return isEmpty(baseUrl) ? OpenWebUIConstants.DEFAULT_BASE_URL : baseUrl.trim();
    }

    public void setBaseUrl(@Nullable String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Nullable
    @Property(order = 2, password = true)
    public String getToken() {
        return token;
    }

    public void setToken(@Nullable String token) {
        this.token = token;
    }

    @Nullable
    @Override
    @Property(order = 3)
    public String getModel() {
        return model;
    }

    @Override
    public void setModel(@NotNull String model) {
        this.model = model;
    }

    @Override
    public void selectModel(@NotNull AIModel model) {
        this.model = model.name();
        if (model.contextWindowSize() != null) {
            this.contextWindowSize = model.contextWindowSize();
        }
    }

    @Nullable
    @Override
    @Property(order = 4, min = 1)
    public Integer getContextWindowSize() {
        return contextWindowSize;
    }

    public void setContextWindowSize(@Nullable Integer contextWindowSize) {
        this.contextWindowSize = contextWindowSize;
    }

    @Override
    @Property(order = 5)
    public double getTemperature() {
        return temperature;
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

    /**
     * Parses "Name: value" lines. Restricted and malformed lines are skipped.
     */
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

    /**
     * Open WebUI may run with authentication disabled, so only the URL and the model are mandatory.
     */
    @Override
    public boolean isValidConfiguration() {
        return !isEmpty(getBaseUrl()) && !isEmpty(model);
    }

    @Override
    public void resolveSecrets(@NotNull AIConfigurationProfile profile) throws DBException {
        if (token == null) {
            token = AIUtils.getSecretValueOrDefault(profile, OpenWebUIConstants.SECRET_TOKEN, null);
        }
    }

    @Override
    public void saveSecrets(@NotNull AIConfigurationProfile profile) throws DBException {
        AIUtils.setSecretValue(profile, OpenWebUIConstants.SECRET_TOKEN, token);
    }

    @Override
    public void deleteSecrets(@NotNull AIConfigurationProfile profile) throws DBException {
        AIUtils.deleteSecretValue(profile, OpenWebUIConstants.SECRET_TOKEN);
    }

    /**
     * Copy used by the settings page for "Test connection" and model list loading.
     */
    @NotNull
    public OpenWebUIProperties copy() {
        OpenWebUIProperties copy = new OpenWebUIProperties();
        copy.baseUrl = baseUrl;
        copy.token = token;
        copy.model = model;
        copy.contextWindowSize = contextWindowSize;
        copy.streaming = streaming;
        copy.functionsEnabled = functionsEnabled;
        copy.hideThinking = hideThinking;
        copy.extraHeaders = extraHeaders;
        copy.metaHideConnectionInfo = metaHideConnectionInfo;
        copy.metaSnapshot = metaSnapshot;
        copy.metaAllowTableDdl = metaAllowTableDdl;
        copy.metaAllowEditorText = metaAllowEditorText;
        copy.metaMaxChars = metaMaxChars;
        copy.setTemperature(temperature);
        copy.setTimeout(getTimeout());
        copy.setLoggingEnabled(isLoggingEnabled());
        copy.setGlobal(isGlobal());
        return copy;
    }

    static boolean isEmpty(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
