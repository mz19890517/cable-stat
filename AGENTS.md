# AGENTS.md — AI 会话工作指引

安卓APP「线缆统计」：配电柜接线用料统计工具（Kotlin + Room + 传统View，零第三方云依赖）。
仓库即完整交付物：代码、CI、文档都在这里。**新会话请先读本文件再动手。**

## 业务模型

projects(配电柜项目，多项目分开统计) → records(用线/用铜记录，截一根记一根) + specs/colors(规格·颜色候选池)。

- records 字段：kind(wire/busbar) / spec(线缆=截面mm²数字串；铜排=宽×厚如"40×5") / color(仅线缆，铜排为""；市面常用国标线色) / lengthMm(毫米，唯一计量单位) / note / createdAt
- **累加语义**：不做预先生成的"台账行"，全部按 GROUP BY 规格×颜色 在内存累加（Repository.aggregate），同一规格×颜色自动汇总，改任何一段必须回归 AggregateTest
- 候选池首启种子：线缆规格 1.0~240 mm²(16档)、颜色 红/黄/绿/蓝/黄绿/黑/棕/白/灰(9色)、铜排规格 20×3~125×10(23档)；规格排序键 线缆=mm²数值、铜排=截面积
- 规格尾零归一：录入自定义线缆规格时 4.0→4 入库，避免与内置重复（Repository.normalizeWireLabel）
- 导出：零依赖 XlsxWriter(自研，兼容 Excel/WPS)；单项目=当前项目5张表，全部=全库5张表（项目汇总/线缆汇总/铜排汇总/线缆明细/铜排明细），走 SAF 生成文件，无需存储权限

## 构建方式

- 只通过 GitHub Actions 构建（push 到 main 自动触发，`gh run watch` 等结果）
- 本地无 gradle wrapper / Android SDK，不要尝试本地 assembleDebug
- 产物：Actions artifact `cablestat-debug-apk`；正式发布用 `gh release create vX.X <apk路径>`
- CI：JDK17 + Gradle 8.6 + platform-34/build-tools-34；先 testDebugUnitTest 后 assembleDebug

## ⚠️ 签名（最重要的约束）

包名 `com.cablestat.record`，alias=`cablestat`，签名链开线即固定，后续所有版本靠同一签名覆盖安装。签名材料：
- `app/signing/cablestat.keystore.zip` —— keystore 的加密压缩包（已入库）
- **解压密码不在仓库里，需要时向当前机器 ~/.config/gh/hosts.yml 的 GitHub 账户所有者（用户本人）索取**；GitHub Secrets 已存：`SIGNING_ZIP_PASSWORD` / `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_PASSWORD`
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