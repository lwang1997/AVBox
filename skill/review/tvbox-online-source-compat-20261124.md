# 线上订阅源兼容清单（以用户指定 api.json 为准）

- 订阅：`http://47.106.254.96:9120/d/home/TVBox/Private/tvbox/api.json`（2026-11-24 抓取，81,311 字节）
- 对比目标：FongMi-TV 当前实现
- 目的：找出 AVBox 加载不了、FongMi 能加载的源，确定改动点

## 一、站点构成（共 230 站）

| 分类 | 数量 | 说明 |
|---|---:|---|
| `csp_*` jar 源 | 126 | 全部走顶层 `spider: "./spider.jar"`（1,704,212 字节），92 个不同 csp 类名 |
| `./py/*.py` Python 源 | 99 | 其中 `./PyramidStore/plugin/html/4KAV.py` 1 个 |
| 原生 JSON API | 2 | `zuidazy.me`、`api.wujinapi.me`，与 JS/Python 无关 |
| drpy2 JS 源 | 1 | `api=./js/drpy2.min.js`，`ext=./js/采集之王.js?type=url&params=../json/采集静态.json$1$1` |
| 直播/空 api | 2 | 不属于点播源 |

## 二、逐类兼容性结论

### 1. 相对路径 —— 已覆盖，无需改

- `ConfigLoader.kt:537` 调用 `ConfigParser.fixContentPath()`，当配置含 `"./` / `"../` 时，把 `./py/xxx.py`、`./spider.jar` 等整体替换为绝对 URL。
- JS 模块相对 import：`JsSpider.createCtx()` 的 `moduleNormalizeName()` 用 `com.whl.quickjs.wrapper.UriUtil.resolve(base, name)`，`drpy2.min.js` 里的 `import ... from "./drpy-core-lite.min.js"` 能解析成绝对 URL。远端 `js/drpy-core-lite.min.js`（708,488 字节）尾部是标准 ESM `export {...}`，AVBox 的 `compileModule()` 可直接编译。
- 结论：这份订阅的相对路径链路是通的（与之前 `box/0000` 的判断不同）。

### 2. Python 源 —— 大部分兼容，2 站明确缺模块

已下载全部 99 个 `.py` 实测：

- 语法：全部可用 Python 3.10 解析（AVBox Chaquopy 版本为 3.10），无 3.11+ 语法。
- `base` 依赖：99 个都只 `from base.spider import Spider`；AVBox `pyramid/src/python/base/spider.py` 提供了全部标准方法（init/homeContent/categoryContent/detailContent/searchContent/playerContent/localProxy 等）。
- 第三方依赖统计：requests 44、urllib3 16、bs4 14、Crypto 13、java 6、pyquery 3、android 3、Cryptodome 2、cryptography 2、lxml 1、ujson 1。
- AVBox `pyramid/build.gradle.kts` 已装：lxml、ujson、pyquery 2.0.2、requests、jsonpath、cachetools、pycryptodome、beautifulsoup4（urllib3/ certifi 等由 requests 带入）。
- FongMi `chaquo/runtime/requirements.txt` 额外包含 **`cryptography==50.0.2`**、pywasm、numpy 等。

**明确缺口：`cryptography` 未在 AVBox 的 pip 列表里**，影响：
- `./py/hongguo.py`
- `./py/红果短剧.py`

这两站在 FongMi 可 import、在 AVBox 会 `ModuleNotFoundError`。改动：在 `pyramid/build.gradle.kts` 的 `pip { install(...) }` 增加 `install("cryptography")`（cffi/pycparser 会自动带入）。

注：`Cryptodome`（foxjun.py、nuomidj.py）在 FongMi 依赖表里也没有（只有 `pycryptodome` → `Crypto`），属于两边都可能失败，不是差异项。`世界听书.py` 里的 `from t4.base.spider import BaseSpider` 对应的 `py/t4.py` 在服务端返回 JSON 错误（object not found），也是两边都失败。

### 3. csp_ jar 源 —— 当前 jar 本体可直接加载，但宿主抽象层不对齐

实测 `spider.jar`：

- 92 个 csp 类名在 dex 中都存在（`csp_4KZhinan` 对应 binary 类名 `4KZhinan`，AVBox `JarLoader.className()` 能正确取到）。
- jar 内 spider 类调用的宿主 API 主要是 `SpiderDebug.log(String)`（326 处）与 `SpiderApi`（`log`/`getAddress`/`getPort`/`getScreenOrientation`/`multiReq`/`webParse`），AVBox 都有对应实现。
- 反编译后未发现 csp 主类直接依赖 `com.fongmi.quickjs.*`；`com.github.catvod.js.Function` 虽引用 FongMi QuickJS 类，但没有 csp 类调用它。
- 引用的 `com.github.catvod.debug.MainActivity` / `com.github.catvod.spider.crawler.SpiderDebug` 经查位于 debug Runnable 辅助类，属于调试 UI 分支，不是加载期必需。

