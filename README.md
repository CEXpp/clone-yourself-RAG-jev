# 个人数字分身 · 检索增强生成系统 (Clone-Yourself RAG)

基于 **JDK 25** + **Spring Boot 3.5.16** + **Apache Lucene 10.3.1** 构建的单进程个人数字分身问答与检索系统。

面向技术面试与个人作品集展示场景设计：**无需任何外部 API 密钥即可完全离线运行**（内置本地 BGE-small-zh-v1.5 ONNX 神经嵌入、Lucene 混合检索与公开资料事实摘录），同时支持通过配置无缝接入官方 JEV 决策路由与 OpenAI 兼容的大模型生成/嵌入服务。

> [!IMPORTANT]
> **演示语料声明（示例）**：仓库开箱内置的 15 篇演示条目（经历、技能、项目、学习笔记）**全部为明确标注的「虚构示例（示例）」**，原型为向 Agent 方向发展的 Java 后端工程师，旨在展示系统的检索、引用追溯与拒答能力，不代表仓库作者的真实履历。若需用于个人真实分身展示，请按后文指引导入并批准自己的资料。

---

## 诚实设计原则 (Honesty by Design)

传统 RAG 演示系统常因盲目召回、虚假引用、臆造模式而产生不可信回复。本项目在工程架构各层均贯彻**「诚实性设计」**：

1. **双重批准公开语料门禁（Approval-gated Corpus）**  
   隐私与公开边界物理隔离。本地导入的原始资料默认存放于 `data/private/`，**不被公开索引读取，亦无任何 HTTP 接口访问**；只有逐条审核披露权限、在数据中显式设定 `"visibility":"public", "publicConfirmed":true`，并执行显式离线批准命令后，才会写入 `data/public/`。任何私有或未确认条目在切块、向量化与生成上下文阶段即被物理剔除。
2. **端到端引用编号有效性校验（Citation Validation）**  
   大模型生成的引用标号（如 `[1]`）必须通过全系统唯一的严格校验门禁：提取所有引用编号，若存在超出实际召回来源区间 `[1, sourcesCount]` 的编号或完全无引用，系统判定回答不可靠，**瞬时降级为可追溯的本地资料逐字摘录**。系统在所有界面中诚实表述：校验的是「引用编号在来源列表中合法存在」，**不夸大为「模型论断已被形式化逻辑证明」**。
3. **双通道相关性门禁与无依据拒答（Dual-channel Gate & Refusal Behaviour）**  
   拒绝「有问必答」式幻觉。针对具体问题的检索必须同时受检于**词法覆盖率门禁**（`lexical-coverage >= 0.50`）与**向量变换余弦门禁**（ONNX 模式 `>= 0.805`）。两路均不达标的候选块直接剔除；若无候选幸存，系统立即触发 `insufficient-evidence` 拒答路径，输出 *“公开资料中没有足够依据回答这个问题”*，杜绝无据引申。
4. **运行模式真实性（Mode Truthfulness）**  
   界面状态栏与 API 响应元数据常显真实生效后端（`onnx` / `http` / `hash` / `lexical-only`）。**绝不使用无深层语义的字符哈希冒充真实语义嵌入**；Hash 模式降级为仅供评测对比的显式选项。本地摘录模式明示前缀 *“根据公开资料摘录：”*，绝不伪装为大模型生成的流畅原创段落。

---

## 架构一览 (Architecture at a Glance)

