# Qoder JetBrains 插件 —— 自定义供应商（Custom Provider）补丁

给「设置 → Qoder → 个人设置 → 模型设置 → 添加模型」增加一个**自定义供应商**：自己填 **Base URL + API Key**，自己选**接口格式**（OpenAI 兼容 / OpenAI Responses / Anthropic），模型名自己输入。

并让这些自定义模型在**聊天模型卡片上可编辑**：上下文窗口、思考强度、多模态（图片输入）都能像内置模型一样设置。

改动方式：**字节码补丁**（原插件无源码）。共改 6 个现有方法 + 新增 1 个类，其余全部复用插件已有实现。

---

## 1. 效果

### 添加模型

「供应商」下拉里多出一项：

```
Built-in Providers
  ...
  自定义供应商 (OpenAI / Anthropic)     <- 新增
```

选中后：

| 表单项 | 内容 |
|---|---|
| 类型（接口格式） | `OpenAI 兼容` / `OpenAI Responses` / `Anthropic Messages` |
| Base URL * | 自定义，如 `https://api.deepseek.com` 或 `http://127.0.0.1:1234/v1` |
| API Key * | 自定义，保存进 IDE 的 PasswordSafe（不会明文落盘） |
| 模型 | 点「Input Model Name」自己输入，如 `deepseek-chat` |

点「确定」走**本地校验**（插件直接向该 URL 发一个最小请求验证 URL/Key/模型名），不调用 Qoder 云端。

### 聊天模型卡片上的「编辑」

自定义模型的卡片和内置模型一样会有「编辑」入口与摘要行：

| 配置项 | 选项 | 默认 | 生效方式 |
|---|---|---|---|
| 上下文窗口 | 32K / 64K / 128K / 256K / 512K / 1M | **128K** | `max_input_tokens`（请求级 `model_config` + `CustomModelParam`） |
| 思考强度 | 开 / 关 + low / medium / high | **关**（不下发 `reasoning_effort`） | `reasoning_effort`（开启后才下发） |
| 多模态输入 | 开 / 关 | **关**（显示「暂不支持多模态」） | `is_vl` + 卡片提示 |

选择会持久化在 `CosySetting.chatModelClassOptionSelected`（按模型类 id），随请求下发。
> 「最长输出（max output tokens）」**未实现**：插件与服务端协议里都没有这个字段（详见 §8）。

---

## 2. 文件清单

```
_patch/
├─ src/CustomEndpointProvider.java   # 唯一的新增代码
├─ tools/Patcher.java                # ASM 字节码补丁器（6 个方法 + 追加新类）
├─ tools/VerifyClass.java            # 数据流校验器
├─ tools/LoadAndVerify.java          # JVM 级加载/校验器（-Xverify:all）
├─ tools/MetaTest.java               # 行为级测试（不启动 IDE 验证模型选项模板）
├─ build.ps1                         # 一键：编译 → 打补丁 → 校验 → 行为测试 → 部署
├─ install-to-ide.ps1                # 可选：装进 IDEA
├─ backup/…jar.bak                   # 原始 jar 备份（回滚用）
├─ dist/…jar                         # 补丁产物
├─ classes/ , tools-classes/         # 编译输出
└─ work/                             # 校验/测试的中间产物（可删）
```

补丁后的插件就在：`qoder-jetbrains/lib/instrumented-qoder-core-2026.924.1.jar`

---

## 3. 改了哪 6 个方法

| # | 类 : 方法 | 改动 |
|---|---|---|
| 1 | `core.lsp.result.ByokConfigResult : getProviders()` | 返回 `CustomEndpointProvider.augment(providers)` —— **唯一注入点**：设置页表格、添加对话框、可用性校验、聊天链路读的都是它 |
| 2 | `core.lsp.params.CustomModelParam : <init>(11 参)` | 构造时若 `provider == custom-endpoint`，填 `url / format / source`（默认清空 `type`）、`is_vl`、`max_input_tokens`、`reasoning_effort` |
| 3 | `ui.components.model.AddModelDialog : updateModelCheckBoxes` | 条件改为 `isCustomProvider(providerKey) \|\| supportsFreeFormModelName(...)`，让没有预设模型的自定义供应商也能出现「Input Model Name」 |
| 4 | `service.ByokConfigService : checkByokConfig(CheckByokConfigParam)` | 方法开头拦截自定义供应商 → `localCheck()` 本地校验，不发云端 |
| 5 | `ui.chat.component.chooser.ChatModelClassChooser : buildChatModelClassItemFromConfig` | 返回前调用 `CustomEndpointProvider.fillCustomModelMeta(meta, item)`，给自定义模型补上上下文/思考/多模态模板与 `isEditable=true` |
| 6 | `util.ModelClassOptionUtil : resolveReasoningEffort(meta, selection)` | 自定义模型在「思考关闭」时返回 `null`（不下发 `reasoning_effort`），避免给不支持该参数的接口发 `none` |

