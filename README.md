# 个人数字分身 · 本地 Java MVP

JDK **25** + Spring Boot **3.5.16** + Lucene **10.3.1** 的单进程问答应用。无密钥可完整运行；内置内容全部为明确标记的虚构示例，不代表项目所有者。

## 启动与测试

要求 JDK 25、Maven 3.6.3+；Maven Enforcer 拒绝其他 JDK 主版本，编译目标为 release 25。

```powershell
java -version
mvn -version
mvn clean verify
java -jar target/digital-twin-0.1.0.jar
```

打开 http://127.0.0.1:8080 。默认只绑定回环地址；端口可通过 `PORT` 环境变量或 `--server.port=8081` 调整。没有 Node、数据库、Docker 或前端安装步骤。

可问：“你会什么”“介绍一下自己”“有哪些项目”“怎样记录笔记来源”。陌生问题会返回资料不足。页面展示回答方式、路由方式及可展开的来源。Enter 发送，Shift+Enter 换行。

## 最小架构

```text
静态聊天页 → POST /api/chat → 可选 JEV choice 路由（异常/低置信度回到本地规则）
                           ├─ 介绍/能力/项目：公开卡片结构化直答
                           └─ 具体问题：Lucene BM25 + HNSW → RRF → 引用摘录/可选生成 HTTP
本地离线导入 → private 存储（默认）/ 显式批准的 public 存储 → 重启重建公开索引
```

- `Entry` 定义卡片结构；`OfflineImport` 在启动 Spring 前完成独立离线导入。
- `Knowledge` 只读取批准的公开条目；SmartChineseAnalyzer + BM25、KnnFloatVectorField + KnnFloatVectorQuery，使用 `TopDocs.rrf` 融合。700 字符切块、100 字符重叠；来源保留原定位并追加字符范围。
- 演示 embedding 为 256 维归一化字符二元特征哈希，**不是语义模型**。远程向量构建失败时整个索引回到演示向量；远程查询失败/维度变化时仅使用词法检索，不混用向量空间。每次启动重建索引，切换模型后必须重启。
- 召回相关性阈值是启发式，不是正确率或概率。JEV 的 confidence 才用于远程路由阈值；本地规则的 confidence=-1，表示不提供概率。
- `ModelHttp` 使用 JDK HttpClient，连接/请求超时、无重定向；JEV、生成、embedding 分别配置。失败结果不向页面暴露供应商错误正文或密钥。生成缺少合法引用编号则回退摘录；合法编号仍不保证模型完全忠于资料，需人工核验。

## 离线资料导入与公开批准

支持 JSON 卡片数组、UTF-8 TXT/Markdown、文本型 PDF（PDFBox，逐页定位，无 OCR）。单文件上限 50MB；单条正文上限 100000 字符。原始文本/PDF 只导入私有存储，即使传批准参数也不能直接公开。

```powershell
# 默认 private；此操作不启动网页、不调用任何模型
java -jar target/digital-twin-0.1.0.jar --app.import-file="D:/my-notes/notes.md"

# 同样默认 private：即便 JSON 内已经写了 public，也需要额外命令批准
java -jar target/digital-twin-0.1.0.jar --app.import-file="D:/my-notes/cards.json"
```

JSON 字段格式参见 [examples/import-cards.json](examples/import-cards.json)。先在仓库外复制并填写自己的卡片；每条需要唯一 `id`、`type`、`title`、`content`、`source` 和 ISO 日期 `updatedAt`。可选 `level`、`boundaries`、`evidenceIds` 支持能力直答。类型支持 profile/skill/project/evidence/learning/style/document；字段采用 camelCase。

只有同时满足下面两项才写入公开存储：

1. 已逐条核对全部正文、元数据、证据、来源的披露权限，将该条设为 `"visibility":"public", "publicConfirmed":true`。
2. 执行本地显式批准命令：

```powershell
java -jar target/digital-twin-0.1.0.jar --app.import-file="D:/approved/public-cards.json" --app.approve-public=true
# 再重启聊天应用；需要真实资料演示时关闭虚构样例
java -jar target/digital-twin-0.1.0.jar --app.demo-data=false
```

原始 TXT/PDF 请先从 `data/private/import-*.json` 复制到仓库外，删除敏感信息、核对 source/title 等字段，再逐条批准；**不要公开原始简历或带联系方式的卡片**。无须上传私密聊天。`evidenceIds` 只会解析为已批准的 evidence 条目；未解析的编号不会出现在公开回答中。

