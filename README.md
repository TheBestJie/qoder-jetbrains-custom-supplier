# Qoder JetBrains 插件 · 自定义供应商补丁

给 Qoder 的 JetBrains 插件加上「**自定义供应商**」能力：

- 供应商下拉里多一项「自定义供应商 (OpenAI / Anthropic)」，**Base URL / API Key 自己填，接口格式自己选**（OpenAI 兼容 / OpenAI Responses / Anthropic Messages），**模型名自己输入**，不再受官方预置供应商与模型目录限制；
- 添加模型走**本地校验**（本地直连该 URL 发最小请求），不依赖 Qoder 云端；
- 自定义模型的卡片和内置模型一样可以「编辑」：**上下文窗口（32K~1M）、思考强度（关/低/中/高）、多模态开关**。

- **目标版本**：Qoder JetBrains 插件 `2026.924.75731882`（改动文件只有 `lib/instrumented-qoder-core-2026.924.1.jar`）
- **改动方式**：ASM 字节码补丁（插件无源码）：改 6 个方法 + 新增 1 个辅助类，其余全部复用插件原生实现
- **状态**：自定义供应商、本地校验、模型卡片编辑（上下文 / 思考 / 多模态开关）已验证可用
- ⚠️ **已知问题**：多模态开关打开后，图片仍不会真正传给模型（服务端 `Qoder.exe` 判定环节，排查中，详见下文）

---

## 1. 仓库内容

本仓库**只包含补丁源码 / 脚本 / 文档**，不含任何二进制 jar（`qoder-jetbrains/lib/`、`_patch/backup/`、`_patch/dist/` 已在 `.gitignore` 里）。

```
_patch/
  src/CustomEndpointProvider.java     新增的唯一代码（合成供应商、本地校验、模型选项模板、参数补齐）
  tools/Patcher.java                  ASM 补丁器（6 个补丁点 + 追加新类）
  tools/VerifyClass.java              全量数据流校验器
  tools/LoadAndVerify.java            JVM 级加载/链接校验器（-Xverify:all）
  tools/MetaTest.java                 行为级测试（不启动 IDE）
  tools/proxy.py                      本地抓包代理（排查多模态问题时用）
  tools/prep-proxy-test.py            把某模型的 Base URL 临时指到代理（配合排查）
  build.ps1                           一键：编译 helper → 打补丁 → 全量校验 → 行为测试 → 产出补丁 jar
  install-jar.ps1                     把构建出的核心 jar 装进已安装插件（推荐）
  install-to-ide.ps1                  整目录安装（需要完整的插件目录）
  README.md                           详细文档：原理 / 补丁点 / 数据流 / 调参 / 回滚 / 风险
```

补丁后的产物是 `instrumented-qoder-core-2026.924.1.jar`（md5 `fb205cbf6445a8e2acd2e058c7dc75e0`，9,508,173 字节），
由 `build.ps1` 在你本机构建得到 —— 因为它是**改过的官方二进制**，不适合放仓库。

---

## 2. 快速开始

### 2.1 先构建补丁 jar（需要本机的官方插件做原料）

1. 装好官方 Qoder JetBrains 插件 `2026.924.75731882`（版本必须一致）；
2. 把它 `lib\instrumented-qoder-core-2026.924.1.jar` 复制成 `_patch\backup\instrumented-qoder-core-2026.924.1.jar.bak`（脚本用的"原始 jar"）；
3. 构建（会自动编译 → 打补丁 → 全量字节码校验 → 行为测试，任一步失败即中止）：

```powershell
powershell -ExecutionPolicy Bypass -File .\_patch\build.ps1
```

> 工具链默认写在 `build.ps1` 顶部（IDEA 2026.2.3 / JDK 27 / ASM 用 IDEA 自带的），按需修改。

### 2.2 装进 IDEA（替换核心 jar）