不过 **FongMi 与 AVBox 的 jar 源宿主协议确实不同**，这是"向 FongMi 看齐"真正的结构性差距：

| FongMi 提供 | AVBox 现状 |
|---|---|
| `Spider` 基类有 `public Net net; public Local local; public final Js js; Dialog dialog;` | AVBox `Spider.kt` 只有 `siteKey`，无这些字段 |
| `JarLoader.newSpider()` 注入 `spider.net = new Net(JsHost.INSTANCE, key)`、`spider.local = new Local(key)` | AVBox `JarLoader.getSpider()` 未注入 |
| 父 ClassLoader 提供 `com.github.catvod.Runtime`、`net.Net`、`net.NetSocket`、`utils.UriUtil`、`utils.Local`、`utils.Js`、`utils.Dialog`、FongMi QuickJS 类 | AVBox 均缺失（只有 `Proxy`/`Spider`/`SpiderApi`/`OkHttp`/whl QuickJS） |

当前 spider.jar 不触发这些依赖，但**任何新 jar 源只要用了 `net.get()`、`local`、`js`、`dialog`、`Runtime`、`UriUtil`，在 AVBox 就会 `NoSuchFieldError` / `NoClassDefFoundError`**。其中 `Net.java` 1,246 行，是主要工作量。

### 4. drpy2 JS 源 —— 采集之王加载失败的根因已定位并修复

用户反馈"采集之王"加载不了，实测确认：

- 该站 `ext = ./js/采集之王.js?type=url&params=../json/采集静态.json$1$1`。规则 `host` 为空，靠 `params` 指向静态 JSON 补全 host/class（`采集之王.js` 中 `rule.params` → `request()` → `JSON.parse`）。
- **`params=../json/` 是相对规则文件 `js/` 目录**，正确地址 `tvbox/json/采集静态.json`（HTTP 200，10,852 字节）。
- AVBox 的 `ConfigParser.fixContentPath()` 用全局 `content.replace("../", ...)` 把它按"相对配置目录"解析成 `Private/json/采集静态.json`（HTTP 500）→ `JSON.parse` 失败 → 规则加载失败。
- FongMi 的 `Decoder.java` 用 `JS_URI` 正则只解析字段值开头的相对路径，query 内的用占位符保护后还原，所以同一配置在 FongMi 正常。

已修复：在 `ConfigParser.kt` 照 FongMi 机制新增 `protectJsUriQuery()`，`fixContentPath()` 改为"保护 query 相对路径 → 全局替换 → 还原"。回归测试 `ConfigParserTest.fixContentPath_keepsDrpyRuleQueryRelative` 已补，`:app:testDebugUnitTest --tests ConfigParserTest` 32 项全绿。

（原先"该 `../` 前面是 `=` 不会被替换"的判断是错的——`fixContentPath` 是无条件全局替换，不看前一个字符。）

## 三、建议改动顺序

1. **P0（小、确定）**：`pyramid/build.gradle.kts` 增加 `install("cryptography")`，解决 `红果短剧.py` / `hongguo.py`。
2. **P1（中）**：对齐 FongMi `Spider`/`JarLoader` 宿主协议——补 `net`/`local`/`js`/`dialog` 字段与注入逻辑；先做 `Local`/`Js`/`Dialog`/`UriUtil`/`Runtime` 等小类，`Net` 单独评估。
3. **P2（中高）**：若目标源改为 FongMi 新版 ESM Spider（`import {Spider} from 'catvod'`），再按前一份清单做 `catvod.js`/`spider.js`/`dialog.js` 三件套 + `SPIDER_STRING_CODE` 返回对象序列化 + host API 补齐。本订阅目前没有这类源。
4. **不做**：`//bb` 字节码互通（AVBox 硬编码 `BYTECODE_VERSION=67`，与 FongMi runtime 不兼容），Node 源（独立工程）。

## 四、待用户确认

静态分析显示这份订阅里绝大多数源理论上可加载。要精确定位"实际加载不了"的那几个，需要提供具体站点名或日志关键字（`echo-getSpider-error`、`ModuleNotFoundError`、`NoClassDefFoundError`、`NoSuchFieldError`、`compileModule-error`），可据此把改动收敛到最小集合。