```text
用户提问 (Question) ──► 极小多轮指代消解 (Minimal Multi-turn)
                              │ (仅唯一明确主题时改写；否则保持原问)
                              ▼
                        意图路由 (Route)
         ┌────────────────────┴────────────────────┐
         │ 结构化总览意图 (skills/projects/profile)    │ 具体事实问题 (retrieve)
         ▼                                         ▼
   公开卡片结构化直答                        Lucene 10 单引擎混合检索
   (技能/项目清单与证据互链)             ┌───────────────┴───────────────┐
                                      ▼                               ▼
                           SmartCN 词法召回 (BM25)       BGE-small-zh 本地 ONNX 向量召回 (HNSW)
                                      │                               │
                                      ▼                               ▼
                           词法覆盖率门禁 (>=0.50)         Lucene 变换余弦门禁 (>=0.805)
                                      └───────────────┬───────────────┘
                                                      ▼
                                            两路门禁候选求并集
                                                      ▼
                                            TopDocs.rrf (k=60) 融合重排
                                                      ▼
                                            同条目重叠分块近重复去重 (顺延补位)
                                                      ▼
                                            Top-4 候选分块
                                     (若为空 ──► insufficient-evidence 拒答)
                                                      │
                                                      ▼
                                            生成与摘录分支
                                     ┌────────────────┴────────────────┐
                                     │ 配置生成大模型                   │ 未配置生成模型
                                     ▼                                 ▼
                         OpenAI 兼容接口生成回答              整理公开分块逐字摘录
                                     │                        (local-extract)
                                     ▼                                 │
                         引用编号严格有效性校验                         │
                         (失效则回退为 local-extract)                   │
                                     └────────────────┬────────────────┘
                                                      ▼
                                  SSE 真实进度流 (Progress Stream) 或 JSON
                                  (route ──► trace ──► progress ──► answer ──► done)
```

- **单进程单引擎架构**：单进程内利用 Apache Lucene 10 同时管理倒排索引（BM25）与分层可导航小世界图索引（HNSW），无须外挂单独的分布式向量数据库，极简部署与运维。
- **本地 ONNX 推理**：内置 BAAI bge-small-zh-v1.5（512 维），使用 WordPiece 词表切词、**CLS pooling**（首 token 隐藏态）与 L2 归一化；在 Linux 容器与主机上通过 native glibc bindings 高效运行。
- **倒数排名融合 (RRF)**：使用 Lucene 原生 `TopDocs.rrf(topN=24, k=60, ...)` 结合词法精确匹配与语义模糊泛化，兼顾两路优势。
- **真实进度流**：流式端点采用真实进度推进事件，答案全文经校验合法后一次性送达前端，杜绝假打字机欺骗。

详细类结构、时序图与设计权衡分析请参阅 [ARCHITECTURE.md](ARCHITECTURE.md)。

---

## 快速开始 (Quickstart)

### 1. 本地直接运行

**环境要求**：
- OpenJDK **25**（Maven Enforcer 插件严格校验 `[25, 26)`，推荐 Eclipse Adoptium Temurin 25）
- Apache Maven **3.6.3+**

```bash
# 1. 验证编译与运行环境
java -version
mvn -version

# 2. 准备本地模型资产（若首次克隆未包含 models/）
./scripts/fetch-model.sh models

# 3. 编译打包应用
mvn clean package -DskipTests

# 4. 启动应用（注意：ONNX Runtime 在 JDK 25 上需开启本地原生访问标志）
java --enable-native-access=ALL-UNNAMED -jar target/digital-twin-0.1.0.jar
```

