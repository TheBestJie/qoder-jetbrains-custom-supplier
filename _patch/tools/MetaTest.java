import com.alibabacloud.intellij.qoder.common.ModelClassOptionSelection;
import com.alibabacloud.intellij.qoder.core.lsp.result.ByokDisplayName;
import com.alibabacloud.intellij.qoder.core.lsp.result.ChatModelClassResult;
import com.alibabacloud.intellij.qoder.ui.components.model.CustomModelItem;
import com.alibabacloud.intellij.qoder.util.CustomEndpointProvider;
import com.alibabacloud.intellij.qoder.util.ModelClassOptionUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline behaviour test for the custom model option templates.
 *
 * Verifies that a locally built custom model class becomes editable and carries the expected
 * context / thinking / modality options - without starting an IDE.
 *
 * usage: MetaTest
 */
public final class MetaTest {

    private static int failures = 0;

    public static void main(String[] args) {
        try {
            testCustomModelGetsEditableTemplate();
            testNonCustomProviderUntouched();
            testReasoningEffortMapping();
        } catch (Throwable t) {
            t.printStackTrace();
            failures++;
        }
        System.out.println(failures == 0 ? "META TEST OK" : "META TEST FAILURES: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void testCustomModelGetsEditableTemplate() {
        CustomModelItem item = item("custom-endpoint", "gpt-5.6-sol", "model_test1");
        ChatModelClassResult meta = new ChatModelClassResult();
        meta.setName("custom:model_test1");

        CustomEndpointProvider.fillCustomModelMeta(meta, item);

        check("contextConfig provided", meta.getContextConfig() != null && meta.getContextConfig().size() == 6);
        check("thinkingConfig provided", meta.getThinkingConfig() != null);
        check("thinking toggle options", meta.getThinkingConfig().getEnabled() != null
                && meta.getThinkingConfig().getDisabled() != null);
        check("feature switch provided", meta.getFeatureSwitches() != null && meta.getFeatureSwitches().size() == 1);
        check("feature switch is bool 'vl'", "vl".equals(meta.getFeatureSwitches().get(0).getKey())
                && "bool".equals(meta.getFeatureSwitches().get(0).getType()));
        check("modal default off", Boolean.FALSE.equals(meta.getMultiModalSupported()));
        check("editable flag set", Boolean.TRUE.equals(meta.isEditable()));
        check("maxInputTokens fallback", meta.getMaxInputTokens() != null && meta.getMaxInputTokens() == 131072);

        // ModelClassOptionUtil.hasEditableOptions is the gate of the "edit" label
        check("hasEditableOptions == true", ModelClassOptionUtil.hasEditableOptions(meta));

        List<ModelClassOptionUtil.ContextOption> options = ModelClassOptionUtil.getSortedContextOptions(meta);
        check("6 context options", options.size() == 6);
        int defaults = 0;
        String defaultKey = null;
        for (ModelClassOptionUtil.ContextOption option : options) {
            if (option.isDefault()) {
                defaults++;
                defaultKey = option.getKey();
            }
            System.out.println("    context option: " + option.getKey() + " -> " + option.getTokenCount()
                    + (option.isDefault() ? " (default)" : ""));
        }
        check("exactly one default context option", defaults == 1);
        check("default context option is 128K", "128K".equals(defaultKey));
        check("formatContextOption works", "128K".equals(ModelClassOptionUtil.formatContextOption(options.get(2)))
                || ModelClassOptionUtil.formatContextOption(options.get(2)).endsWith("K"));

        // effort rows (needs the IDE text service, may be unavailable outside the IDE)
        try {
            List<ModelClassOptionUtil.ThinkingEffortOption> efforts = ModelClassOptionUtil.getSortedThinkingEfforts(meta);
            check("3 thinking efforts", efforts.size() == 3);
            for (ModelClassOptionUtil.ThinkingEffortOption effort : efforts) {
                System.out.println("    thinking effort: " + effort.getKey());
            }
        } catch (Throwable t) {
            System.out.println("    [warn] thinking effort rows unavailable outside the IDE: " + t);
        }

        // the summary row logic for thinking
        System.out.println("    thinking summary (default selection): "
                + safeThinkingSummary(meta, "custom:model_test1"));
    }

    private static String safeThinkingSummary(ChatModelClassResult meta, String modelClassId) {
        try {
            ModelClassOptionSelection selection = ModelClassOptionUtil.getEffectiveSelection(meta, modelClassId);
            if (selection == null) {
                return "<none>";
            }
            String effort = CustomEndpointProvider.reasoningEffortForCustomModel(meta, selection);
            return "thinkingEnabled=" + selection.getThinkingEnabled()
                    + ", effort=" + selection.getThinkingEffort()
                    + ", sentReasoningEffort=" + effort;
        } catch (Throwable t) {
            return "<unavailable: " + t + ">";
        }
    }

    private static void testNonCustomProviderUntouched() {
        CustomModelItem item = item("deepseek", "deepseek-chat", "model_other");
        ChatModelClassResult meta = new ChatModelClassResult();
        meta.setName("custom:model_other");
        CustomEndpointProvider.fillCustomModelMeta(meta, item);
        check("other provider: no context template", meta.getContextConfig() == null);
        check("other provider: not marked editable", meta.isEditable() == null);
    }

    private static void testReasoningEffortMapping() {
        ChatModelClassResult meta = new ChatModelClassResult();
        meta.setName("custom:model_test1");

        ModelClassOptionSelection disabled = new ModelClassOptionSelection();
        disabled.setThinkingEnabled(Boolean.FALSE);
        disabled.setThinkingEffort("medium");
        check("thinking off -> no reasoning_effort",
                CustomEndpointProvider.reasoningEffortForCustomModel(meta, disabled) == null);

        ModelClassOptionSelection enabled = new ModelClassOptionSelection();
        enabled.setThinkingEnabled(Boolean.TRUE);
        enabled.setThinkingEffort("high");
        check("thinking on -> high",
                "high".equals(CustomEndpointProvider.reasoningEffortForCustomModel(meta, enabled)));

        ModelClassOptionSelection blank = new ModelClassOptionSelection();
        blank.setThinkingEnabled(Boolean.TRUE);
        blank.setThinkingEffort("  ");
        check("thinking on without effort -> null",
                CustomEndpointProvider.reasoningEffortForCustomModel(meta, blank) == null);
    }

    private static CustomModelItem item(String providerKey, String modelKey, String customModelId) {
        ByokDisplayName providerName = new ByokDisplayName();
        providerName.setEnUs("Custom Provider");
        providerName.setCnZh("自定义供应商");
        ByokDisplayName modelName = new ByokDisplayName();
        modelName.setEnUs(modelKey);
        modelName.setCnZh(modelKey);
        Map<String, String> extraFields = new HashMap<>();
        extraFields.put("base_url", "https://example.invalid/v1");
        List<String> sensitive = new ArrayList<>();
        sensitive.add("api_key");
        return new CustomModelItem(customModelId, providerKey, providerName, modelName, modelKey, modelName,
                Collections.<String>emptyList(), true, extraFields, sensitive, "openai-compatible",
                Boolean.FALSE, Boolean.FALSE, Integer.valueOf(0), null, null);
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            System.out.println("[ok]   " + name);
        } else {
            failures++;
            System.out.println("[FAIL] " + name);
        }
    }
}
