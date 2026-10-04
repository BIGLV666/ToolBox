# ToolBox 项目接手说明

> 分析日期：2026-08-05  
> 工程目录：`D:\AndroidProjects\ToolBox`  
> 当前规模：1 个 Android 应用模块、26 个 Java 文件、约 4484 行 Java、13 个布局文件

## 1. 项目概览

ToolBox 是一个原生 Android 本地工具集合，目前包含：

1. 首页与天气卡片
2. 密码管理
3. 多模型 Agent
4. 笔记、文件夹与附件
5. 工具库导航

项目采用 Java + XML View，没有使用 Compose，也没有引入 Room、Retrofit、ViewModel 或依赖注入框架。主要数据保存在同一个 SQLite 数据库 `toolbox.db` 中，网络请求使用 `HttpURLConnection`，异步任务由各 Activity 自己持有的单线程 `ExecutorService` 执行。

## 2. 构建与运行

### 2.1 基础配置

| 项目 | 当前值 |
|---|---|
| applicationId | `com.example.toolbox` |
| namespace | `com.example.toolbox` |
| minSdk | 26 |
| targetSdk | 37 |
| compileSdk | 37 |
| Java | 11 |
| Android Gradle Plugin | 9.3.1 |
| 数据库版本 | 5 |

主要依赖：

- AndroidX AppCompat、Activity KTX、RecyclerView、ConstraintLayout
- Material Components
- Google Play Services Fitness，目前源码中未发现实际使用
- JUnit 4、AndroidX Test、Espresso

### 2.2 常用命令

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
```

本次分析已验证：

- `testDebugUnitTest` 通过
- `assembleDebug` 通过
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`

说明：当前目录没有 `.git`，无法通过提交历史判断功能演进和设计原因。

## 3. 页面与导航

应用入口声明在 `app/src/main/AndroidManifest.xml`。

| 页面 | 类 | 作用 |
|---|---|---|
| 首页/密码页 | `MainActivity` | 启动页；首页和密码管理共用一个 Activity 内的两组 View |
| 工具库 | `ToolsActivity` | Agent、笔记、密码模块的导航页 |
| Agent | `AgentActivity` | 模型选择、流式对话、工具调用、历史会话 |
| API Key 管理 | `ApiKeyActivity` | 分组、Key、模型配置 |
| 笔记 | `NoteActivity` | 文件夹、笔记编辑/阅读、附件、拖放整理 |

导航关系：

```text
MainActivity
├─ 密码管理：当前 Activity 内切换 homePage/passwordPage
├─ AgentActivity
│  └─ ApiKeyActivity
└─ ToolsActivity
   ├─ AgentActivity
   ├─ NoteActivity
   └─ MainActivity(open_password=true)
```

`ToolsActivity` 返回密码模块时使用 `FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP`，参数由 `MainActivity.onNewIntent()` 接收，避免重复创建首页。

## 4. 总体架构

项目目前是轻量的分层结构：

```text
Activity / Adapter
        │
        ├─ PasswordService
        │      └─ PasswordMapper
        │             └─ BaseMapper + DatabaseUtil
        │
        ├─ AgentRepository
        ├─ NoteRepository
        ├─ WeatherService
        └─ AgentClient
               │
               └─ OpenAI 兼容 HTTP API

所有本地持久化最终进入 ToolboxDatabaseHelper
```

需要注意：

- Activity 同时承担页面状态、业务编排、线程切换和错误展示，逻辑较集中。
- 密码模块有 Service 层；Agent 和笔记主要由 Activity 直接调用 Repository。
- Repository API 都是同步阻塞接口，调用方必须放到后台线程。
- 各 Activity 自建执行器，没有统一的数据调度层。

## 5. 数据库

数据库入口：`app/src/main/java/com/example/toolbox/repository/ToolboxDatabaseHelper.java`

数据库名：`toolbox.db`  
当前版本：`10`

### 5.1 表结构

| 表 | 用途 | 关键字段 |
|---|---|---|
| `password` | 密码条目 | `password_id`、`password_name`、`password`、`category` |
| `agent_key_group` | API Key 分组 | `group_id`、`name` |
| `agent_api_key` | 服务凭据 | `key_id`、`group_id`、`base_url`、`encrypted_key` |
| `agent_model` | Key 下的模型 | `model_id`、`key_id`、`name` |
| `agent_conversation` | 对话 | `conversation_id`、`model_id`、`updated_at` |
| `agent_message` | 对话消息 | `conversation_id`、`role`、`content`、`status` |
| `agent_settings` | Agent 单行设置 | `settings_id`、`smart_search`、`memory_token_limit`、`tinyfish_encrypted_key`、`workspace_tree_uri` |
| `note_folder` | 笔记文件夹 | `folder_id`、`name`、`updated_at` |
| `note` | 笔记（标题、正文、备注） | `note_id`、`title`、`content`(备注，仅本地)、`body`(正文，上云)、`folder_id`、`sync_id`、`sync_state`、`encrypted` |
| `note_tombstone` | 删除墓碑（v13） | `sync_id`(PK)、`title`、`deleted_at`、`device_id`、`encrypted`、`payload_json`、`uploaded` |
| `note_attachment` | 附件元数据 | `note_id`、`local_id`、`uri`、`name`、`mime_type`、`size`、`remote_key`、`md5`、`upload_state`、`local_path`、`encrypted`、`plain_sha256` |
| `note_cloud_settings` | 附件云同步配置（单行） | `enabled`、`bucket`、`region`、`endpoint`、`prefix`、`delete_local`、`encrypted_ak`、`encrypted_sk`、`encrypted_mk`、`keyring_text` |
| `note_cloud_trash` | 待过期的云端对象 | `bucket`、`object_key`、`requested_at`、`attempts` |
| `smoking_record` | 抽烟记录 | `smoked_at` |