启动完成后，使用浏览器打开 [http://127.0.0.1:8080](http://127.0.0.1:8080)。
服务默认仅绑定回环地址 `127.0.0.1`，零密钥启动默认使用 `onnx` 向量模型与 `local-extract` 本地摘录模式。

### 2. Docker / Docker Compose 容器化运行

本项目提供**多阶段、glibc 运行时、非 root 用户、全离线就绪**的容器打包方案。构建阶段由 `scripts/fetch-model.sh` 预先将模型装入镜像，**运行期容器不执行任何外部网络下载**。

#### 方式 A：Docker Compose（推荐）

```bash
# 启动服务（默认端口 8080，挂载持久化卷 clone-yourself-rag-data）
docker compose up -d

# 查看容器健康状态（就绪探针探活 /api/status）
docker compose ps

# 查看实时运行日志
docker compose logs -f
```

#### 方式 B：Docker 原生构建

```bash
# 1. 构建镜像（多阶段构建，模型预备于构建期）
docker build -t clone-yourself-rag:latest .

# 2. 运行容器（非 root 用户 appuser，映射 8080 端口，挂载数据卷）
docker run -d \
  --name clone-yourself-rag \
  -p 8080:8080 \
  -v clone-yourself-rag-data:/app/data \
  clone-yourself-rag:latest

# 3. 检查状态端点
curl -s http://127.0.0.1:8080/api/status
```

> [!WARNING]
> **公网部署安全前置警示**：容器运行时环境变量设定了 `SERVER_ADDRESS=0.0.0.0` 以便 Docker 暴露端口。此 MVP 为面向单用户的求职分身演示系统，**未内置身份鉴权、防刷频次控制与公网防御机制**。如需部署至公网，请务必在前置配置 Nginx/Caddy 反向代理、HTTPS 证书与 Basic/OAuth 访问控制，切勿直接将裸端口暴露给不可信互联网。

### 3. 面试演示模式与提问清单（示例）

进入系统 Web 界面后，可通过快捷键 `/` 聚焦输入框，使用 `Enter` 发送提问，`Shift + Enter` 换行。

以下为基于内置 15 篇虚构示例语料的预设演示问答（全部为示例）：

| 问题类型 | 提问示例 | 预期行为与系统流转路径 |
|---|---|---|
| **技能总览** | *“你会什么？”*（示例） | 触发第一层精确路由 `skills`，直接输出 5 项已批准技能卡，自述与证据链互链展示。 |
| **项目总览** | *“有哪些项目？”*（示例） | 触发第一层精确路由 `projects`，展示数字分身、奶茶店后端、点评系统 3 个项目卡。 |
| **自我介绍** | *“介绍一下你自己”*（示例） | 触发第一层精确路由 `profile`，展示职业角色画像与核心方向自述。 |
| **具体技术问答** | *“为什么不用向量数据库而选 Lucene 单库？”*（示例） | 路由判为 `retrieve`，BM25 与 ONNX 混合检索，双通道门禁放行，命中架构决策证据卡，生成带引用的摘录。 |
| **具体技术问答** | *“RRF 融合的 k 取值是多少？依据是什么？”*（示例） | 检索命中长文档条目后段，精准召回 `k=60` 参数抉择依据，展示 token-aware 分块定位能力。 |
| **多轮指代消解** | 第一轮：*“奶茶项目用的什么技术栈？”*<br>第二轮：*“它的自测性能怎么样？”*（示例） | 第二轮自动识别前序唯一主题条目 `奶茶项目`，重写问题并发起检索，准确定位性能自测记录。 |
| **无依据拒答（负例）** | *“他的奶茶项目服务过多少付费用户？”*（示例） | 词法覆盖率仅 0.40 未达 0.50 门禁，向量相似度 0.8028 未达 0.805 门禁，无候选幸存，触发拒答。 |
| **跨域无关提问** | *“今天北京天气怎么样？”*（示例） | 向量分最高仅 0.6414，双通道坚决拦截，返回资料不足拒答提示。 |

---

## 离线资料导入与公开批准流程

若要替换演示数据，展示您自己的真实履历与资料：

```bash
# 1. 离线私有导入（默认进入 private 存储，不启动 Web、不调用外部网络、不对外公开）
java --enable-native-access=ALL-UNNAMED -jar target/digital-twin-0.1.0.jar \
  --app.import-file="/path/to/your-cards.json"

# 2. 核对卡片内容与隐私披露权限（将确认披露条目设为 "visibility":"public", "publicConfirmed":true）

# 3. 执行显式批准导入（写入 data/public/）
java --enable-native-access=ALL-UNNAMED -jar target/digital-twin-0.1.0.jar \
  --app.import-file="/path/to/approved-cards.json" \
  --app.approve-public=true

# 4. 生产运行并关闭虚构示例数据
java --enable-native-access=ALL-UNNAMED -jar target/digital-twin-0.1.0.jar \
  --app.demo-data=false
```

卡片 JSON 结构字段与格式要求请参考 [examples/import-cards.json](examples/import-cards.json)。

---

## 评测故事与真实数字 (The Eval Story & Calibration Numbers)

本项目拒绝脱离数据盲调超参数。所有检索门禁阈值均在固定语料与黄金开发集上进行严谨校准，并在未调参的冻结测试集上完成单次泛化验证。完整校准过程与证据详见 [eval/CALIBRATION.md](eval/CALIBRATION.md)。

### 1. 评测基准设定
- **运行基准**：固定 Fixture 语料库（`src/main/resources/demo/entries.json`，15 篇经审核公开文档，经 token-aware 分块后产生 16 个 chunk）。
- **开发集 (`eval/dev.json`)**：$N=16$ 例（可回答 13 例，不可回答负例 3 例）。
- **冻结测试集 (`eval/test.json`)**：$N=12$ 例（可回答 10 例，不可回答负例 2 例）。
- **运行隔离**：独立评测入口 `--app.eval`，临时索引评测后自动清理，绝不修改生产索引 `data/index-public`。

### 2. 门禁参数校准依据 (Evidence on dev.json)
- **词法覆盖率门禁 (`app.lexical-coverage = 0.50`)**：
  负例案例 `d09`（*“他的奶茶项目服务过多少付费用户？”*）命中词法片段仅包含“奶茶”、“项目”，覆盖率为 2/5 (0.40)。若阈值设为 $\le 0.40$，该负例将进入召回池引发幻觉假答；设定为 **0.50** 成功将负例拦截，同时可回答正例的核心查询词覆盖率均 $\ge 0.67$，无误伤。
- **向量相似度门禁 (`app.vector-threshold`: onnx=0.805, hash=0.82, http=0.86)**：
  在开发集上，BGE-small-zh-v1.5 对负例最高分数为 `d09` 的 **0.8028**（Lucene 转换分 $\frac{1+\cos}{2}$）；所有正例分数均分布在 **0.8054 ~ 0.8729**。因此门禁下限严格重校准为 **0.805**，在彻底封堵负例（假答率降为 0.0%）的同时，保留最大语义泛化空间。

### 3. 开发集与冻结测试集真实跑分

#### 开发集表现 (Dev Set, N=16)

> 报告源文件：`eval/results/eval-dev-20261018-221808.json` (最新软链接: `eval/results/eval-dev-latest.json`)

| 模式 (Mode) | 实际后端 (Backend) | 索引构建耗时 | 预热 p50 延迟 | 预热 p95 延迟 | Recall@4 | Hit@4 | MRR@4 | 路由准确率 | 挂载来源率 | 有据回答率 | 假答率 (False Answer) | 误拒率 (False Refusal) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **lexical-only** | BM25-only | 1286ms | 6.32ms | 17.69ms | 6/22 (27.3%) | 6/13 (46.2%) | 0.410 | 13/16 (81.3%) | 11/13 (84.6%) | 9/13 (69.2%) | **0/3 (0.0%)** | 2/13 (15.4%) |
| **hash** | BM25 + Hash (256d) | 251ms | 6.05ms | 16.50ms | 6/22 (27.3%) | 6/13 (46.2%) | 0.423 | 13/16 (81.3%) | 11/13 (84.6%) | 9/13 (69.2%) | **0/3 (0.0%)** | 2/13 (15.4%) |
| **onnx** | BM25 + BGE-small (512d) | 2606ms | 25.54ms | 59.63ms | 6/22 (27.3%) | 6/13 (46.2%) | 0.385 | 13/16 (81.3%) | 11/13 (84.6%) | 9/13 (69.2%) | **0/3 (0.0%)** | 2/13 (15.4%) |

#### 冻结测试集表现 (Frozen Test Set, N=12，仅运行一次)

> 报告源文件：`eval/results/eval-test-20261018-221904.json` (最新软链接: `eval/results/eval-test-latest.json`)

| 模式 (Mode) | 实际后端 (Backend) | 索引构建耗时 | 预热 p50 延迟 | 预热 p95 延迟 | Recall@4 | Hit@4 | MRR@4 | 路由准确率 | 挂载来源率 | 有据回答率 | 假答率 (False Answer) | 误拒率 (False Refusal) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **lexical-only** | BM25-only | 1353ms | 4.81ms | 17.26ms | 5/16 (31.3%) | 5/10 (50.0%) | 0.450 | 11/12 (91.7%) | 7/10 (70.0%) | 7/10 (70.0%) | **0/2 (0.0%)** | 3/10 (30.0%) |
| **hash** | BM25 + Hash (256d) | 230ms | 7.22ms | 15.07ms | 5/16 (31.3%) | 5/10 (50.0%) | 0.500 | 11/12 (91.7%) | 7/10 (70.0%) | 7/10 (70.0%) | **0/2 (0.0%)** | 3/10 (30.0%) |
| **onnx** | BM25 + BGE-small (512d) | 1473ms | 37.37ms | 56.46ms | **6/16 (37.5%)** | **6/10 (60.0%)** | **0.533** | 11/12 (91.7%) | **8/10 (80.0%)** | **8/10 (80.0%)** | **0/2 (0.0%)** | **2/10 (20.0%)** |

> [!NOTE]
> **数据结论**：
> - **0.0% 假答率**在开发集与完全冻结的测试集上均保持稳固，双通道门禁实现了预期的安全防御边界。
> - **路由准确率**基于路由器意图真实度量，测试集上达到 **91.7%** (11/12)。
> - 在测试集上，ONNX 模式展现了更优的端到端能力：Hit@4 达 **60.0%**（提升 10%），MRR@4 达 **0.533**，有据回答率达 **80.0%**，误拒率下降至 **20.0%**，有效激活了语义泛化召回通道。
> - 推理延迟在轻量级 CPU 环境下 p50 为 37.37ms、p95 为 56.46ms，交互体验流畅敏捷。

#### 评测复现运行命令

```bash
# 运行开发集 (dev) 评测
java --enable-native-access=ALL-UNNAMED -jar target/digital-twin-0.1.0.jar \
  --app.eval --app.eval-set=dev --app.embedding-mode=onnx

# 运行测试集 (test) 评测
java --enable-native-access=ALL-UNNAMED -jar target/digital-twin-0.1.0.jar \
  --app.eval --app.eval-set=test --app.embedding-mode=onnx
```

---

## 完整配置项表格 (Configuration Table)

所有配置项均支持通过项目根目录的 `.env` 文件（Java properties 格式，如 `KEY=value`）、系统环境变量或命令行参数 `--app.key=value` 传入：

| Spring 配置属性 | 对应环境变量 | 默认值 | 合法取值 / 格式 | 功能与行为说明 |
|---|---|---|---|---|
| `server.port` | `PORT` | `8080` | `1024-65535` | HTTP 服务监听端口。 |
| `server.address` | `SERVER_ADDRESS` | `127.0.0.1` | IP 地址 (如 `0.0.0.0`) | 网络监听绑定地址。主机本地建议保留 `127.0.0.1`，容器环境需设为 `0.0.0.0`。 |
| `app.data-dir` | `DATA_DIR` | `./data` | 目录路径 | 数据根目录，存放 `public/`、`private/` 与 Lucene `index-public/`。 |
| `app.demo-data` | `DEMO_DATA` | `true` | `true` / `false` | 是否加载内置的 15 篇虚构示例条目。上线真实资料时设为 `false`。 |
| `app.import-file` | - | 空 | 文件路径 | 离线导入文件路径（支持 JSON、TXT、Markdown、文本型 PDF）。 |
| `app.approve-public` | - | `false` | `true` / `false` | 离线导入时是否显式批准导入公开存储 `data/public/`。 |
| `app.embedding-mode` | `EMBEDDING_MODE` | `auto` | `auto` / `onnx` / `http` / `hash` / `lexical` | 向量嵌入模式。`auto` 会依次探测配置完整的 `http` → 模型就绪的 `onnx` → 回退到纯词法 `lexical-only`。 |
| `app.model-dir` | `MODEL_DIR` | `models` | 目录路径 | 本地 ONNX 嵌入模型及词表存放目录（须含 `model.onnx` 与 `vocab.txt`）。 |
| `app.lexical-coverage`| `LEXICAL_COVERAGE` | `0.5` | `0.0 - 1.0` | 词法门禁覆盖率阈值。分块命中的查询分词比例须达此值才算相关。 |
| `app.vector-threshold`| `VECTOR_THRESHOLD` | 空 (自适应) | `0.0 - 1.0` | 向量门禁阈值。留空时依模式自动选用校准值（onnx=0.805, hash=0.82, http=0.86）。 |
| `app.http-timeout-seconds` | `HTTP_TIMEOUT_SECONDS` | `8` | `1 - 60` | 外部远程 HTTP 调用（JEV、生成、远程嵌入）单次超时时间（秒）。 |
| `app.jev-url` | `JEV_URL` | `https://api.typesafe.ai/v1/systemone` | HTTP(S) URL | 官方 JEV 决策路由端点。 |
| `app.jev-key` | `JEV_API_KEY` | 空 | 字符串 | JEV 访问密钥。留空则使用内置保守规则路由。 |
| `app.jev-model` | `JEV_MODEL` | `jev-latest` | 字符串 | JEV 路由模型标识。 |
| `app.jev-threshold` | `JEV_THRESHOLD` | `0.65` | `0.0 - 1.0` | JEV 决策置信度阈值。低于此阈值降级为本地规则路由。 |
| `app.generation-url` | `GEN_URL` | `https://api.openai.com/v1/chat/completions` | HTTP(S) URL | OpenAI 兼容的文本生成端点。 |
| `app.generation-key` | `GEN_API_KEY` | 空 | 字符串 | 生成大模型 API 密钥。留空则使用本地摘录模式。 |
| `app.generation-model`| `GEN_MODEL` | 空 | 字符串 | 生成大模型名称（如 `gpt-4o-mini`, `qwen-plus` 等）。与 key 配合生效。 |
| `app.embedding-url` | `EMBED_URL` | `https://api.openai.com/v1/embeddings` | HTTP(S) URL | OpenAI 兼容的远程文本向量化端点。 |
| `app.embedding-key` | `EMBED_API_KEY` | 空 | 字符串 | 远程向量模型 API 密钥。 |
| `app.embedding-model` | `EMBED_MODEL` | 空 | 字符串 | 远程向量模型名称（如 `text-embedding-3-small`, `bge-m3` 等）。 |
| `app.index-dir` | `INDEX_DIR` | 空 | 目录路径 | 自定义 Lucene 索引输出目录（评测时自动隔离指定，无需手动设定）。 |

---

## 许可与致谢 (License & Notice)

- 本系统核心业务代码遵循开源规范。
- **嵌入模型许可与致谢**：
  本系统内置或拉取的离线嵌入权重 `BAAI/bge-small-zh-v1.5` 由北京智源人工智能研究院（BAAI）研发并开源，遵循 **MIT 许可证**；ONNX 格式转换件由 Hugging Face Transformers.js (Xenova) 维护并发布（固定 commit: `75c43b069aac4d136ba6bc1122f995fedcfd2781`），同样遵循 **MIT 许可证**。
  完整的第三方版权声明与许可证全文请参阅仓库根目录下的 [NOTICE](NOTICE) 文件。

---

## 延伸阅读

- [ARCHITECTURE.md](ARCHITECTURE.md) —— 面向面试官与架构师的代码导读指南：类职责地图、请求端到端生命周期、核心设计权衡（单库 vs 独立向量库、CLS 池化、双通道门禁、进度流）与推荐代码阅读路线。
- [eval/CALIBRATION.md](eval/CALIBRATION.md) —— 双通道门禁（词法覆盖率 + 向量分数）在开发集上的参数校准实验证据与测试集指标报告。
