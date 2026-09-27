# AGENTS.md — AI 会话工作指引

安卓APP「线缆统计」：配电柜接线用料统计工具（Kotlin + Room + 传统View，零第三方云依赖）。
仓库即完整交付物：代码、CI、文档都在这里。**新会话请先读本文件再动手。**

## 业务模型

projects(配电柜项目，多项目分开统计) → records(用线/用铜记录，截一根记一根) + specs/colors(规格·颜色候选池)。

- records 字段：kind(wire/busbar) / spec(线缆=单芯截面数字串如"1.5"，或多芯"3×2.5"/"4×4+1×2.5"；铜排=宽×厚如"40×5") / color(仅线缆，铜排为""；市面常用国标线色) / lengthMm(毫米，唯一计量单位) / note / createdAt
- 录入长度支持多行每行一根 + "85×5"同长多根批量（Repository.parseLengths 展开，根数上限500）；默认录入单位(mm/cm/m)存 SharedPreferences(util/LengthUnit)，录入时换算为毫米后入库，DB 内永远毫米
- **累加语义**：不做预先生成的"台账行"，全部按 GROUP BY 规格×颜色 在内存累加（Repository.aggregate），同一规格×颜色自动汇总，改任何一段必须回归 AggregateTest
- 候选池种子改为**增量补齐**（seedIfEmpty/seedSpecs）：只插入缺失项，老用户升级后也能补进新内置规格，重复启动不产生重复
- 候选池内置：线缆单芯 1.0~240 mm²(16档) + 多芯常用档(2/3/4/5芯 及 3+1/3+2/4+1)；颜色 9 色；铜排 20×3~125×10(23档)
- 规格排序键：单芯线缆=mm²数值；多芯线缆=基数10万+总芯数*1000+主截面（排在单芯之后，按芯数→截面）；铜排=截面积
- 线缆规格归一（Repository.normalizeWireSpec）：去空白、x/X/星号统一为 ×、单芯尾零归一（4.0→4）；多芯用 parseWireCoreInfo 识别
- 线缆分组（Repository.wireGroupKey/wireGroupTitle/wireGroupOrder）：单芯="single"，"3×2.5"="core3"，"3×2.5+1×1.5"="core3plus1"；录入弹窗与候选池管理按此分组
- 线缆规格合法性统一用 Repository.isValidWireSpec（单芯数字 或 多芯 芯数×截面），不要再用 wireKey==MAX_VALUE 判断
- 导出：零依赖 XlsxWriter(自研，兼容 Excel/WPS)；单项目=当前项目5张表，全部=全库5张表（项目汇总/线缆汇总/铜排汇总/线缆明细/铜排明细），走 SAF 生成文件，无需存储权限

## 构建方式

- 只通过 GitHub Actions 构建（push 到 main 自动触发，`gh run watch` 等结果）
- 本机（Android proot，aarch64）可本地编译：`/opt/gradle-8.2/bin/gradle :app:compileDebugKotlin -Pandroid.aapt2FromMavenOverride=/opt/aapt2-custom/aapt2`
- 本机**跑不了 Robolectric DB 用例**：Robolectric 原生/legacy SQLite 均不支持 linux-aarch64；纯 JVM 用例可本地跑，完整 `testDebugUnitTest` 交给 x86_64 的 CI
- 本地跑单测需 conscrypt ≥2.6（build.gradle.kts 已加 `conscrypt-openjdk-uber:2.7.0`），否则报 `conscrypt_openjdk_jni-linux-aarch_64` 缺失
- 产物：Actions artifact `cablestat-debug-apk`；正式发布用 `gh release create vX.X <apk路径>`
- CI：JDK17 + Gradle 8.6 + platform-34/build-tools-34；先 testDebugUnitTest 后 assembleDebug

## ⚠️ 签名（最重要的约束）

包名 `com.cablestat.record`，alias=`cablestat`，签名链开线即固定，后续所有版本靠同一签名覆盖安装。签名材料：
- `app/signing/cablestat.keystore.zip` —— keystore 的加密压缩包（已入库）
- **解压密码不在仓库里，需要时向当前机器 ~/.config/gh/hosts.yml 的 GitHub 账户所有者（用户本人）索取**；GitHub Secrets 已存：`SIGNING_ZIP_PASSWORD` / `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_PASSWORD`
- 注意：keystore 为 PKCS12，私钥只用 keystore 密码解锁 ⇒ `SIGNING_KEY_PASSWORD` 与 `SIGNING_STORE_PASSWORD` 必须相同（Android 签名库不做 keytool 那样的忽略，不同会 BadPaddingException）
- 原始 `cablestat.keystore` 被 .gitignore 排除，app/signing/ 下原件不要提交

规则：
1. 永远不要替换/删除 keystore，不要改动 alias 或密码配置
2. 永远不要把任何密码明文写进代码、README 或提交信息
3. 若用户忘记密码：keystore 无法恢复，签名链断裂 = 全体用户需卸载重装。提醒用户平时自备份

## 数据库约定

- Room schema 当前 version=1（首版，无历史迁移链）；新增表/字段必须 version+1 写纯SQL迁移，禁止 fallbackToDestructiveMigration
- 无网络功能，全部本地存储；不需任何运行时权限（导出用 SAF）

## 其他约定

- UI 文案全部走字符串常量/资源；中文注释是本仓库惯例
- 版本发布节奏：功能完成→versionName/versionCode 递增→README 更新→push→CI 绿→下载 APK 放项目根目录→（重要版本）发 GitHub Release