**为什么需要 #4**：云端 `/api/v2/byok/check` 不认识自定义供应商，否则点「确定」必报 *Model service connection failed*。

**为什么需要 #5**：编辑按钮的显示条件是 `meta.isSelectable() && ModelClassOptionUtil.hasEditableOptions(meta)`，而
`hasEditableOptions = (meta.isEditable==true && (contextConfig 非空 || thinkingConfig != null)) || featureSwitches 非空`。
自由输入的模型名走 `ByokConfigUtil.createFreeFormModel()`，没有任何选项模板 → 编辑按钮不出现、卡片也没有上下文/思考行。#5 把模板补上后，原生编辑弹窗、选项持久化、请求下发这条**本来通用**的链路就全部可用。

**为什么需要 #6**：内置模型的「思考关闭」会下发 `reasoning_effort=none`；对自定义端点这可能被上游拒绝。我们改成**只在你主动开启思考时才下发档位**。

**为什么 `source` 用 `predefined` 而不是 `custom`**：插件把 `source == "custom"` 视为「企业供应商」，会隐藏类型下拉、禁用自由模型名。

---

## 4. 数据流（复用到的既有逻辑）

```
ByokConfigResult.getProviders()  ←── augment() 注入合成供应商
        │
        ├─ AddModelDialog            供应商下拉 / 类型下拉 / 动态字段(base_url, api_key)
        │      └─ ByokSecureStorage  按字段名自动分流：api_key→PasswordSafe，base_url→extraFields
        ├─ ByokConfigService.checkByokConfig → localCheck()（本地 HTTP 探测）
        ├─ ModelSettingsPanel        落盘到 CosySetting.byokModelConfigs
        ├─ ChatModelClassChooser     ←── fillCustomModelMeta() 补上下文/思考/多模态模板
        │      └─ ChatModelClassDetailPanel「编辑」弹窗（原生单选行 / 开关）
        │             └─ ModelClassOptionUtil.saveSelection("custom:<id>", selection)
        └─ ChatTaskUtil              构造 CustomModelParam + model_config
                ├─ applyOptionSelection → IdeModelConfigOverride{max_input_tokens, reasoning_effort}
                └─ 补丁 2 补上 url/format/source/is_vl/max_input_tokens/reasoning_effort
```

---

## 5. 安装到 IDE

> ⚠️ 插件会自动更新，更新后补丁会被覆盖。

1. 关闭 IntelliJ IDEA；
2. 运行 `_patch\install-to-ide.ps1`（会先把现有插件目录改名备份），或手动：

```powershell
$dst = "$env:APPDATA\JetBrains\IntelliJIdea2026.2\plugins\qoder-jetbrains"
Rename-Item $dst "qoder-jetbrains.bak-2026.1014"
Copy-Item "<项目目录>\qoder-jetbrains" $dst -Recurse
```

3. 打开 IDE → `设置 → 插件` → 关闭 Qoder 的自动更新；
4. 验证：`设置 → Qoder → 个人设置 → 模型设置 → 添加模型` 里能选「自定义供应商」；添加后在聊天模型卡片上悬停应看到「编辑」。

**回滚**：把 `_patch\backup\instrumented-qoder-core-2026.924.1.jar.bak` 复制成 `lib\instrumented-qoder-core-2026.924.1.jar`（或恢复上面备份的插件目录）。

---

## 6. 可选调参文件（不用重新打补丁）

`%USERPROFILE%\.qoder\custom-endpoint.properties`（或 `~/.qoder-custom-endpoint.properties`，或 `-Dqoder.customEndpoint.config=<路径>`）：

