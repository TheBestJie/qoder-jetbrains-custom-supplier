package com.alibabacloud.intellij.qoder.util;

import com.alibabacloud.intellij.qoder.common.ByokModelConfig;
import com.alibabacloud.intellij.qoder.common.CosySetting;
import com.alibabacloud.intellij.qoder.common.ModelClassOptionSelection;
import com.alibabacloud.intellij.qoder.core.lsp.params.CheckByokConfigParam;
import com.alibabacloud.intellij.qoder.core.lsp.params.CustomModelParam;
import com.alibabacloud.intellij.qoder.core.lsp.result.ByokDisplayName;
import com.alibabacloud.intellij.qoder.core.lsp.result.ByokField;
import com.alibabacloud.intellij.qoder.core.lsp.result.ByokProvider;
import com.alibabacloud.intellij.qoder.core.lsp.result.ByokProviderType;
import com.alibabacloud.intellij.qoder.core.lsp.result.ChatModelClassResult;
import com.alibabacloud.intellij.qoder.core.lsp.result.CheckByokConfigResult;
import com.alibabacloud.intellij.qoder.service.ApplicationServices;
import com.alibabacloud.intellij.qoder.ui.components.model.CustomModelItem;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Adds a synthetic "custom provider" (user supplied Base URL + API Key + wire format) to the
 * server driven BYOK provider catalog of the Qoder plugin.
 *
 * <p>This class contains the only new code of the patch. Everything else reuses the plugin's own
 * machinery: {@code ByokConfigResult}, {@code AddModelDialog}, {@code ByokSecureStorage},
 * {@code CustomModelItem}, {@code ChatTaskUtil}, ...
 *
 * <p>Injected by patching four existing methods (see {@code _patch/tools/Patcher.java}):
 * <ul>
 *   <li>{@code ByokConfigResult#getProviders()} -&gt; {@link #augment(List)}</li>
 *   <li>{@code CustomModelParam#<init>} -&gt; {@link #isCustomProvider(String)}, {@link #resolveUrl},
 *       {@link #resolveFormat(String)}, {@link #resolveSource()}, {@link #resolveType(String)}</li>
 *   <li>{@code AddModelDialog#updateModelCheckBoxes} -&gt; {@link #isCustomProvider(String)}</li>
 *   <li>{@code ByokConfigService#checkByokConfig} -&gt; {@link #isCustomCheck(CheckByokConfigParam)},
 *       {@link #localCheck(CheckByokConfigParam)}</li>
 * </ul>
 *
 * <p>Optional tuning file (no re-patching needed), all keys optional:
 * <pre>
 * ~/.qoder/custom-endpoint.properties
 *   enabled=true|false
 *   providerKey=custom-endpoint
 *   source=custom
 *   sendType=false
 *   format.openai=openai
 *   format.openaiResponses=openai-responses
 *   format.anthropic=anthropic
 * </pre>
 */
public final class CustomEndpointProvider {

    private static final Logger LOG = Logger.getInstance(CustomEndpointProvider.class);

    /** Fallback provider key; can be overridden through the optional properties file. */
    public static final String DEFAULT_PROVIDER_KEY = "custom-endpoint";

    /** UI type keys (stored in {@code ByokModelConfig.byokTypeKey}). */
    private static final String TYPE_KEY_OPENAI = "openai-compatible";
    private static final String TYPE_KEY_RESPONSES = "openai-responses";
    private static final String TYPE_KEY_ANTHROPIC = "anthropic";

    private static final String[] URL_KEYS = {"base_url", "url", "api_base", "baseUrl", "endpoint"};
    private static final String[] KEY_KEYS = {"api_key", "apikey", "key", "token", "access_key"};

    private static final Properties PROPS = loadProperties();

    private static final boolean ENABLED = boolProp("enabled", true);
    private static final String PROVIDER_KEY = strProp("providerKey", DEFAULT_PROVIDER_KEY);
    private static final String SOURCE = strProp("source", "custom");
    private static final boolean SEND_TYPE = boolProp("sendType", false);
    private static final String FMT_OPENAI = strProp("format.openai", "openai");
    private static final String FMT_RESPONSES = strProp("format.openaiResponses", "openai-responses");
    private static final String FMT_ANTHROPIC = strProp("format.anthropic", "anthropic");

    // runtime option templates (context window / thinking effort / modality) for custom models
    public static final String VL_SWITCH_KEY = "vl";
    private static final int[] CONTEXT_PRESET_TOKENS = intArrayProp("context.options",
            new int[]{32768, 65536, 131072, 262144, 524288, 1048576});
    private static final int DEFAULT_CONTEXT_TOKENS = intProp("context.default", 131072);
    private static final boolean DEFAULT_THINKING_ENABLED = boolProp("thinking.defaultEnabled", false);
    private static final String DEFAULT_THINKING_EFFORT = strProp("thinking.defaultEffort", "medium");
    private static final String[] THINKING_EFFORT_KEYS = listProp("thinking.efforts",
            new String[]{"low", "medium", "high"});
    private static final boolean DEFAULT_VL = boolProp("multimodal.default", false);
    private static final Boolean FORCE_VL = boolPropOrNull("multimodal.force");
    private static final String VL_LABEL = strProp("multimodal.label", "多模态输入 / Multimodal input");
    private static final String VL_ON_LABEL = strProp("multimodal.on", "支持图片 / With images");
    private static final String VL_OFF_LABEL = strProp("multimodal.off", "仅文本 / Text only");

    private static final int CONNECT_TIMEOUT_SECONDS = 20;
    private static final int REQUEST_TIMEOUT_SECONDS = 45;

    private static volatile ByokProvider cachedProvider;
    private static volatile HttpClient httpClient;

    private CustomEndpointProvider() {
    }

    // ------------------------------------------------------------------
    // 1. provider catalog injection
    // ------------------------------------------------------------------

    /** Appends the synthetic custom provider to the server supplied provider list. */
    public static List<ByokProvider> augment(List<ByokProvider> original) {
        if (!ENABLED) {
            return original;
        }
        List<ByokProvider> result = new ArrayList<>();
        boolean alreadyThere = false;
        if (original != null) {
            for (ByokProvider p : original) {
                if (p == null) {
                    continue;
                }
                if (PROVIDER_KEY.equals(p.getKey())) {
                    alreadyThere = true;
                    continue;
                }
                result.add(p);
            }
        }
        if (!alreadyThere) {
            result.add(customProvider());
        }
        return result;
    }

    public static ByokProvider customProvider() {
        ByokProvider cached = cachedProvider;
        if (cached != null) {
            return cached;
        }
        ByokProvider provider = new ByokProvider();
        provider.setKey(PROVIDER_KEY);
        provider.setEnabled(Boolean.TRUE);
        // MUST stay "predefined": the plugin treats source == "custom" as an enterprise provider,
        // which would hide the type selector and disable free form model names.
        provider.setSource("predefined");
        provider.setDisplayName(display("Custom Provider (OpenAI / Anthropic)", "自定义供应商 (OpenAI / Anthropic)"));

        List<ByokField> fields = new ArrayList<>();
        fields.add(field("base_url", "Base URL", "接口地址 (Base URL)"));
        fields.add(field("api_key", "API Key", "API Key"));
        provider.setFields(fields);

        List<ByokProviderType> types = new ArrayList<>();
        types.add(type(TYPE_KEY_OPENAI, "OpenAI Compatible", "OpenAI 兼容"));
        types.add(type(TYPE_KEY_RESPONSES, "OpenAI Responses", "OpenAI Responses"));
        types.add(type(TYPE_KEY_ANTHROPIC, "Anthropic Messages", "Anthropic Messages"));
        provider.setTypes(types);

        // no preset models on purpose: the "Input Model Name" entry is used instead
        provider.setModels(new ArrayList<>());

        cachedProvider = provider;
        LOG.info("[custom-endpoint] synthetic BYOK provider registered, key=" + PROVIDER_KEY
                + ", source=predefined, types=" + types.size());
        return provider;
    }

    private static ByokField field(String key, String en, String zh) {
        ByokField f = new ByokField();
        f.setKey(key);
        f.setType("free_input");
        f.setDisplayName(display(en, zh));
        return f;
    }

    private static ByokProviderType type(String key, String en, String zh) {
        ByokProviderType t = new ByokProviderType();
        t.setKey(key);
        t.setDisplayName(display(en, zh));
        t.setModels(new ArrayList<>());
        return t;
    }

    private static ByokDisplayName display(String en, String zh) {
        ByokDisplayName dn = new ByokDisplayName();
        dn.setEnUs(en);
        dn.setCnZh(zh);
        return dn;
    }

    // ------------------------------------------------------------------
    // 2. request decoration (CustomModelParam constructor)
    // ------------------------------------------------------------------

    public static boolean isCustomProvider(String providerKey) {
        return ENABLED && providerKey != null && PROVIDER_KEY.equals(providerKey.trim());
    }

    /** Wire format the server should use for the selected UI type. */
    public static String resolveFormat(String typeKey) {
        String key = typeKey == null ? "" : typeKey.trim().toLowerCase(Locale.ROOT);
        if (TYPE_KEY_ANTHROPIC.equals(key)) {
            return FMT_ANTHROPIC;
        }
        if (TYPE_KEY_RESPONSES.equals(key)) {
            return FMT_RESPONSES;
        }
        if (TYPE_KEY_OPENAI.equals(key)) {
            return FMT_OPENAI;
        }
        return FMT_OPENAI;
    }

    /** Routing source forwarded to the server (default: "custom"). */
    public static String resolveSource() {
        return SOURCE;
    }

    /** Plan type of the provider. Custom endpoints have none, unless sendType=true. */
    public static String resolveType(String originalType) {
        return SEND_TYPE ? originalType : null;
    }

    /**
     * Base URL of the custom endpoint, taken from the freshly entered parameters first and from the
     * persisted model configuration as fallback.
     */
    public static String resolveUrl(String provider, String model, Map<String, String> parameters) {
        if (!isCustomProvider(provider)) {
            return null;
        }
        String raw = firstNonBlank(parameters, URL_KEYS);
        if (raw == null) {
            raw = urlFromSettings(provider, model);
        }
        if (raw == null) {
            LOG.warn("[custom-endpoint] no base URL found for provider=" + provider + ", model=" + model);
            return null;
        }
        String normalized = normalizeBaseUrl(raw);
        LOG.info("[custom-endpoint] resolved endpoint, model=" + model + ", url=" + normalized);
        return normalized;
    }

    private static String urlFromSettings(String provider, String model) {
        try {
            ByokModelConfig config = findModelConfig(provider, model);
            if (config == null) {
                return null;
            }
            String extra = config.getExtraFields();
            if (extra == null || extra.trim().isEmpty() || "{}".equals(extra.trim())) {
                return null;
            }
            JsonObject json = JsonUtil.parseObject(extra);
            if (json == null) {
                return null;
            }
            for (String key : URL_KEYS) {
                JsonElement element = json.get(key);
                if (element != null && !element.isJsonNull()) {
                    String value = element.getAsString();
                    if (value != null && !value.trim().isEmpty()) {
                        return value.trim();
                    }
                }
            }
        } catch (Throwable t) {
            LOG.warn("[custom-endpoint] failed to read endpoint from settings: " + t.getMessage(), t);
        }
        return null;
    }

    /** Looks up the persisted configuration of a custom model by provider + model key. */
    private static ByokModelConfig findModelConfig(String provider, String model) {
        if (provider == null) {
            return null;
        }
        try {
            CosySetting state = ApplicationServices.persistentSetting().getState();
            if (state == null) {
                return null;
            }
            List<ByokModelConfig> configs = state.getByokModelConfigs();
            if (configs == null) {
                return null;
            }
            for (ByokModelConfig config : configs) {
                if (config == null || !provider.equals(config.getProviderKey())) {
                    continue;
                }
                if (model == null || model.equals(config.getModelKey())) {
                    return config;
                }
            }
        } catch (Throwable t) {
            LOG.warn("[custom-endpoint] failed to look up model config: " + t, t);
        }
        return null;
    }

    /** Looks up the persisted configuration of a custom model by its custom model id. */
    private static ByokModelConfig findModelConfigById(String customModelId) {
        if (customModelId == null || customModelId.trim().isEmpty()) {
            return null;
        }
        try {
            CosySetting state = ApplicationServices.persistentSetting().getState();
            if (state == null) {
                return null;
            }
            List<ByokModelConfig> configs = state.getByokModelConfigs();
            if (configs == null) {
                return null;
            }
            for (ByokModelConfig config : configs) {
                if (config == null) {
                    continue;
                }
                String id = config.getCustomModelId();
                if (customModelId.equals(id)) {
                    return config;
                }
            }
        } catch (Throwable t) {
            LOG.warn("[custom-endpoint] failed to look up model config by id: " + t, t);
        }
        return null;
    }

    /** Removes a pasted endpoint suffix and appends {@code /v1} when the URL carries no path. */
    public static String normalizeBaseUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) {
            return url;
        }
        url = stripTrailingSlashes(url);
        String[] suffixes = {"/chat/completions", "/v1/messages", "/responses", "/completions", "/messages"};
        String lower = url.toLowerCase(Locale.ROOT);
        for (String suffix : suffixes) {
            if (lower.endsWith(suffix)) {
                url = url.substring(0, url.length() - suffix.length());
                break;
            }
        }
        url = stripTrailingSlashes(url);
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                url = url + "/v1";
            }
        } catch (Throwable ignore) {
            // leave the URL untouched; the request itself will report the problem
        }
        return stripTrailingSlashes(url);
    }

    private static String stripTrailingSlashes(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 2b. runtime option templates (context window / thinking / modality)
    // ------------------------------------------------------------------

    /**
     * Gives a locally built custom model class the runtime option template that the native
     * "edit" popup (context window, thinking effort) and the modality switch need.
     * Only custom models of our synthetic provider are touched.
     */
    public static void fillCustomModelMeta(ChatModelClassResult meta, CustomModelItem item) {
        if (meta == null || item == null) {
            return;
        }
        try {
            if (!isCustomProvider(item.getProviderKey())) {
                return;
            }
            if (meta.getContextConfig() == null || meta.getContextConfig().isEmpty()) {
                meta.setContextConfig(defaultContextConfig());
            }
            if (meta.getThinkingConfig() == null) {
                meta.setThinkingConfig(defaultThinkingConfig());
            }
            if (meta.getFeatureSwitches() == null || meta.getFeatureSwitches().isEmpty()) {
                meta.setFeatureSwitches(defaultFeatureSwitches());
            }
            meta.setMultiModalSupported(Boolean.valueOf(DEFAULT_VL));
            Boolean selectedVl = resolveVlFromItem(item);
            if (selectedVl != null) {
                meta.setMultiModalSupported(selectedVl);
            }
            Integer maxInput = meta.getMaxInputTokens();
            if (maxInput == null || maxInput.intValue() <= 0) {
                meta.setMaxInputTokens(Integer.valueOf(DEFAULT_CONTEXT_TOKENS));
            }
            meta.setEditable(Boolean.TRUE);
            LOG.info("[custom-endpoint] custom model meta prepared: model=" + item.getModelKey()
                    + ", contextOptions=" + defaultContextConfig().size()
                    + ", editable=true");
        } catch (Throwable t) {
            LOG.warn("[custom-endpoint] failed to prepare custom model options: " + t, t);
        }
    }

    /** True when the model class belongs to a locally defined custom model of our provider. */
    public static boolean isCustomModelClass(ChatModelClassResult meta) {
        if (meta == null) {
            return false;
        }
        try {
            String customModelId = CustomModelIdUtil.parseModelClassId(meta.getName());
            if (customModelId == null || customModelId.trim().isEmpty()) {
                return false;
            }
            ByokModelConfig config = findModelConfigById(customModelId);
            return config != null && isCustomProvider(config.getProviderKey());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Keeps the request free of a reasoning_effort value while thinking is switched off for a
     * custom model (the built-in logic would send "none" for every model).
     */
    public static String reasoningEffortForCustomModel(ChatModelClassResult meta,
                                                       ModelClassOptionSelection selection) {
        if (selection == null) {
            return null;
        }
        if (!Boolean.TRUE.equals(selection.getThinkingEnabled())) {
            return null;
        }
        String effort = selection.getThinkingEffort();
        return effort == null || effort.trim().isEmpty() ? null : effort.trim();
    }

    /** Modality of the custom model, taken from the model card switch. */
    public static Boolean resolveVl(String provider, String model, Boolean fallback) {
        if (!isCustomProvider(provider)) {
            return fallback;
        }
        Boolean selected = resolveVlFromConfig(provider, model);
        if (FORCE_VL != null) {
            LOG.info("[custom-endpoint] resolve vl: provider=" + provider + ", model=" + model
                    + ", selected=" + selected + ", forcedByConfig=" + FORCE_VL);
            return FORCE_VL;
        }
        Boolean result = selected != null ? selected : (fallback != null ? fallback : Boolean.valueOf(DEFAULT_VL));
        LOG.info("[custom-endpoint] resolve vl: provider=" + provider + ", model=" + model
                + ", selected=" + selected + ", storedConfig=" + fallback + ", sent=" + result);
        return result;
    }

    /** Logs the exact custom model payload that is handed to the agent/server for a request. */
    public static void logCustomModelParam(CustomModelParam param) {
        if (param == null || !isCustomProvider(param.getProvider())) {
            return;
        }
        try {
            LOG.info("[custom-endpoint] custom model param sent: " + JsonUtil.toJSONString(param));
        } catch (Throwable t) {
            LOG.warn("[custom-endpoint] failed to log custom model param: " + t, t);
        }
    }

    /** Context window of the custom model, taken from the context selection of the model card. */
    public static Integer resolveMaxInputTokens(String provider, String model, Integer fallback) {
        if (!isCustomProvider(provider)) {
            return fallback;
        }
        try {
            ByokModelConfig config = findModelConfig(provider, model);
            if (config != null) {
                ModelClassOptionSelection selection = savedSelection(config.getCustomModelId());
                if (selection != null && selection.getContextTokenCount() != null
                        && selection.getContextTokenCount().intValue() > 0) {
                    return selection.getContextTokenCount();
                }
                if (config.getMaxInputTokens() != null && config.getMaxInputTokens().intValue() > 0) {
                    return config.getMaxInputTokens();
                }
            }
        } catch (Throwable t) {
            LOG.warn("[custom-endpoint] failed to resolve max input tokens: " + t, t);
        }
        if (fallback != null && fallback.intValue() > 0) {
            return fallback;
        }
        return Integer.valueOf(DEFAULT_CONTEXT_TOKENS);
    }

    /** Thinking effort of the custom model, taken from the thinking selection of the model card. */
    public static String resolveReasoningEffort(String provider, String model, String fallback) {
        if (!isCustomProvider(provider)) {
            return fallback;
        }
        try {
            ByokModelConfig config = findModelConfig(provider, model);
            if (config != null) {
                return reasoningEffortForCustomModel(null, savedSelection(config.getCustomModelId()));
            }
        } catch (Throwable t) {
            LOG.warn("[custom-endpoint] failed to resolve reasoning effort: " + t, t);
        }
        return fallback;
    }

    private static Boolean resolveVlFromItem(CustomModelItem item) {
        return resolveVlFromConfig(item.getProviderKey(), item.getModelKey());
    }

    private static Boolean resolveVlFromConfig(String provider, String model) {
        try {
            ByokModelConfig config = findModelConfig(provider, model);
            if (config == null) {
                return null;
            }
            ModelClassOptionSelection selection = savedSelection(config.getCustomModelId());
            if (selection == null || selection.getFunctionSwitches() == null) {
                return null;
            }
            String raw = selection.getFunctionSwitches().get(VL_SWITCH_KEY);
            if (raw == null || raw.trim().isEmpty()) {
                return null;
            }
            return Boolean.valueOf(Boolean.parseBoolean(raw.trim()));
        } catch (Throwable t) {
            LOG.warn("[custom-endpoint] failed to resolve modality: " + t, t);
            return null;
        }
    }

    private static ModelClassOptionSelection savedSelection(String customModelId) {
        if (customModelId == null || customModelId.trim().isEmpty()) {
            return null;
        }
        return ModelClassOptionUtil.getSavedSelection(CustomModelIdUtil.toModelClassId(customModelId));
    }

    private static java.util.Map<String, ChatModelClassResult.ContextConfigItem> defaultContextConfig() {
        java.util.Map<String, ChatModelClassResult.ContextConfigItem> result = new java.util.LinkedHashMap<>();
        boolean defaultMarked = false;
        for (int tokens : CONTEXT_PRESET_TOKENS) {
            ChatModelClassResult.ContextConfigItem item = new ChatModelClassResult.ContextConfigItem();
            item.setLabel(formatTokenLabel(tokens));
            item.setTokenCount(Integer.valueOf(tokens));
            boolean isDefault = !defaultMarked && tokens == DEFAULT_CONTEXT_TOKENS;
            item.setDefault(Boolean.valueOf(isDefault));
            if (isDefault) {
                defaultMarked = true;
            }
            result.put(formatTokenLabel(tokens), item);
        }
        if (!defaultMarked && !result.isEmpty()) {
            String firstKey = result.keySet().iterator().next();
            result.get(firstKey).setDefault(Boolean.TRUE);
        }
        return result;
    }

    private static ChatModelClassResult.ThinkingConfig defaultThinkingConfig() {
        ChatModelClassResult.ThinkingMode disabled = new ChatModelClassResult.ThinkingMode();
        disabled.setDescription("Off");
        disabled.setDefault(Boolean.valueOf(!DEFAULT_THINKING_ENABLED));

        ChatModelClassResult.ThinkingEnabled enabled = new ChatModelClassResult.ThinkingEnabled();
        enabled.setDescription("On");
        enabled.setDefault(Boolean.valueOf(DEFAULT_THINKING_ENABLED));
        java.util.Map<String, ChatModelClassResult.ThinkingEffort> efforts = new java.util.LinkedHashMap<>();
        for (String key : THINKING_EFFORT_KEYS) {
            ChatModelClassResult.ThinkingEffort effort = new ChatModelClassResult.ThinkingEffort();
            effort.setDescription(key);
            effort.setDefault(Boolean.valueOf(key.equalsIgnoreCase(DEFAULT_THINKING_EFFORT)));
            efforts.put(key, effort);
        }
        enabled.setEfforts(efforts);

        ChatModelClassResult.ThinkingConfig config = new ChatModelClassResult.ThinkingConfig();
        config.setDisabled(disabled);
        config.setEnabled(enabled);
        return config;
    }

    private static java.util.List<ChatModelClassResult.FeatureSwitch> defaultFeatureSwitches() {
        ChatModelClassResult.FeatureSwitchOption off = new ChatModelClassResult.FeatureSwitchOption();
        off.setLabel(VL_OFF_LABEL);
        off.setDefault(Boolean.valueOf(!DEFAULT_VL));

        ChatModelClassResult.FeatureSwitchOption on = new ChatModelClassResult.FeatureSwitchOption();
        on.setLabel(VL_ON_LABEL);
        on.setDefault(Boolean.valueOf(DEFAULT_VL));

        java.util.Map<String, ChatModelClassResult.FeatureSwitchOption> options = new java.util.LinkedHashMap<>();
        options.put("false", off);
        options.put("true", on);

        ChatModelClassResult.FeatureSwitch featureSwitch = new ChatModelClassResult.FeatureSwitch();
        featureSwitch.setKey(VL_SWITCH_KEY);
        featureSwitch.setType("bool");
        featureSwitch.setLabel(VL_LABEL);
        featureSwitch.setDescription(VL_LABEL);
        featureSwitch.setOptions(options);

        java.util.List<ChatModelClassResult.FeatureSwitch> switches = new ArrayList<>();
        switches.add(featureSwitch);
        return switches;
    }

    private static String formatTokenLabel(int tokens) {
        if (tokens > 0 && tokens % 1048576 == 0) {
            return (tokens / 1048576) + "M";
        }
        if (tokens > 0 && tokens % 1024 == 0) {
            return (tokens / 1024) + "K";
        }
        return String.valueOf(tokens);
    }

    // ------------------------------------------------------------------
    // 3. credential check (replaces the cloud side "checkByokConfig" call)
    // ------------------------------------------------------------------

    public static boolean isCustomCheck(CheckByokConfigParam param) {
        return param != null && isCustomProvider(param.getProvider());
    }

    /**
     * Performs a minimal real request against the configured endpoint so that URL, API key and model
     * name are all validated locally, without involving the Qoder backend (which does not know about
     * user defined providers).
     */
    public static CheckByokConfigResult localCheck(CheckByokConfigParam param) {
        String model = param == null ? null : param.getModel();
        Map<String, String> parameters = param == null ? null : param.getParameters();
        String url = firstNonBlank(parameters, URL_KEYS);
        String apiKey = firstNonBlank(parameters, KEY_KEYS);
        String format = resolveFormat(param == null ? null : param.getType());

        if (url == null) {
            return failure("Base URL 不能为空 / Base URL is required");
        }
        if (apiKey == null) {
            return failure("API Key 不能为空 / API Key is required");
        }

        String base = normalizeBaseUrl(url);
        String requestUrl;
        String body;
        String contentType = "application/json";

        if (FMT_ANTHROPIC.equalsIgnoreCase(format)) {
            requestUrl = base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
            body = "{\"model\":\"" + jsonEscape(model) + "\",\"max_tokens\":1,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}";
        } else if (FMT_RESPONSES.equalsIgnoreCase(format)) {
            requestUrl = base + "/responses";
            body = "{\"model\":\"" + jsonEscape(model) + "\",\"input\":\"ping\",\"max_output_tokens\":16}";
        } else {
            requestUrl = base + "/chat/completions";
            body = "{\"model\":\"" + jsonEscape(model) + "\",\"max_tokens\":1,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}";
        }

        LOG.info("[custom-endpoint] local credential check: url=" + requestUrl + ", format=" + format
                + ", model=" + model);

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

            if (FMT_ANTHROPIC.equalsIgnoreCase(format)) {
                builder.header("x-api-key", apiKey);
                builder.header("anthropic-version", "2023-06-01");
            } else {
                builder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient().send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                LOG.info("[custom-endpoint] local credential check succeeded: url=" + requestUrl + ", http=" + code);
                return success();
            }

            String detail = snippet(response.body());
            LOG.warn("[custom-endpoint] local credential check failed: url=" + requestUrl + ", http=" + code
                    + ", body=" + detail);
            if (code == 401 || code == 403) {
                return failure("认证失败 (HTTP " + code + ")：请检查 API Key。" + detail);
            }
            if (code == 404 || code == 405) {
                return failure("接口地址不可用 (HTTP " + code + ")：请检查 Base URL 是否正确。" + detail);
            }
            if (code == 400 || code == 422) {
                return failure("服务拒绝请求 (HTTP " + code + ")：请检查模型名称与接口格式。" + detail);
            }
            return failure("服务返回 HTTP " + code + detail);
        } catch (java.net.http.HttpTimeoutException e) {
            LOG.warn("[custom-endpoint] local credential check timed out: url=" + requestUrl, e);
            return failure("请求超时：无法在 " + REQUEST_TIMEOUT_SECONDS + " 秒内连接 " + requestUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure("校验被中断");
        } catch (Throwable t) {
            LOG.warn("[custom-endpoint] local credential check error: url=" + requestUrl, t);
            String message = t.getMessage();
            return failure("无法连接 " + requestUrl + "：" + t.getClass().getSimpleName()
                    + (message == null ? "" : " - " + message));
        }
    }

    private static HttpClient httpClient() {
        HttpClient client = httpClient;
        if (client == null) {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build();
            httpClient = client;
        }
        return client;
    }

    private static CheckByokConfigResult success() {
        CheckByokConfigResult result = new CheckByokConfigResult();
        result.setSuccess(Boolean.TRUE);
        return result;
    }

    private static CheckByokConfigResult failure(String message) {
        CheckByokConfigResult result = new CheckByokConfigResult();
        result.setSuccess(Boolean.FALSE);
        result.setErrorCode(null);
        result.setErrorMsg(message);
        return result;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String firstNonBlank(Map<String, String> map, String[] keys) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            String value = map.get(key);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            for (String key : keys) {
                if (name.equals(key.toLowerCase(Locale.ROOT))) {
                    String value = entry.getValue();
                    if (value != null && !value.trim().isEmpty()) {
                        return value.trim();
                    }
                }
            }
        }
        return null;
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static String snippet(String body) {
        if (body == null || body.trim().isEmpty()) {
            return "";
        }
        String trimmed = body.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 300 ? " " + trimmed.substring(0, 300) + "..." : " " + trimmed;
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        List<Path> candidates = new ArrayList<>();
        String explicit = System.getProperty("qoder.customEndpoint.config");
        if (explicit != null && !explicit.trim().isEmpty()) {
            candidates.add(Paths.get(explicit.trim()));
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            candidates.add(Paths.get(home, ".qoder", "custom-endpoint.properties"));
            candidates.add(Paths.get(home, ".qoder-custom-endpoint.properties"));
        }
        for (Path path : candidates) {
            try {
                if (Files.isRegularFile(path)) {
                    try (InputStream in = Files.newInputStream(path)) {
                        properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                    }
                    System.out.println("[custom-endpoint] loaded configuration from " + path);
                    return properties;
                }
            } catch (Throwable t) {
                System.err.println("[custom-endpoint] failed to read " + path + ": " + t);
            }
        }
        return properties;
    }

    private static String strProp(String key, String fallback) {
        String value = PROPS.getProperty(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static boolean boolProp(String key, boolean fallback) {
        String value = PROPS.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /** Returns null when the property is absent or set to "auto". */
    private static Boolean boolPropOrNull(String key) {
        String value = PROPS.getProperty(key);
        if (value == null || value.trim().isEmpty() || "auto".equalsIgnoreCase(value.trim())) {
            return null;
        }
        return Boolean.valueOf(Boolean.parseBoolean(value.trim()));
    }

    private static int intProp(String key, int fallback) {
        String value = PROPS.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int[] intArrayProp(String key, int[] fallback) {
        String value = PROPS.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        List<Integer> parsed = new ArrayList<>();
        for (String part : value.split(",")) {
            try {
                int number = Integer.parseInt(part.trim());
                if (number > 0) {
                    parsed.add(Integer.valueOf(number));
                }
            } catch (NumberFormatException ignore) {
                // skip malformed entry
            }
        }
        if (parsed.isEmpty()) {
            return fallback;
        }
        int[] result = new int[parsed.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = parsed.get(i).intValue();
        }
        return result;
    }

    private static String[] listProp(String key, String[] fallback) {
        String value = PROPS.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        List<String> parsed = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parsed.add(trimmed);
            }
        }
        return parsed.isEmpty() ? fallback : parsed.toArray(new String[0]);
    }

    static {
        LOG.info("[custom-endpoint] patch active: enabled=" + ENABLED + ", providerKey=" + PROVIDER_KEY
                + ", source=" + SOURCE + ", sendType=" + SEND_TYPE
                + ", formats=" + FMT_OPENAI + "/" + FMT_RESPONSES + "/" + FMT_ANTHROPIC
                + ", contextDefault=" + DEFAULT_CONTEXT_TOKENS
                + ", thinkingDefault=" + (DEFAULT_THINKING_ENABLED ? DEFAULT_THINKING_EFFORT : "off")
                + ", multimodalDefault=" + DEFAULT_VL);
    }
}
