# QuickJS 源兼容清单（对照 `D:\Users\15937\Desktop\box\0000\api.json`）

> 生成时间：2026-11-24
> 结论先行：这套订阅共 88 个站点，但**真正需要"移植 FongMi 新版 QuickJS 运行时"的只有 9 个**（新版 ESM Spider）。
> 其余 79 个走的是 drpy 规则源 / jar 源 / 原生 JSON API，AVBox 现有 `JsSpider` + `JarLoader` 已经覆盖，**不需要引入 FongMi 二进制运行时**。

## 1. 订阅构成（`api.json` → sites，共 88）

| 分类 | 数量 | api 形态 | AVBox 现状 | 是否要改 |
|---|---|---|---|---|
| drpy2 规则源 | 29 | `api=./api/drpy2.min.js`，`ext=./js/xxx.js` | `JsSpider` 有 `//DRPY`、`__jsEvalReturn` 分支，内置 `模板.js`/`net.js`/`cheerio`/`crypto-js`/`gbk` | 小改：相对路径解析（见 §4） |
| jar 源（`csp_` 前缀） | 15 | `api=csp_Xxx`，部分带 `jar=./jars/xxx.jar` | `JarLoader` 支持，但 `getCSP()` 只按 `.js`/`.py` 后缀分流，`csp_` 会落到 jarLoader（行为正确但需确认 jar 来源） | 中：确认 jar 可获取性 |
| 原生 JSON API | 33 | `api=http(s)://...` | `SpiderApi` 直接 HTTP，与 QuickJS 无关 | 不用改 |
| 新版 ESM Spider | 9 | `api=./js/bili_open.js` 等 4 个文件 | `JsSpider` 只认 `export default`/`__jsEvalReturn`，不认 `import {Spider} from 'catvod'` + `extends Spider` + 返回对象 | **重点：需移植接口层** |
| 其它 JS 引擎 | 2 | `apiv2542直转点.js`、`live2vod.js` | 同类规则源，走 drpy 分支 | 小改：相对路径解析 |

## 2. 需要重点处理的 9 个站点（新版 ESM Spider）

| 站点 key | api 文件 | 依赖特征 | 移植难度 |
|---|---|---|---|
| `bili_open0` ~ `bili_open4`（5 个） | `./js/bili_open.js` | `import {Crypto,jinja2,_} from './cat.js'`、`__jsEvalReturn`、`danmaku` | 中高：cat.js 已内置，但需 `__jsEvalReturn` + 弹幕回调 |
| `cntv` | `./cat/cntv_open.js` | `import`、`request` | 中 |
| `nangua` | `./cat/ng_open.js` | `import`、`crypto` | 中 |
| `subaibai` | `./cat/subaibai_open.js` | `import`、`crypto` | 中 |

这 9 个的共同点：都是 `import ... from './cat.js'` + ES Module 语法，且以 `export default` / `__jsEvalReturn` 导出 spider 对象。AVBox 的 `SPIDER_STRING_CODE` 已经有 `__jsEvalReturn` 兼容分支（`JsSpider.kt:704-713`），**但返回值按 String 处理**，而 ESM 源返回 JS 对象 → 需要补一层"对象 → JSON 字符串"的序列化。

> 注意：这些源文件**没有** `import host from 'catvod:host'`，也没有 `wasm`/`dialog`/`extends Spider`。
> 所以上一轮担心的"必须引入 FongMi 二进制运行时"在这套订阅里**不成立**——纯接口层适配即可。

## 3. 不用改的（占大头）

- **29 个 drpy2 规则源**：`js/*.js` 都是 `var rule = {...}` 格式，配合内置 `模板.js`，AVBox 的 `//DRPY` 字节码 + `__jsEvalReturn` 路径天然支持。
- **33 个 JSON API 源**：走 `SpiderApi` HTTP，与 JS 引擎无关。
- **15 个 csp_ jar 源**：走 `JarLoader`；其中 `jars/豆瓣.jar` 只有一个，其余靠订阅方的 jar 地址，需确认可用性（属配置问题，非代码问题）。

## 4. 真正要改的代码点（按优先级）

### P0 — 新版 ESM Spider 接口适配（9 个站点）
- `app/src/main/java/com/github/catvod/crawler/js/JsSpider.kt`
  - `SPIDER_STRING_CODE`（704-713）：新增 `extends Spider` 对象的识别与导出
  - `call()`（152-163）：ESM 源返回 JSObject，需 `stringify()` 后转 String 再返回
  - `initializeJS()`（304-357）：`import './cat.js'` 的相对路径解析（`UriUtil.resolve` 已接入，需确认基准名）
- 参考：`D:\Users\15937\Desktop\FongMi-TV\quickjs\src\main\assets\js\lib\spider.js`（入口包装思路，不引入其 runtime）

### P1 — 相对路径加载（影响 29+9 个站点）
- `app/src/main/java/com/github/avbox/osc/io/FileUtils.kt` `loadModule()`
  - 当前只支持：`URL_JOIN` 匹配、`assets://`、`js/lib/*`、`file://`、`clan://`
  - **不支持** `./api/drpy2.min.js`、`./js/xxx.js` 这类相对路径 → 返回 null → `isInvalidModuleContent` 判空 → spider 初始化失败
  - 建议：增加"相对配置基目录"解析分支（复用 `ConfigParser.fixContentPath` / `localFileBase` 的能力）
- `app/src/main/java/com/github/avbox/osc/api/SpiderLoader.kt` `getCSP()`（350-363）
  - `api.endsWith(".js") || api.contains(".js?")` 能覆盖 `./js/bili_open.js`，但 `./api/drpy2.min.js` 也能覆盖
  - `ext=./js/xxx.js` 作为规则文件传入 `JsSpider.init()`，需确认 `cfg()`/`Json.invalid` 分支处理（165-171）

### P2 — 补充 host API（仅当目标源需要）
- 这套订阅**未使用** `md5X`/`sha256X`/`randomX`/`desX`/`wasm`/`dialog`/`socket`/`danmakuUrl`/`page`/`link`
- 若后续引入 FongMi 官方源市场的新源，再按 `FongMi quickjs/method/Global.java` 逐个补

### P3 — jar 源可得性（配置层，非代码）
- 15 个 `csp_` 站点缺 jar 地址，需确认订阅方是否提供；`D:\...\box\0000\jars\` 下只有 `豆瓣.jar`

## 5. 验证方式（最小改动、不引入二进制运行时）

1. 先只改 `FileUtils.loadModule()` 的相对路径分支 + `JsSpider` 的 ESM 对象序列化
2. 用 `./js/bili_open.js`（6 个站点共用）做首个验证源
3. 用 `./api/drpy2.min.js` + `ext=./js/555电影.js` 做 drpy 验证源
4. 门禁：按 AGENTS.md 交付前检查清单跑（但注意"小修改禁止跑测试"红线）

## 6. 一句话结论

**不需要移植 FongMi 的 QuickJS 运行时**。你这套订阅里 90% 的源是 drpy 规则源和 JSON API，AVBox 已支持；真正要改的是：
1. `FileUtils.loadModule()` 的相对路径解析（解锁 38 个源）
2. `JsSpider` 的 ESM 对象序列化（解锁 9 个 bili/cat 系列源）

两处都是几十行的改动，属于低-中难度，不需要碰引擎层。