```properties
# 请求相关
enabled=true
providerKey=custom-endpoint
source=custom
sendType=false
format.openai=openai
format.openaiResponses=openai-responses
format.anthropic=anthropic

# 上下文窗口档位（token 数，逗号分隔）与默认档
context.options=32768,65536,131072,262144,524288,1048576
context.default=131072

# 思考强度：默认是否开启 / 档位 / 默认档
thinking.defaultEnabled=false
thinking.efforts=low,medium,high
thinking.defaultEffort=medium

# 多模态开关的默认值与显示文案
multimodal.default=false
multimodal.label=多模态输入 / Multimodal input
multimodal.on=支持图片 / With images
multimodal.off=仅文本 / Text only
```

- `source` —— 发给服务端的路由标识。默认 `custom`；若服务端不认，可试 `organization`。
- `sendType` —— 是否把类型 key 作为 `type` 字段一起发（默认不发）。
- `format.*` —— 三个接口格式实际发给服务端 `format` 字段的取值。默认 `openai` 与 `anthropic` 是实测配置里出现过的值；`openai-responses` 是推测值，需实测校准。
- `thinking.efforts` —— 若服务端只认 `high/medium/low` 之外的写法（如 `minimal/xhigh`），在这里改。
- 改完重启 IDE 生效（日志里会打印 `[custom-endpoint] patch active: ...`）。

---

## 7. 校验记录（本次）

| 检查 | 结果 |
|---|---|
| 数据流校验（`Analyzer` + `BasicVerifier`，整个 jar） | 打补丁后 3928 类 **全部 OK** |
| JVM 校验（`-Xverify:all` + 真实插件/IDE classpath，`Class.forName` 触发链接与验证） | 6 个被改类 + 新类 **全部 LOADED**，无 `VerifyError` |
| 类结构对比（javap -v：成员、注解、Signature、BootstrapMethods、InnerClasses、NestMembers） | 6 个类 **完全一致**，只改了方法体 |
| jar 差异对比 | 原 4540 → 4541 条目，仅 6 个类被改 + 1 个新类 |
| 行为级测试（`MetaTest`，不启动 IDE） | **META TEST OK**，20 项断言全过：`hasEditableOptions==true`、6 档上下文（128K 默认）、3 档思考、vl 开关、非本供应商不受影响、思考关闭时不产生 `reasoning_effort` |

---

## 8. 已知风险 / 待实测

1. **服务端是否真的按 `url` 直连**：协议字段、本地 `Qoder.exe` 内同构的 Go 结构体、OpenAI/Anthropic 客户端都在，证据很强；但「provider 非空时是否仍采用 url」必须实跑验证。
   → 若聊天报错，按第 6 节依次试：`source` 换 `organization`、`sendType=true`。
2. **`openai-responses` 的 format 取值**：默认值属推测；若该选项报错，用 `format.openaiResponses=` 改成服务端认识的值。
3. **本地校验会真实调用一次接口**（`max_tokens=1` 级别），可能带来极小额度消耗；目的是同时验证 URL、Key、模型名。
4. **最长输出（max output tokens）未实现**：两端协议都只有 `max_input_tokens`，没有输出上限字段（本地二进制里 `max_output_tokens` 只属于另一套知识库 API）。若一定要，只能塞进 `parameters` 让服务端自行忽略/采纳，本次按你的决定先不做。
5. **思考强度**：默认关闭时不下发 `reasoning_effort`；开启后下发所选档位，若上游接口不认可能报错 —— 关掉即可。
6. **插件自动更新**：更新后补丁失效，重新跑 `build.ps1` 即可（幂等）。若想改到 2026.1014 版本，`Patcher` 是按「方法名 + 指令特征」定位的，通常可直接复用，但必须重跑校验（脚本会自动做）。

---

## 9. 重新构建

```powershell
cd <项目目录>
powershell -ExecutionPolicy Bypass -File .\_patch\build.ps1
```

流程：编译 helper → 编译补丁器 → 打补丁 → 全量数据流校验 → 行为测试 → 部署（任一步失败即中止，不会部署坏包）。

**产物指纹**（用于确认装的是哪一版）：

| 文件 | 大小 | MD5 |
|---|---|---|
| 打补丁后 `instrumented-qoder-core-2026.924.1.jar` | 9,507,729 | `33f6ea7139777828c34e2b7efd2afbd5` |
| 原始备份 `…jar.bak` | 9,420,367 | `da56b8d0b44302f3b236a20c90d278be` |

```powershell
Get-FileHash "$env:APPDATA\JetBrains\IntelliJIdea2026.2\plugins\qoder-jetbrains\lib\instrumented-qoder-core-2026.924.1.jar" -Algorithm MD5
```
