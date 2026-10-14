# 记账助手（Bookkeeping）

离线优先的安卓自动记账 App：**通知 / 短信自动抓取记账** + Jetpack Compose + Room。
所有数据只存在本地，无任何自建服务器、无网络上传。

- 包名：`com.bookkeeping.app` · minSdk 26（Android 8.0）· targetSdk 33 · compileSdk 35
- 当前版本：versionCode 1 / versionName 0.1.0

## 功能

### 自动记账
- 监听通知栏与短信，按用户自定义规则抓取金额、商户、时间，解析后进入**待确认队列**，确认才真正落库，不会静默记账
- 规则支持关键词（`|` 分隔多关键词）+ 正则，可分别指定金额、商户、时间字段的提取方式
- 抓取全链路写入本地日志（`files/service.log`），可自查为何某条没被解析

### 记账方式
- **语音记账**：sherpa-onnx 离线语音识别，说完自动解析填入表单确认，全程不联网
- **手动记账**：支出 / 收入 / 转账，支持多账本、多账户、备注、图片凭证

### 金额解析
- 关键词 + 多级正则的解析引擎，覆盖银行、支付平台、钱包类文案
- 口语金额：`55块8毛2` → 55.82，`3元2角` → 3.2，`3元5毛` → 3.5

### 账目管理
- 日历视图 / 明细列表 / 收支统计报表
- 预算（月度总预算与分类预算）、周期任务、信用卡分期
- 待报销、待回款、借贷三类专项台账
- 回收站（软删除，可恢复）

### 桌面小部件
- Glance 实现的桌面 Widget，展示今日 / 本月收支与最近一笔
- 透明背景，直接透出壁纸

### 其他
- 生物识别（指纹 / 人脸）应用锁
- CSV 导入导出、本地备份恢复
- 主题：浅色 / 深色 / 跟随系统；卡片色卡 + 不透明度可调；背景图自定义

## 技术栈

| 方向 | 选型 |
| --- | --- |
| 语言 | Kotlin |
| UI | Jetpack Compose + Material3 1.3.0 |
| 数据库 | Room 2.6.1 |
| 后台任务 | WorkManager 2.9.1 |
| 桌面小部件 | Glance 1.1.1 |
| 生物识别 | androidx.biometric 1.1.0 |
| 离线语音识别 | sherpa-onnx 1.13.8（本地 AAR） |
| 异步 | Kotlin Coroutines |

## 构建

```bash
# 编译验证
./gradlew :app:compileDebugKotlin

# 打 debug 包（约 300MB）
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> APK 体积说明：onnxruntime 与 sherpa-onnx 的 `.so` 库无法按 ABI 裁剪，是体积的主要来源。

需要 Android SDK 35，并在 `local.properties` 中配置 `sdk.dir`。
签名相关：`keystore.properties` 与 `keystore/` 不入库，缺失时自动退化为不签名，不影响编译。

## 目录结构

```
app/src/main/java/com/bookkeeping/app/
├── parser/          # 通知/短信解析引擎（ParseEngine）
├── service/         # 通知捕获前台服务、规则缓存
├── receiver/        # 短信广播、开机自启
├── widget/          # Glance 桌面小部件
├── worker/          # 周期任务（周期记账等）
├── data/            # Room 实体、DAO、数据库、仓库
├── theme/           # 主题、色板、通用下拉菜单
├── ui/              # 二级页面：借贷、报销、回款、分期、锁屏等
└── *Screen.kt       # 首页、明细、报表、设置、日历
```

## 隐私

- 无网络权限、无账号体系、无埋点统计
- 记账数据、备份文件、语音识别全部在设备本地完成
- 隐私政策：[privacy-policy/](privacy-policy/)
