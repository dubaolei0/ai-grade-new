# AI 智能作业批改（Spring Boot + 阿里云百炼 Qwen）

一个可直接运行的 Spring Boot 项目：上传学生作业图片，或「题干文本 + 作答图片」，由大模型完成**转录 → 解题批改**两个阶段的批改，并在单页前端实时流式展示转录结果、思考过程与评分。

## 一、功能与原理

严格对齐所给 Python 示例的两阶段设计，避免模型「先算出标准答案再脑补学生手写内容」：

| 阶段 | 输入 | `enable_thinking` | 作用 |
| --- | --- | --- | --- |
| 阶段一 · 转录 | 图片 + `transcribe_prompt` | `false` | 只照原样转录题干与学生答案，不解题、不判断对错 |
| 阶段二 · 批改 | `grade_prompt` + 转录文本 | `true` | 仅依据题干独立求标准答案，比对打分并按固定格式输出 |

两种批改模式：

- **整张作业图片**（`mode=image`）：图片里既有题干也有手写答案 → 原版 `transcribe_prompt` 逐题转录。
- **题干文本 + 作答图片**（`mode=question`）：题干由用户以文本给出，图片只拍手写答案 → 用「仅转录作答」的提示词变体转录，后端把题干与转录答案拼成与模式 A **完全一致的转录格式**，从而复用同一份 `grade_prompt`。

流式链路：浏览器 `POST /api/grade/stream` → Spring `SseEmitter`（SSE）→ JDK 21 内置 `HttpClient` 流式调用百炼 `/chat/completions` → 逐块转发 `delta.reasoning_content` / `delta.content` 给前端。

## 二、目录结构

```
ai-grade/
├── pom.xml
├── README.md
└── src/main/
    ├── java/com/yuanxuan/aigrade/
    │   ├── AiGradeApplication.java            启动类
    │   ├── config/
    │   │   ├── DashScopeProperties.java      百炼配置（api-key / base-url / model）
    │   │   └── WebConfig.java                CORS（允许 file:// 直接打开页面）
    │   ├── cli/AiGradeCli.java               纯 JDK 命令行版（Python 脚本 1:1）
    │   ├── controller/GradeController.java   /api/config、/api/grade/stream
    │   ├── model/GradeInput.java             批改输入 record
    │   ├── service/
    │   │   ├── DashScopeClient.java          流式 chat/completions 客户端
    │   │   └── GradeService.java            两阶段编排 + SSE 事件
    │   └── support/
    │       ├── Prompts.java                  三份提示词
    │       └── ImageUtils.java               图片 -> base64 data URL
    └── resources/
        ├── application.yml
        └── static/index.html                 独立单页前端
```

## 三、快速开始

### 1. 配置 API Key

Windows PowerShell：

```powershell
$env:DASHSCOPE_API_KEY = "sk-你的百炼APIKey"
```

Windows CMD：

```cmd
set DASHSCOPE_API_KEY=sk-你的百炼APIKey
```

Linux / macOS：

```bash
export DASHSCOPE_API_KEY=sk-你的百炼APIKey
```

> 也可以直接改 `src/main/resources/application.yml` 里的 `dashscope.api-key`，但不要把 Key 提交到仓库。

### 2. 启动

```bash
cd D:\yuanxuan\ai-grade
mvn spring-boot:run
```

或打包后运行：

```bash
mvn clean package -DskipTests
java -jar target/ai-grade-1.0.0.jar
```

### 3. 访问

浏览器打开 **http://localhost:8080/** 即可（前端页面由后端托管，无需额外起服务）。

也可以直接双击 `src/main/resources/static/index.html` 打开：页面在 `file://` 协议下会自动回连 `http://localhost:8080`，后端已开启 CORS。

## 四、配置项（application.yml）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `dashscope.api-key` | `${DASHSCOPE_API_KEY:}` | 百炼 API Key |
| `dashscope.base-url` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | OpenAI 兼容模式地址 |
| `dashscope.model` | `qwen3.8-max` | 模型名，可用环境变量 `DASHSCOPE_MODEL` 覆盖 |
| `dashscope.show-thinking` | `true` | 前端「显示思考过程」开关默认值 |
| `dashscope.connect-timeout-seconds` | `15` | 连接超时 |
| `server.port` | `8080` | 服务端口 |
| `spring.servlet.multipart.max-file-size` | `20MB` | 上传图片上限 |

> **模型说明**：`qwen3.8-max` 是百炼当前旗舰模型，同时支持**视觉理解**（可直接传图片）与**思考模式**，正好覆盖本项目「图片转录 + 开启思考批改」的需求。如需锁定版本可改成快照名（如 `qwen3.8-max-0902`）；若账号尚未开通，改成已开通的模型名即可 —— 全部通过配置切换，无需改代码。

## 五、接口说明

### GET /api/config

返回当前模型与 Key 配置状态：

```json
{"model":"qwen3.8-max","baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1","showThinking":true,"apiKeyConfigured":true}
```