### 5.2 重点方法

#### `ToolboxDatabaseHelper.onCreate()`

位置：`ToolboxDatabaseHelper.java:26`

- 创建密码表。
- 创建 Agent 全套表和索引。
- 创建笔记、文件夹、附件表和索引。

#### `ToolboxDatabaseHelper.onUpgrade()`

位置：`ToolboxDatabaseHelper.java:34`

增量迁移：

- v2：密码表增加 `category`
- v3：增加 Agent 表
- v4：增加笔记表
- v5：旧笔记表增加文件夹能力
- v6：增加 `agent_settings` 单行表
- v7：`agent_message` 增加 token 统计字段
- v8：`agent_settings` 增加 `workspace_tree_uri`
- v9：增加 `smoking_record`
- v10：附件支持云端存储，见 8.3
- v11：正文上云（`sync_id`/`body`/`sync_state`）、附件加密标记、待过期表，`delete_local` 默认改为 1，并逐行回填 `sync_id`
- v12：笔记可逐篇选择云端是否加密（`note.encrypted`，-1 未决定 / 0 明文 / 1 加密），并把历史上已上传的笔记与附件标记为加密
- v13：删除传播——`note_tombstone` 墓碑表（含完整 payload_json 供重试与回收站）、配置表加 `device_id`

注意 v10、v11 的分支条件：`ALTER` 只在 `oldVersion >= 4` 时执行，因为更早的版本会走 `createNoteTables()` 直接建成含新列的最新结构，重复 `ALTER` 会因字段已存在而失败；`note_cloud_settings` 的列只有 `oldVersion >= 10` 才需要补，因为这张表 v10 才出现。`CREATE TABLE IF NOT EXISTS` 与 `backfillSyncIds()` 对任何旧版本执行都安全。

新增字段或表时必须同时：

1. 增加 `DB_VERSION`
2. 更新最新建表逻辑
3. 在 `onUpgrade()` 增加旧版本到新版本的迁移
4. 测试跨版本升级，不能只测试全新安装

### 5.3 反射 Mapper

相关文件：

- `interfases/Table.java`
- `interfases/Column.java`
- `interfases/TableFiledId.java`
- `util/DatabaseUtil.java`
- `mapper/BaseMapper.java`

这是项目自带的简易 ORM，目前实际主要服务于 `Password`。

#### `DatabaseUtil.getEntityMeta()`

位置：`DatabaseUtil.java:40`

- 读取 `@Table`、`@Column`、`@TableFiledId`
- 校验表名和列名
- 查找唯一主键和无参构造器
- 允许通过反射读写私有字段

#### `DatabaseUtil.createTableSql()`

位置：`DatabaseUtil.java:97`

根据实体注解生成 `CREATE TABLE IF NOT EXISTS`。注解中的 `defaultValue` 是原样 SQL 片段，字符串默认值必须自己带单引号。

#### `EntityMeta.toContentValues()` / `EntityMeta.fromCursor()`

位置：`DatabaseUtil.java:204`、`DatabaseUtil.java:221`

负责实体、`ContentValues` 和 `Cursor` 之间的转换。新增映射类型时，需要同时修改写入和读取逻辑。

#### `BaseMapper`

位置：`BaseMapper.java`

提供：

- `insert()`
- `updateById()`
- `deleteById()`
- `selectById()`
- `selectList()`
- `selectCount()`

它不管理多操作事务，跨表或多步骤写入仍需 Repository 显式开启事务。

## 6. 密码管理

核心文件：

- UI：`MainActivity.java`
- 模型：`Password/model/Password.java`
- 服务：`Password/service/PasswordService.java`
- Mapper：`Password/Repository/PasswordMapper.java`
- 加密：`util/PasswordEncoding.java`

### 6.1 数据边界

密码字段有明确约定：

```text
UI / PasswordService 外部：明文
PasswordService -> Mapper -> SQLite：密文
```

不要让 UI 或 Agent 直接调用 `PasswordMapper`，否则容易把明文直接写入数据库，或者把密文当明文展示。

### 6.2 重点方法

#### `PasswordService.getPasswords()`

位置：`PasswordService.java:28`

读取全部记录，并在返回前逐条解密。当前列表会在内存中持有全部密码明文。

#### `PasswordService.insertPassword()`

位置：`PasswordService.java:39`

1. 校验名称、密码、分类
2. 复制实体
3. 只加密副本
4. 写入数据库
5. 将新 ID 回填到调用方原对象

复制实体的目的，是避免界面持有的对象被原地替换成密文。

#### `PasswordService.updatePassword()`