`data/private/` 不被公开索引读取，也无 HTTP 访问接口；只有 `data/public/` 中同时标为 public 且已确认的条目参与切片、embedding、固定回答与生成上下文。整个 `data/` 已排除 Git，静态文件仅来自 classpath。公开入口无法查询私有资料。

数据目录默认 `./data`，可用 `DATA_DIR` 或 `--app.data-dir=D:/local-twin-data` 指定；不要放在静态资源目录或 Git 追踪目录中。记录可用本地编辑器查看，无在线管理接口。批准会把处理后的公开条目发给配置的远程 embedding；JEV 接收当前问题，生成模型接收当前问题与公开检索片段，不发送历史对话。

**更新与撤回**：导入是追加批次，不是权限撤回命令。更新资料前先停止应用、从 `data/public/` 的所有批次删除旧条目（保持 id 唯一），再导入修订版并重启；撤回公开许可时也必须移除所有对应公开副本。启动会清空重建 `data/index-public/`，没有回答缓存。单纯再次导入 private 不会撤回先前 public 副本。重复 id 跨批次会导致启动失败，需先去除重复。

## 接入真实 JEV 与模型

演示模式不进行外部网络调用。设置以下变量后重启即可启用各适配器；密钥只放环境变量，不写入文件或 Git。示例里的占位符需要替换；没有配置模型名称时生成/embedding 继续使用本地模式。

| 变量 | 默认值 / 含义 |
| --- | --- |
| `JEV_API_KEY` | 空；配置后启用官方 HTTP 路由 |
| `JEV_URL` | `https://api.typesafe.ai/v1/systemone`，完整端点 |
| `JEV_MODEL` | `jev-latest` |
| `JEV_THRESHOLD` | `0.65`；低于阈值回退本地规则 |
| `GEN_API_KEY` / `GEN_MODEL` | 空；均配置后启用生成 |
| `GEN_URL` | `https://api.openai.com/v1/chat/completions`，完整 OpenAI-compatible 端点 |
| `EMBED_API_KEY` / `EMBED_MODEL` | 空；均配置后启用 embedding |
| `EMBED_URL` | `https://api.openai.com/v1/embeddings`，完整 OpenAI-compatible 端点 |
| `HTTP_TIMEOUT_SECONDS` | `8`，限制在 1–60 秒；每个 HTTP 请求分别超时，无自动重试 |
| `DEMO_DATA` | `true`；设为 false 关闭虚构数据 |

```powershell
$env:JEV_API_KEY = '<your-key>'
$env:GEN_API_KEY = '<your-key>'
$env:GEN_MODEL = '<provider-generation-model>'
$env:GEN_URL = 'https://your-provider.example/v1/chat/completions'
$env:EMBED_API_KEY = '<your-key>'
$env:EMBED_MODEL = '<provider-embedding-model-with-at-most-1024-dimensions>'
$env:EMBED_URL = 'https://your-provider.example/v1/embeddings'
java -jar target/digital-twin-0.1.0.jar
```

embedding 响应必须为浮点数组，维度 1–1024；同一运行期模型配置和维度必须稳定。该 MVP 未实现维度投影或发送 dimensions 参数，请选择适合 Lucene 默认向量上限的模型。支持本机 HTTP 模型端点（仍需设置非空占位 key 启用），其他远程地址要求 HTTPS。

JEV 使用官方 `model/state/questions` 请求和 `answers.route.choice/confidence` 响应；它不生成自然语言。生成兼容 `messages` → `choices[0].message.content`；embedding 兼容 `input` → `data[0].embedding`。不要求安装第三方 Java SDK。

## 验证与边界

`mvn clean verify` 包含真实 Spring HTTP 服务、Lucene 混合检索、双重公开批准、私有目录/未确认条目排除、文本/PDF 导入页码、未知问题、结构化技能证据解析，以及本机 HTTP stub 验证的 JEV/生成/embedding 契约、超时、429、无效引用和向量维度变化降级。不依赖真实密钥。

此版本是本地单用户 MVP，不应直接暴露公网。没有登录、私有问答、多轮记忆、远程 URL 抓取、在线审批、OCR、增量索引或生产级模型输出验证；当前相关性阈值和特征哈希未经过真实语料质量评测。真实供应商端到端调用需自行配置密钥，HTTP stub 测试不等于真实模型调用认证。首次构建需要 Maven 下载依赖。

官方参考：[JEV HTTP API](https://docs.typesafe.ai/api.md)、[Spring Boot 3.5 Java 支持](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[Lucene TopDocs RRF](https://lucene.apache.org/core/10_3_1/core/org/apache/lucene/search/TopDocs.html)。