### POST /api/grade/stream

`multipart/form-data`，响应 `text/event-stream`。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `mode` | string | `image`（默认）/ `question` |
| `questionText` | string | `mode=question` 时必填，题干文本 |
| `showThinking` | boolean | 是否推送思考过程，默认 `true` |
| `image` | file | 作业图片 / 作答图片 |

curl 示例：

```bash
curl -N -X POST http://localhost:8080/api/grade/stream \
  -F "mode=image" \
  -F "showThinking=true" \
  -F "image=@学生作答题.jpg"
```

题干文本模式：

```bash
curl -N -X POST http://localhost:8080/api/grade/stream \
  -F "mode=question" \
  -F "questionText=计算 √16 + 2³ 的值" \
  -F "image=@学生答案.jpg"
```

SSE 事件（`data:` 行为一行 JSON）：

```json
{"type":"stage","stage":"transcribe","label":"阶段一 · 作业转录"}
{"type":"delta","kind":"transcript","text":"第1题\n"}
{"type":"transcript","text":"第1题\n题干：...\n学生答案：..."}
{"type":"stage","stage":"grade","label":"阶段二 · 解题与批改"}
{"type":"delta","kind":"reasoning","text":"..."}
{"type":"delta","kind":"content","text":"第1题\n"}
{"type":"done","ok":true}
{"type":"error","message":"..."}
```

## 六、与 Python 示例的对应关系

| Python | 本项目 |
| --- | --- |
| `os.getenv("DASHSCOPE_API_KEY")` | `dashscope.api-key` / 环境变量 |
| `base_url=".../compatible-mode/v1"` | `dashscope.base-url` |
| `MODEL = "qwen3.8-max"` | `dashscope.model` |
| `to_image_url()` 本地图片转 base64 | `ImageUtils.toDataUrl()` |
| `chat(..., stream=True)` | `DashScopeClient.chatStream()` |
| `extra_body={"enable_thinking": thinking}` | 请求体顶层 `enable_thinking` |
| `delta.reasoning_content` | SSE `kind=reasoning` |
| `SHOW_THINKING` | 前端开关 + `showThinking` 参数 |
| `transcribe_prompt` / `grade_prompt` | `Prompts.java`（原文保留） |
| `grade_prompt.replace("{transcript}", transcript)` | `Prompts.GRADE.replace(...)` |

## 七、独立 Java 命令行版（Python 脚本 1:1 实现）

如果不想起 Web 服务，只想和原 Python 脚本一样在终端跑一次批改，项目里还提供了一份**纯 JDK 21、零第三方依赖、单文件**的 Java 实现：

`src/main/java/com/yuanxuan/aigrade/cli/AiGradeCli.java`

它逐项对应 Python 脚本：`to_image_url()` → `toImageUrl()`、`chat()` → `chat()`、`grade()` → `grade()`、`__main__` → `main()`；`SHOW_THINKING`、三段分隔线标题、流式打印方式、两阶段 `enable_thinking` 取值、命令行传入图片路径全部一致。提示词与 `Prompts.java` 保持同一份内容；为了零依赖，JSON 构造与 SSE `delta` 解析用 JDK 内置能力手写（见类里的 `escapeJson` / `deltaObject` / `jsonString`）。

### 编译运行

```bash
cd D:\yuanxuan\ai-grade
set DASHSCOPE_API_KEY=sk-你的百炼APIKey

javac -encoding UTF-8 -d target/cli-classes src/main/java/com/yuanxuan/aigrade/cli/AiGradeCli.java
java -cp target/cli-classes com.yuanxuan.aigrade.cli.AiGradeCli 学生作答题.jpg
```

JDK 11+ 也支持单文件源码启动，无需先编译：

```bash
java src/main/java/com/yuanxuan/aigrade/cli/AiGradeCli.java 学生作答题.jpg
```

图片参数支持本地路径或 `http(s)://` URL，不传则使用默认的 `学生作答题.jpg`。

### 可用参数

| 参数 | 说明 |
| --- | --- |
| 命令行第 1 个参数 | 作业图片路径 / URL |
| 环境变量 `DASHSCOPE_API_KEY` | 百炼 API Key |
| `-Ddashscope.apiKey=sk-xxx` | 同上，优先级更高 |
| `-Ddashscope.model=xxx` | 覆盖模型名，默认 `qwen3.8-max` |
| `-Ddashscope.baseUrl=xxx` | 覆盖接口地址（便于联调 / 代理） |
| `-DshowThinking=false` | 不打印转录结果与思考过程，只输出批改结果 |

### 终端输出（与 Python 版一致）

```text
==================== 转录结果 ====================
第1题
题干：计算 1+1
学生答案：2

==================== 思考过程 ====================
先独立求标准答案：1+1=2。再与转录出的学生答案比对。

==================== 批改结果 ====================
第1题
- 学生答案：2
- 标准答案：2
- 判定：✅正确
- 得分：10/10
【总分】10/10
```