位置：`PasswordService.java:63`

按当前明文重新加密整条记录并更新。如果更新行数为 0，会抛出异常。

#### `PasswordEncoding.encrypt()` / `decrypt()`

位置：`PasswordEncoding.java:51`、`PasswordEncoding.java:73`

- 使用 Android Keystore 中别名 `toolbox_password_key` 的 AES-256 密钥
- 算法：`AES/GCM/NoPadding`
- 每次加密生成新 IV
- 存储格式：`Base64(IV).Base64(密文和认证标签)`

密钥不可导出。清除应用数据、Keystore 密钥失效或迁移到其他设备后，原密文无法恢复。

### 6.3 页面调用链

```text
MainActivity.openPasswordModule()
  -> PasswordService 初始化
  -> loadPasswords()
  -> PasswordService.getPasswords()
  -> PasswordMapper.selectList()
  -> PasswordEncoding.decrypt()
  -> filterPasswords()
  -> PasswordAdapter
```

保存链路：

```text
showPasswordForm()
  -> savePassword()
  -> insertPassword() / updatePassword()
  -> 加密副本
  -> Mapper 写库
  -> loadPasswords()
```

## 7. Agent 模块

核心文件：

- UI：`agent/ui/AgentActivity.java`
- Key 管理：`agent/ui/ApiKeyActivity.java`
- 网络：`agent/network/AgentClient.java`
- 数据：`agent/repository/AgentRepository.java`
- Key 加密：`agent/security/ApiKeyCipher.java`

### 7.1 配置模型

配置关系是：

```text
KeyGroup 1 --- N ApiKeyConfig 1 --- N ModelOption
```

一个 Key 保存：

- 所属分组
- Key 名称
- OpenAI 兼容服务 Base URL
- 加密后的 API Key
- 脱敏提示
- 一个或多个模型名

### 7.2 API Key 重点方法

#### `AgentRepository.addKey()` / `updateKey()`

位置：`AgentRepository.java:44`、`AgentRepository.java:70`

事务内完成：

- Base URL 标准化
- API Key 加密
- 脱敏提示生成
- Key 配置写入
- 模型集合整体替换

编辑时 API Key 留空表示保留原密文。

#### `AgentRepository.getModelOptions()`

位置：`AgentRepository.java:135`

联表展开分组、Key、模型，并解密 API Key，返回可直接发起请求的 `ModelOption`。该对象包含明文 Key，只应短期驻留内存，不应打印日志或持久化。

#### `ApiKeyCipher`

位置：`agent/security/ApiKeyCipher.java`

与密码加密方案相同，但使用独立 Keystore 别名 `toolbox_agent_api_key`。

#### `AgentClient.fetchModels()`

位置：`AgentClient.java:135`

调用：

```text
GET {baseUrl}/models
Authorization: Bearer {apiKey}
```

用于测试连接并获取模型列表。

### 7.3 对话主链路

#### `AgentActivity.sendMessage()`

位置：`AgentActivity.java:179`

这是 Agent UI 的主要编排方法：

1. 检查模型和输入
2. 在 UI 中加入用户消息和助手占位消息
3. 首条消息时创建会话
4. 持久化用户消息
5. 读取最近 20 条完整消息
6. 调用 `AgentClient.run()`
7. 接收 SSE 文本增量并刷新最后一条消息
8. 持久化最终助手回答

`generationId` 用于让旧请求的迟到 UI 回调失效。

#### `AgentClient.run()`

位置：`AgentClient.java:68`

执行最多 6 轮的 Agent 循环：

```text
请求模型
├─ 没有 tool_calls -> 返回最终文本
└─ 有 tool_calls
   ├─ 执行本机白名单工具
   ├─ 把 tool 结果加入 messages
   └─ 再次请求模型
```

系统提示要求模型在检索密码条目时必须调用 `search_passwords`。

#### `AgentClient.request()`

位置：`AgentClient.java:175`

调用 OpenAI 兼容接口：

```text
POST {baseUrl}/chat/completions
stream=true
```

处理 SSE `data:` 事件，并按 `tool_calls[].index` 聚合同一个工具调用被拆散的：

- `id`
- 函数名
- JSON 参数字符串

参数必须等流结束后再整体解析。

#### `AgentClient.executeTool()`

位置：`AgentClient.java:273`

当前工具白名单：

- `get_current_time`
- `get_device_info`
- `list_toolbox_modules`
- `search_passwords`
- `query_notes`

#### `AgentRepository.queryNotes()`

位置：`AgentRepository.java`

`query_notes` 允许模型自己生成完整的只读 SQL，但不会直接查询真实业务数据库。执行流程：

1. 校验只能是一条 `SELECT`
2. 禁止注释、多语句、联表、集合查询、系统表和高风险函数
3. 把真实数据库中的笔记复制到只含 `note` 表的内存 SQLite 数据库
4. 在内存数据库执行模型 SQL
5. 使用外层 `LIMIT` 强制限制最终返回行数

沙箱表结构：

```sql
note(
    note_id,
    title,
    content,
    folder_id,
    folder_name,
    created_at,
    updated_at
)
```

