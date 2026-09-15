# 线缆统计（CableStat）

配电柜接线用料统计安卓工具：**线缆 / 铜排**两类，做柜子时**截一根记一根**，同规格自动累加，随时看总量、一键导出 Excel。

纯离线，本地存储，无任何网络权限，无第三方 SDK。

## 功能

- **多项目分开统计**：一个配电柜一个项目，互不干扰
- **两大分类**：线缆（按截面 mm² + 颜色）和 铜排（按 宽×厚 mm）
- **增量累加**：每截一根就记一笔（长度单位：毫米），同一 规格×颜色 自动累加，实时汇总
- **候选池**：内置市面常用规格/颜色，可自定义增删
  - 线缆规格 16 档：1.0/1.5/2.5/4/6/10/16/25/35/50/70/95/120/150/185/240 mm²
  - 线缆颜色 9 色：红/黄/绿/蓝/黄绿/黑/棕/白/灰
  - 铜排规格 23 档：20×3、40×5、60×6、100×10…TMY 常用规格
- **Excel 导出**：单项目或全部，5 张工作表（项目汇总/线缆汇总/铜排汇总/线缆明细/铜排明细），单位同时给出 毫米 和 米，兼容 Excel/WPS
- 明细台账：每个规格×颜色的逐条记录，可查看时间、备注、删除

## 技术栈

Kotlin · Room · 传统 View（ViewBinding）· 零依赖自研 XlsxWriter · minSdk 28 / targetSdk 34

## 构建

仅通过 GitHub Actions 构建：push 到 main 自动触发。产物为 Actions artifact `cablestat-debug-apk`。

```bash
git add -A && git commit -m "..." && git push   # 触发 CI
gh run watch                                    # 等结果
gh run download <runId> -n cablestat-debug-apk  # 下载 APK
```

## 签名

固定签名链（alias=`cablestat`），后续所有版本可覆盖安装。keystore 为加密压缩包入库（app/signing/cablestat.keystore.zip），密码存于 GitHub Secrets（SIGNING_ZIP_PASSWORD / SIGNING_STORE_PASSWORD / SIGNING_KEY_PASSWORD），**密码请本人自行备份**。

## 测试

- 累加统计回归（AggregateTest）：同规格同色自动累加、排序（线缆按 mm² 数值、铜排按截面积）
- 规格解析 / 首启种子 / Excel 导出结构
- CI 先跑 testDebugUnitTest 再 assembleDebug