4. **完全关闭 IDEA**；
5. 运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\_patch\install-jar.ps1
```

（脚本会自动在 `%APPDATA%\JetBrains\IntelliJIdea*\plugins` 里找插件目录，覆盖前把原文件备份成 `.orig`，需要时可加 `-PluginDir <路径>`）

6. 启动 IDEA → `设置 → 插件` → **关闭 Qoder 的自动更新**（否则会被官方新版覆盖，补丁失效）；
7. `设置 → Qoder → 个人设置 → 模型设置 → 添加模型` → 供应商选「自定义供应商 (OpenAI / Anthropic)」 → 填 Base URL / API Key / 模型名。

**回滚**：`install-jar.ps1` 备份的 `lib\instrumented-qoder-core-2026.924.1.jar.orig` 改回原名即可（或重新装一次官方插件）。

---

## 3. 校验记录（本产物的构建校验）

| 检查 | 结果 |
|---|---|
| 全 jar 数据流校验（ASM Analyzer + BasicVerifier，3928 个类） | 全部 **OK** |
| JVM `-Xverify:all` 强制加载/链接校验 | 6 个被改类 + 新类全部 **LOADED**，无 `VerifyError` |
| 类结构对比（`javap -v`：成员 / 注解 / Signature / BootstrapMethods / NestMembers） | 6 个类 **完全一致**，只改了方法体 |
| jar 差异 | 仅 6 个类变化 + 1 个新类，无条目丢失 |
| 行为测试（`MetaTest`，不启动 IDE） | 20 项断言全过 |

**产物指纹**：`instrumented-qoder-core-2026.924.1.jar` = `fb205cbf6445a8e2acd2e058c7dc75e0`（9,508,173 字节）

---

## 4. 改了哪 6 个方法

| # | 类 : 方法 | 改动 |
|---|---|---|
| 1 | `core.lsp.result.ByokConfigResult : getProviders()` | 注入合成供应商 `custom-endpoint`（`augment`），设置页 / 添加对话框 / 聊天链路读的都是它 |
| 2 | `core.lsp.params.CustomModelParam : <init>(11 参)` | 自定义供应商时补齐 `url / format / source / type / is_vl / max_input_tokens / reasoning_effort` |
| 3 | `ui.components.model.AddModelDialog : updateModelCheckBoxes` | 让自定义供应商也出现「Input Model Name」自由输入 |
| 4 | `service.ByokConfigService : checkByokConfig(CheckByokConfigParam)` | 自定义供应商改走 `localCheck()` 本地校验，不请求云端 |
| 5 | `ui.chat.component.chooser.ChatModelClassChooser : buildChatModelClassItemFromConfig` | 给自定义模型补上上下文 / 思考 / 多模态模板与 `isEditable=true`（「编辑」入口） |
| 6 | `util.ModelClassOptionUtil : resolveReasoningEffort(meta, selection)` | 自定义模型思考关闭时不下发 `reasoning_effort` |

详细原理、数据流、可调参数（`%USERPROFILE%\.qoder\custom-endpoint.properties`）、风险与实测项见 **`_patch/README.md`**。

---

## 5. 已知问题：多模态（图片）没有真正传给模型

现状与证据：

1. 模型卡片「多模态输入」开关可保存，客户端**确实**把 `is_vl: true` 下发到服务端（实测抓到的请求参数）：

```json
{"provider":"custom-endpoint","model":"gpt-5.6-sol","is_vl":true,"is_reasoning":false,
 "max_input_tokens":524288,"format":"openai-responses","source":"custom",
 "url":"https://<你的中转站>/v1","reasoning_effort":"medium"}
```

2. 但服务端（本地 `Qoder.exe`）仍把用户图片降级成文字占位：

```
[Image]
<image_files>
The following images were provided by the user and saved to the workspace for future use:
1. <本地路径>.png
</image_files>
```

3. 反汇编确认服务端判定函数为 `cosy/agent/handlers.validateModelSupportForVl`（读取 `ai-coding/custom-model` 的
   `is_vl`，结构体偏移 `0x78`；为假时记录 `custom model does not support vision` 并丢弃图片）；
   另一处 `cosy/chat/util.PrepareModelConfig` 会把 `is_vl` 复制进本次模型配置。
4. 下游中转站本身**支持**图片：直接调用（含 `image_url` 多模态内容）可正确识别测试图内容，排除第三方 API 的问题。
5. 已排除 `format`：把模型类型改成「OpenAI 兼容」（下发 `format:"openai"`，即服务端认识的取值）后，
   图片**仍然**进不去模型（模型回复"我用 Read 工具看不了图片文件"）。
6. 抓包结论：**本地服务端并不使用客户端下发的 `url` 去完成模型调用**。做法是把 `url` 指向本地抓包代理
   （`_patch/tools/proxy.py`，只记录并转发）、并杀掉旧服务端进程以清掉内存缓存后重试：
   客户端日志确认下发的就是代理地址，但代理**一个 TCP 连接都没收到**，而聊天照常在数秒内得到回答。
   结合"图片会被上传到 `*.oss-*.aliyuncs.com` 云存储"的现象，推测模型调用（以及是否给未注册的自定义模型附带图片）
   实际由 Qoder 云端决定，插件侧无法左右 —— 因此多模态开关当前只是 UI 层能力，图片不会被真正附带。
7. 排查工具都留在 `_patch/tools/` 下（本地抓包代理、模型地址临时改写/还原、连接监视器），方便以后有新思路时继续。

排查工具已随仓库提供：`_patch/tools/proxy.py`（本地抓包代理，转发到真实中转站并记录服务端→模型的原始请求）
+ `_patch/tools/prep-proxy-test.py`（把指定模型的 Base URL 临时指向代理）。

---

## 6. 免责声明

- 本仓库只包含**补丁源码 / 脚本 / 文档**，不含任何二进制文件；Qoder 插件及其原始文件的版权归 Qoder / Alibaba 所有。
- 补丁仅供个人学习与自用，请自行确认是否符合你所在地的法律与软件许可条款。
- 插件更新会覆盖补丁，需要重新执行 `install-jar.ps1`；使用风险自负。