默认最多返回 3 行。`AgentClient.allowsExpandedNoteResults()` 会检查最近一条用户消息：只有用户明确要求更多、全部、所有，或指定大于 3 的条数/篇数时，代码才允许模型使用更大的 `limit`。普通查询即使模型传入更大数值也会被强制压回 3，最大限制为 20 行。

该设计允许模型使用 `WHERE`、`LIKE`、排序和聚合帮助查找笔记，同时物理隔离密码、API Key 和其他业务表。

新增工具时至少需要同步修改：

1. `toolDefinitions()`：给模型声明工具
2. `executeTool()`：本地实际执行
3. `AgentActivity.readableTool()`：界面状态文案
4. 权限、隐私和数据最小化策略

### 7.4 密码隐私链路

Agent 搜索密码时：

```text
模型调用 search_passwords
  -> AgentRepository.searchPasswordReferences()
  -> 只查询 id/name/category/url/notes
  -> 元数据返回模型
  -> UI 弹出本地匹配项
  -> 用户手动选择
  -> PasswordService.selectPassword()
  -> 本地解密并显示
```

`searchPasswordReferences()` 明确不查询 `password` 列。明文不会进入 `AgentClient`、聊天记录或工具结果。

### 7.5 会话存储

重点方法：

- `createConversation()`：创建绑定模型的会话
- `addMessage()`：写入消息并刷新会话更新时间
- `getRecentMessages()`：倒序截断最新消息，再恢复为正序
- `getConversations()`：按最近更新时间读取历史
- `openConversation()`：恢复 UI 和历史消息

数据库只持久化完整的用户/助手消息。当前工具调用和工具结果主要存在于单次 `AgentClient.run()` 的内存消息数组中。

## 8. 笔记模块

核心文件：

- UI：`note/NoteActivity.java`
- 数据：`note/NoteRepository.java`
- Markdown：`note/MarkdownRenderer.java`

### 8.1 页面状态

`NoteActivity` 在同一个 Activity 内维护：

```text
列表页
└─ 详情页
   ├─ 编辑态
   └─ 阅读态
```

根级的 `folderId == null` 表示“未归档”，不是“查询所有文件夹”。

### 8.2 重点方法

#### `NoteActivity.loadNotes()`

位置：`NoteActivity.java:175`

- 搜索为空：读取当前文件夹；根级额外加载文件夹列表
- 搜索非空：跨所有文件夹搜索
- 输入延迟 180ms
- 使用 `searchVersion` 丢弃过时查询结果

#### `NoteRepository.list()` / `searchAll()`

位置：`NoteRepository.java:30`、`NoteRepository.java:50`

- `list(null, query)` 只查未归档笔记
- `searchAll(query)` 才是全局搜索

#### `NoteRepository.save()`

位置：`NoteRepository.java:135`

在同一事务内：

1. 新增或更新笔记正文
2. 删除原附件记录
3. 按当前内存附件集合重建附件元数据

附件采用整体替换策略，逻辑简单，但附件很多时写放大会明显。

#### `NoteRepository.deleteFolder()`

位置：`NoteRepository.java:93`

删除文件夹前，先把其中笔记的 `folder_id` 设为 SQL NULL，再删除文件夹。两步位于同一事务，笔记不会被级联删除。

#### `NoteActivity.onFilePicked()`

位置：`NoteActivity.java:351`

- 使用系统 `OpenDocument`，但换成自定义契约 `OpenDocumentWithWrite`：默认契约只保证读权限，而删除手机上的原文件需要写权限
- 尝试持久化源 URI 的读写权限；写权限拿不到时退化为只读，上传仍可完成，删除原文件会失败并如实提示
- 在主线程读取文件名、大小、MIME，随后整段处理交给后台执行器
- 走云端还是本地在**后台线程**判断：云同步配置可能尚未加载完，过早判断会把附件错误地按本地方式保存

未启用云同步时退回本地复制路径（复制进 `filesDir/note_attachments`，此后不依赖源文件）；启用后的落盘位置与状态见 8.3。

#### `MarkdownRenderer.render()`

位置：`MarkdownRenderer.java:30`

原生 `SpannableStringBuilder` 渲染，不使用 WebView。支持：

- 一级到三级标题
- 无序列表
- 待办项
- 引用
- 粗体
- `http/https` 链接

不支持嵌套、转义、代码块、图片、表格和相对链接。

### 8.3 云端同步与加密

相关文件：

- 签名与传输：`cloud/S3Signer.java`、`cloud/CloudClient.java`、`cloud/CloudException.java`
- 加密：`cloud/NoteCipher.java`、`cloud/Keyring.java`（都是纯 JDK，可在 `NoteCipherTest` 里验证）
- 对象键规则：`cloud/ObjectKeys.java`
- 正文同步：`cloud/NoteSyncClient.java`
- 到期清理：`cloud/QiniuManagementClient.java`（七牛原生管理接口，不是 S3 兼容接口）
- 配置与凭据持久化：`cloud/CloudRepository.java`（单行表 `note_cloud_settings`）
- 设置页：`note/ui/NoteCloudSettingsActivity.java`
- 单元测试：`app/src/test/java/com/example/toolbox/cloud/NoteCipherTest.java`

#### 加密范围与逐篇选择