> Windows 下若把输出重定向到文件或管道时出现中文乱码，加 `-Dstdout.encoding=UTF-8`；控制台直接运行一般无需处理。`System.out` 默认跟随终端编码，`✅` 等 emoji 在 GBK 代码页下可能显示为方框，可先执行 `chcp 65001`。

> CLI 与 Spring Boot 后端是**两套独立实现**，共用同一套提示词和调用协议：Web 版用 Jackson + `SseEmitter` 推流给浏览器，CLI 版零依赖直接打印到终端。按需二选一即可。

## 八、页面公式渲染（LaTeX → 可读排版）

转录提示词要求「公式用 LaTeX」，模型会输出 `\(\boldsymbol{\dfrac{x^2}{25}+\dfrac{y^2}{16}=1}\)` 这类源码。若直接当纯文本显示就是一堆反斜杠，可读性很差。前端内置了一个**零依赖、无 CDN** 的轻量 LaTeX 渲染器（`index.html` 中 `MATH_RENDERER_START` ~ `MATH_RENDERER_END`），把公式转成真实的数学排版：

| 原始 LaTeX | 页面显示 |
| --- | --- |
| `\boldsymbol{\dfrac{x^2}{25}+\dfrac{y^2}{16}=1}` | 粗体的 x²⁄25 + y²⁄16 = 1（分数线上下排布、上标） |
| `\sqrt{4}` / `\sqrt[3]{x}` | 带根号横线的 √4 / ³√x |
| `x_1^2` | x₁² |
| `\alpha\le\beta`、`\times`、`\pi` | α ≤ β、×、π |
| `\text{cm}`、`\binom{n}{k}`、`\boxed{2}` | 正体文本、组合数、带框答案 |

覆盖范围：`\(...\)` `\[...\]` `$...$` `$$...$$` 四种定界符；`\frac`/`\dfrac`/`\tfrac`/`\cfrac`（支持嵌套与 `\frac12` 写法）；上下标、根号、希腊字母、常用运算符与函数、粗体/正体/黑板体、重音符号；`\left`/`\right` 等排版命令自动忽略。文本中未包裹定界符的裸命令（如 `化简 \sqrt{16}`）同样会被处理。

几个实现要点：

- **作用于三处输出**：「用于批改的转录文本」「思考过程」「批改结果（流式区 + 完成后的题目卡片）」，全部改为 `innerHTML = renderMathText(原文)`，原来的 `textContent` 拼接已移除。
- **流式友好**：公式还没输出完整时，尾部按淡紫色等宽原文显示为「待完成」，闭合定界符到达后同一次重绘自动变成正式公式；渲染按 `requestAnimationFrame` 合并，不会逐 token 全量重绘。
- **可核对原文**：结果面板右上角勾选「原始 LaTeX」，会按当前累积结果立即重渲染为纯源码，不重新请求后端、不影响已批改内容。批改场景需要逐字符核对手写转录，这个开关是刻意保留的。
- **安全与降级**：所有文本都经过 HTML 转义；未识别的命令降级为「去掉反斜杠的命令名」而不是吞掉内容，保证转录原文不丢失。`cases` / `aligned` 等环境的对齐符降级为间隔。

> 它是面向作业批改可读性的轻量渲染器，不是完整 TeX 引擎；复杂矩阵、自定义宏等会降级为文本。需要出版级排版可自行替换为本地 KaTeX。CLI 版输出到终端，仍保留原始 LaTeX，不受此影响。

## 九、常见问题

- **提示「尚未配置 API Key」**：环境变量要在启动服务的同一个终端里设置，设置后需重启服务。
- **HTTP 401**：Key 无效或复制时带了空格。
- **HTTP 404 / model not found**：模型名与账号地域不匹配，按第四节改 `dashscope.model`。
- **批改很慢**：阶段二开启了思考，题量多时可能耗时 1~3 分钟；SSE 超时已设为 10 分钟。
- **中文乱码**：SSE 事件已用 UTF-8 字节写出；若自行改造请勿改回 String 写出。
- **图片过大**：超过 20MB 会被拒绝，建议压缩后再上传。
- **公式还是显示成源码**：确认浏览器已刷新到新页面（旧页面可能被缓存，强制刷新一次）；勾选「原始 LaTeX」会刻意显示源码，取消勾选即可。
- **启动日志显示端口不是 8080**：若环境里存在 `SERVER_PORT` / `SERVER__PORT` 一类环境变量，Spring Boot 宽松绑定会覆盖 `server.port`。显式指定即可：`java -jar target/ai-grade-1.0.0.jar --server.port=8080`。若确实改用其他端口，直接双击 HTML 打开时请同步修改 `index.html` 顶部的 `API` 常量。

## 十、技术栈

Spring Boot 3.2.5 · Java 21 · JDK 内置 HttpClient（无第三方 HTTP 依赖）· Jackson · 原生 HTML/CSS/JS 单页（无前端框架、无 CDN，可离线打开）· 内置轻量 LaTeX 渲染器。