**加密的是内容，不加密文件名。** 对象键直接用原始文件名，以便在七牛控制台一眼认出是哪个文件；文件名泄露的只是"存在这么个文件"，内容仍是密文。

**是否加密按笔记逐篇决定**（`note.encrypted`：`-1` 未决定 / `0` 明文 / `1` 加密）。正文与它的附件共用同一个选择：附件跟着笔记走，不单独设。首次上传前问一次，之后在笔记详情的工具栏菜单里切换，改动只影响下一次上传。

- 未决定时 `NoteRepository.Note.encryptsUpload()` 返回 true——默认走安全的一侧，不会因为漏问而把内容明文传出去。
- 明文上传不需要口令，因此没设口令也能用；设为加密而未解锁时，上传会保持"待上传"并提示，不会退化成明文。

**是否加密由内容自描述，不依赖本地标记。** 加密内容以 `TBXC` 魔数开头，明文就是普通 JSON/文件。`CloudClient.getObject()` 先窥探开头 4 字节，是魔数就解密、否则原样复制；内容加密而调用方没给密钥时直接报错，而不是把密文当文件交出去。这样做的好处是恢复场景下判断永远准确——全新安装根本没有本地标记。恢复出的笔记，其 `encrypted` 就取自这次嗅探结果。

**备注不参与上传**：`note.content`（备注）只存在本机 SQLite，`NoteSyncClient.Payload` 里没有这个字段，因此在实现层面就不可能被传出去。

#### 密钥层次

```text
口令 ──PBKDF2-HMAC-SHA256(salt, 210000 次)──▶ KEK（只用来包裹主密钥）
随机主密钥 MK ──AES-GCM(KEK)──▶ keyring
MK ──HMAC-SHA256(MK, "file:<对象键>") / "note:<syncId>"──▶ 单个对象的密钥
```

- keyring 明文存云端 `notes/_meta/keyring.txt`：它只含被口令包裹的 MK，本身不含可用密钥。
- **主密钥与对象密钥分离**：改口令只需用新 KEK 重新包裹 MK，云端已上传的内容一个都不用重传。
- 本机把 MK 用 Keystore 缓存（`note_cloud_settings.encrypted_mk`），日常免输入口令；缓存不可用时视为未解锁，此时**不会退化成明文上传**，而是提示先去设置口令。
- 附件的内容密钥由对象键派生、正文的由 `syncId` 派生：键本身唯一且已保存，所以不需要额外保存任何密钥材料。
- 忘记口令 = 云端内容永久不可读（本地明文还在）。keyring 解不开即口令错误，GCM 认证标签天然充当校验值。

#### 分块格式，以及为什么先落临时文件

```text
magic "TBXC"(4) | 版本(1) | 块大小(4) | 保留(3) | nonce 前缀(8) | 分块密文…
每块 = AES-GCM(对象密钥, nonce = 前缀||块计数, 明文块, AAD = 块计数) → 密文 + 16B tag
```

- 分块而非整体加密：GCM 不能在流式中途产出 tag，整体加密要把文件全读进内存，几十 MB 的 PDF 会 OOM。块计数同时进 nonce 与 AAD，**重排、截断、拼接都会解密失败**。
- 上传是"先把密文写到临时文件、再走普通上传"：签名要求请求头带请求体的 SHA-256，而密文因随机 nonce 无法提前算出；用 `UNSIGNED-PAYLOAD` 依赖服务端支持（未验证），读进内存又会 OOM。代价是一次临时磁盘写入，上传结束立即删除。
- **校验分两层**：`UploadResult.isVerified()` 比对服务端 ETag 与**密文** MD5（证明密文完整到达）；`plain_sha256` 在下载解密后再比对一次（证明解出来的就是原文件）。少任何一层都可能漏掉静默损坏。

#### 对象键

- 附件：`notes/<安全化的原始文件名>`；重名时 `availableKey()` 依次探测 `名字_2.ext`、`名字_3.ext`……**绝不允许覆盖云端已有对象**。
- 正文：`notes/meta/<syncId>.json`（稳定标识，不会因为改标题而变成孤儿对象）。
- 加密凭据：`notes/_meta/keyring.txt`。

#### 删除与到期

删除笔记时**不立即删云端对象**，而是把对象键收进 `note_cloud_trash`，再调七牛原生接口设为 7 天后过期：

```text
POST /deleteAfterDays/<urlsafe-base64("空间名:对象键")>/7      Host: rs.qiniuapi.com
Authorization: Qiniu <AK>:<HMAC-SHA1("POST " + path + "\nHost: rs.qiniuapi.com\n\n")>
```

- 收集对象键必须与删除处于**同一事务且顺序在前**：附件行一删，`remote_key` 就再也找不回来，云端对象会永久残留（这正是第一版漏掉的链路）。
- 服务端返回 612（资源不存在）视为完成；其余失败累加 `attempts` 留在队列，应用下次启动或再次删除时重试。
- `deleteAfterDays` 的 N 天是相对上传时间还是相对设置时间，文档没有明确；设置后会用 `stat` 把服务端返回的 `expiration` 写进日志（TAG `NoteActivity`），真机首次运行即可确认。两种语义都不会有害。

#### 本地文件处理

应用目录里的私有副本上传成功后一律删除（不设开关）。手机上的原始文件按 `delete_local` 三档处理：

| 值 | 行为 |
|---|---|
| 0 | 保留原文件 |
| 1 | 总是删除（**默认**，只留云端） |
| 2 | 每个文件上传完成后询问一次 |

- 「每次询问」由 `deleteAskShowing` 与弹窗的 dismiss 回调串行驱动，多文件同时完成也不会叠弹窗。
- 删除原文件是尽力而为：文件管理器、下载目录的文档 URI 一般可删，网盘或聊天软件的虚拟文档通常不支持；失败时提示手动清理，绝不谎报成功。
- 任何一档都以"服务端校验通过"为前提。

#### 正文同步与恢复

- `saveNote()` 每次保存都会重传一遍正文。不用"仅标题/正文变化才传"是为了让**附件清单**跟着更新：只在变化时上传，会让"先传附件、后保存笔记"这条路径上的清单永远停留在旧版本，结果是换设备恢复时正文回来了、附件却认不出属于哪篇笔记。正文密文只有几 KB，这点流量不值得那个风险。
- 云端 JSON 为 `{v, syncId, title, body, folderName, createdAt, updatedAt, attachments[]}`；`attachments` 是附件清单（名字、对象键、大小、类型、md5、明文摘要、是否加密），**没有它就无法把附件恢复回正确的笔记**。注意这是 v11 之后才加入的字段，更早上传的对象里没有清单，重新保存一次该笔记即可补上。
- 文件夹按**名字**同步，`folder_id` 跨设备没有意义。
- **后写赢**：不做云端比对，本地 `updatedAt` 更大的一方直接覆盖。多设备同时改同一篇会丢一次远程修改——这是选定策略，不是缺陷。
- 恢复入口在设置页：列举 `notes/meta/` → 逐个下载解密 → 按 `syncId` 合并（本地没有则插入，云端更新则覆盖）。合并时**绝不覆盖备注**。

#### 附件文件的落盘位置与命名

私有副本、缓存等一律放在**应用私有目录**，从不写共享存储：`filesDir/note_pending/<uuid>/<原始文件名>`（上传前的副本）、`filesDir/note_attachments/<uuid>/<原始文件名>`（本地模式）、`cacheDir/note_cache/<localId>/<原始文件名>`（按需下载）。

**每个附件一个子目录、文件名用原始名**，原因有两个：同名文件不会互相覆盖；把文件通过 FileProvider 交给外部应用打开时，对方看到的是用户自己的文件名，而不是一串 uuid 前缀。代价是目录多了一层，因此两处按目录结构写的逻辑必须配套维护：

- `referencedAttachmentFiles()` 还原被引用文件时，直接用 `new File(filesDir, uri.getPath())`——本应用 FileProvider 的 URI 路径就是 filesDir 下的相对路径。**不要按"取最后一段文件名去各目录里找"的方式还原**，子目录结构下会还原错、进而误删仍在使用的附件。
- `deleteUnreferenced()` 与 `deleteStale()` 都要递归进子目录，并在清空后回收空目录。

#### 双端同步协议（payload v2）

PC 端（`D:\AndroidProjects\pc-notes`，Python）与手机端共享同一云端库并字节级互通。payload v2 在
`{v, syncId, title, body, folderName, createdAt, updatedAt, attachments[]}` 基础上增加
`deleted / deletedAt / deviceId`。

- **权威索引在云端**：`notes/meta/` 就是唯一索引，两端的本地 SQLite 只是可重建的缓存。
  同步循环（`NoteActivity.syncNow()`）：① 拉取（列出 meta/ → 逐个下载 → 墓碑落地或正文合并）
  → ② 推送（`sync_state ≠ 已同步` 的正文 + 未上传的墓碑；已墓碑的笔记绝不再上传）
  → ③ 处理待过期队列。触发点：进入笔记页（静默）、列表菜单「立即同步」。
- **删除传播**：删除时把 meta 对象覆盖成「完整 payload + deleted:true + deletedAt + deviceId」
  （内容完整保留，回收站才能找回），本地 `note_tombstone` 落一行（含 payload_json，上传失败可重试）。
  过期分级：附件等内容对象 7 天，meta 墓碑 30 天（`processTrashQueue` 按 `/meta/` 前缀区分）。
  另一端同步到墓碑后删除本地并记录自己的墓碑；**本地墓碑永久保留**，保证已删笔记不会被任何一端复活。
  「删了又改」例外：云端 payload 的 updatedAt 比本地墓碑的删除时间新时，复活并清墓碑。
- **冲突备份**：合并覆盖前，若本地有未同步修改（`sync_state ≠ SYNCED`），把本地版另存一篇
  「标题（冲突备份 时间）」；本地没有未同步修改时覆盖是无损的，不备份。
- **附件回收**：`save()` 在删除旧附件行之前，把"上传过但不在本次清单里"的 `remote_key`
  写进待过期队列——移除附件不再泄漏云端对象。
- **文件夹自动创建**：合并/恢复时按 folderName 找不到文件夹就新建（两端同规则），结构自然双向。
- PC 端对应实现：`pc-notes/app/sync.py`（sync_now / pull_from_cloud / upload_tombstone）、
  `app/db.py`（同构墓碑表与合并规则），回收站页面在其设置页；协议兼容性由 PC 端 87 项 pytest 与
  Java↔Python 加解密交叉验证（`crosscheck/`）保障。

#### 打开附件的回退顺序

待上传（点按重试）→ `local_path` 私有副本 → `uri` 原始文件 → 云端按需下载到 `cacheDir/note_cache`（解密 + 明文摘要复核）。缓存保留 6 小时后再由 `clearStaleCache()` 清理，避免打断仍在读取的外部阅读器。

历史附件（本次改造前上传的）`encrypted = 0`，下载时不做解密。`upload_state`：`0` 待上传、`1` 已上传、`2` 上传失败；改造前的仅本地附件不会被自动上传。

#### 孤儿文件清理

`NoteActivity.purgeOrphanFiles()` 在页面创建时扫描 `note_pending` 与 `note_attachments`，删除**没有任何附件记录引用**的文件。会产生残留的三种情况：上传失败或未解锁口令后用户放弃了该笔记；添加附件但没保存就退出（附件行从未落库）；本地模式下随笔记删除遗留的副本——最后这条在改造前一直缺失，文件只增不减。

两个保护：判定"被引用"时对 FileProvider 的 content URI 同时按两种目录尝试还原（宁可少删，多留一轮不会出错，误删会丢数据）；10 分钟内刚写入的文件一律跳过，因为正在上传的副本此刻可能还没落库。

#### 服务端准备

1. 建 IAM 用户，策略资源用简化 QRN：`qrn:kodo:::biglv-test1/notes/*`（文档说明 zone 与 uid 可省略）。
2. 动作需覆盖该前缀下对象的上传、下载与列举；具体动作名以控制台策略编辑器提供的列表为准。到期设置走的是管理接口（`rs.qiniuapi.com`），不经 IAM 对象动作，但用的是同一对 AK/SK。
3. 把 AK/SK 填进设置页；密钥经 `ApiKeyCipher` 加密后落库，明文不落库。

#### 本次未做

正文冲突自动合并（用后写赢代替）；附件级备注；本地数据库加密；历史明文附件的批量重新加密（靠 `encrypted` 标记共存）。


## 9. 首页天气与公共交互

### 9.1 真实设备定位

相关文件：

- `util/DeviceLocationProvider.java`
- `MainActivity.java`
- `AndroidManifest.xml`

首页会申请粗略/精确定位权限，并从 Android `LocationManager` 的 GPS 与网络 Provider 获取当前位置：

1. 优先等待精度 150 米以内的当前定位
2. 最长等待 10 秒
3. 超时后使用本轮收集到的最佳位置或系统最近位置
4. 无权限或定位服务不可用时明确提示，不再回退到北京或公网 IP 地址

点击天气卡片可以重新发起权限或定位流程。

### 9.2 `WeatherService.load(Location)`

位置：`WeatherService.java`

读取策略：

1. 接收手机定位得到的真实经纬度
2. 使用 Android `Geocoder` 反向解析城市
3. 调用 Open-Meteo 获取该坐标的天气
4. 30 分钟内且与缓存坐标相距不超过 3 公里时复用天气缓存
5. 联网失败时只回退到同一位置附近的旧缓存
6. 城市解析失败时显示“当前位置”，不会伪造默认城市

该服务是同步网络 API，必须在后台线程调用。

### 9.3 `SwipeBackHelper.Controller.handle()`

位置：`SwipeBackHelper.java:43`

- 仅处理左侧 30dp 内开始的手势
- 112dp 作为返回阈值
- Android 12 以上增加 `RenderEffect` 模糊
- Activity 从 `dispatchTouchEvent()` 转交事件

## 10. 接手时优先关注的风险

### P1：Agent 后台请求使用可变 `conversationId`

`AgentActivity.sendMessage()` 的后台任务在多个位置直接读取 Activity 字段 `conversationId`。用户在请求结束前新建或切换会话时，`generationId` 只阻止旧任务更新 UI，不保证旧任务不会把最终回答写入错误会话，甚至可能尝试写入会话 ID 0。

建议：创建会话后立即捕获不可变的 `requestConversationId`，本次请求的所有读写都使用该局部变量；切换会话只改变后续请求。

### P1：缺少数据库外键约束

表结构声明了多个逻辑关联，但没有 `FOREIGN KEY`，也没有显式启用 SQLite foreign keys。删除操作依赖手工级联，异常或新代码遗漏时可能留下孤儿记录。

建议：先补数据清理测试，再设计迁移；不要直接改表而不考虑现有数据。

### P1：自动化测试覆盖很低

现有有效测试主要验证：

- `DatabaseUtil` 能读取 `Password` 元数据
- 密码表建表 SQL

尚未覆盖：

- 数据库 v1-v5 迁移
- 密码/API Key 加解密与损坏密文
- Repository 事务和删除行为
- Agent SSE/tool_calls 分片解析
- 会话切换与取消竞态
- 笔记附件权限和保存
- Activity 关键交互

### P2：Activity 职责偏重

`MainActivity`、`AgentActivity`、`NoteActivity` 同时承担 UI、线程、业务编排和状态管理。配置变更、页面重建和并发边界较难测试。

建议逐步引入 ViewModel 或独立协调类，不建议一次性重写整个项目。

### P2：会话删除和 Key 删除依赖手工级联

- 删除 Key 时只删除模型和 Key
- 历史会话仍可能保留已删除的 `model_id`
- 删除分组、会话、笔记均由 Java 手工维护关联

当前 `LEFT JOIN` 能让历史会话在模型不存在时继续显示，但后续恢复模型时可能退回当前选择，而不是原模型。

### P2：明文生命周期较长

- 密码列表加载后，所有密码明文保存在 `allPasswords`
- `ModelOption` 长期持有明文 API Key
- 用户复制后，秘密进入系统剪贴板

建议后续按需解密、减少字段驻留时间，并考虑 Android 的敏感剪贴板标记与自动清理策略。

### P2：加密格式没有版本字段

当前密文只有 `IV.密文`，没有算法版本或密钥版本。未来更换算法、别名或密钥策略时，不容易兼容迁移。

建议新格式增加版本前缀，例如 `v1.IV.ciphertext`，解密时按版本分派。

### P2：网络兼容范围有限

Agent 假定服务支持：

- `/models`
- `/chat/completions`
- Bearer API Key
- SSE 流式返回
- OpenAI 风格 `tool_calls`

部分“OpenAI 兼容”服务只兼容基础聊天，不兼容工具调用或增量工具参数。

### P3：模型生成 SQL 仍需关注复杂度

`query_notes` 已通过内存数据库隔离其他业务表，并禁止常见危险语法，但模型仍可能生成低效的笔记查询。当前通过 SQL 长度、结果列数、单元格长度和最多 20 行结果限制控制影响；笔记数量明显增长后，应考虑增加查询超时或改为结构化查询计划。

### P3：依赖和命名存在历史痕迹

- `play-services-fitness` 当前未发现使用，可评估移除
- `interfases` 应为 `interfaces`
- `TableFiledId` 应为 `TableFieldId`
- `ColumType` 应为 `ColumnType`
- `Password` 包名首字母大写，不符合常见 Java 包命名

这些类型已有调用方，重命名应使用 IDE 重构并分批进行，不要与功能改动混在同一次提交。

## 11. 推荐接手顺序

1. 先运行 `assembleDebug` 和 `testDebugUnitTest`，安装 Debug APK，逐页走通功能。
2. 阅读 `ToolboxDatabaseHelper`，掌握所有持久化结构和版本迁移。
3. 阅读 `PasswordService` 与两个 Keystore 加密类，确认秘密数据边界。
4. 阅读 `AgentActivity.sendMessage()` 和 `AgentClient.run()/request()`，理解并发与 Tool Calling。
5. 阅读 `NoteActivity.loadNotes()/saveNote()` 和 `NoteRepository.save()`，理解页面状态与附件策略。
6. 优先补 Agent 会话竞态测试和数据库迁移测试，再开始较大改造。

## 12. 新增功能时的落点

### 新增普通工具页面

1. 新建 Activity 和布局
2. 在 Manifest 注册
3. 在 `ToolsActivity` 增加入口
4. 如需首页最近使用入口，再修改 `MainActivity` 和 `activity_main.xml`
5. 更新 `list_toolbox_modules` 的返回值

### 新增 Agent 本机工具

1. 在 `AgentClient.toolDefinitions()` 声明 JSON Schema
2. 在 `AgentClient.executeTool()` 实现白名单分支
3. 在 Repository 增加最小权限数据接口
4. 在 `AgentActivity.readableTool()` 增加中文名称
5. 确认敏感字段不会进入工具结果、日志或聊天记录
6. 增加工具参数、异常和循环上限测试

### 新增数据库表或字段

1. 更新最新建表方法
2. 提升 `DB_VERSION`
3. 增加 `onUpgrade()` 迁移
4. 补全新安装和跨版本升级测试
5. 检查备份排除规则和加密数据迁移策略

## 13. 当前测试与产物

测试文件：

- `app/src/test/java/com/example/toolbox/DatabaseUtilTest.java`
- `app/src/test/java/com/example/toolbox/agent/repository/AgentRepositorySqlTest.java`
- `app/src/test/java/com/example/toolbox/ExampleUnitTest.java`
- `app/src/androidTest/java/com/example/toolbox/ExampleInstrumentedTest.java`

`AgentRepositorySqlTest` 覆盖允许的笔记 SELECT，以及其他表、联表、集合查询、写操作和多语句拒绝规则。`ExampleUnitTest` 和 `ExampleInstrumentedTest` 仍是模板性质，业务保障有限。

数据库已在以下备份规则中被排除：

- `app/src/main/res/xml/backup_rules.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`

这是合理的，因为数据库中的密码和 API Key 密文依赖本设备 Android Keystore，单独恢复数据库通常无法解密。

## 14. 一句话总结

这个项目的核心价值在于“本地数据 + 自有模型服务”的组合：SQLite 管理数据，Android Keystore 保护秘密，Agent 只获得必要元数据。接手后的第一优先级不是重写界面，而是固定会话并发边界、补数据库迁移与加密测试，再逐步拆分过重的 Activity。
