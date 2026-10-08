package com.bookkeeping.app.theme

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// ---------- 业务色 ----------

/** 品牌主色：标题、强调文字、主按钮 */
val BrandBlue = Color(0xFF2E5AAC)

/** 品牌主色的深一档：次级强调、借入方向 */
val BrandBlueDeep = Color(0xFF1565C0)

/** 支出金额色 */
val ExpenseRed = Color(0xFFE53935)

/**
 * 危险/错误色：删除按钮、校验错误、逾期提示。
 * 当前与 [ExpenseRed] 同值，但语义独立，后续可单独调整。
 */
val DangerRed = Color(0xFFE53935)

/** 收入金额色 */
val IncomeGreen = Color(0xFF43A047)

/** 成功/已完成：已结清、已收款 */
val SuccessGreen = Color(0xFF4CAF50)

/** 转账类型色 / 待处理 */
val TransferOrange = Color(0xFFFF9800)

/** 借出方向色（比 [TransferOrange] 深一档） */
val LentOrange = Color(0xFFEF6C00)

/** 转账（账户互转）色 */
val TransferBlue = Color(0xFF1E88E5)

/** 暖色卡片背景：借出/提醒等需要突出显示的容器 */
val WarmCardBg = Color(0xFFFFF3E0)

// ---------- 桌面小部件配色（全透明底，固定色） ----------
// 小部件背景全透明，直接透出壁纸。这里**不做明暗自适应**——实测在复杂照片壁纸上
// 「壁纸主色亮度」判定并不可靠（深绿植物壁纸被误判成亮壁纸，于是给了深色字，
// 压在深绿上几乎看不清）。因此固定一套：文字纯白 + 提亮过的红/绿金额，
// 面向中深色壁纸；浅色壁纸下白字会偏淡，这是透明底方案的固有限制。

/** 小部件全部文字色（标题、各统计标签、日期、时间）：纯白 */
val WidgetText = Color(0xFFFFFFFF)

/** 小部件支出金额色（提亮暖红，深底可读） */
val WidgetExpense = Color(0xFFFF7B72)

/** 小部件收入金额色（提亮青绿，深底可读） */
val WidgetIncome = Color(0xFF56D364)

/** 小部件品牌强调色：标题前的装饰竖条 */
val WidgetAccent = Color(0xFF4C7DD9)

/** 小部件分隔线色（半透明白，叠加在壁纸上） */
val WidgetDivider = Color(0x1FFFFFFF)

// ---------- 语义补色（原先散落在业务文件里的裸 Color(0x…)） ----------

/** 渐变横幅（本月结余卡）：蓝 → 青 → 金 */
val BannerGradient = listOf(Color(0xFF4A90D9), Color(0xFF7EC8E3), Color(0xFFE8C468))

/**
 * 抽屉二级页面统一背景：与 ModalDrawerSheet 完全相同的竖向渐变（蓝 → 青 → 金）。
 *
 * ⚠️ 为什么二级页面不能用透明容器：抽屉里的二级页面是以叠层方式盖在首页之上的，
 * Scaffold 一旦 containerColor=Transparent，首页内容连同背景图会一起透上来，
 * 二级页面的文字压在下层内容上完全糊成一团、无法单独阅读。
 * 这里给它们铺一层和抽屉同源的实底渐变，视觉上与抽屉连贯，同时能独立显示。
 *
 * 用法：`Scaffold(modifier = Modifier.drawerGradientBackground(), containerColor = Color.Transparent, ...)`
 * 注意必须先 Scaffold 的 containerColor 为透明，否则默认不透明 surface 会把渐变盖住。
 */
fun Modifier.drawerGradientBackground(): Modifier =
    this.background(Brush.verticalGradient(BannerGradient))

/** 预算卡圆形底：支出的 10% 淡红 */
val ExpenseRedSoft = Color(0x1AE53935)

/** 逾期/标记淡红底：支出的 13% */
val ExpenseRedFaint = Color(0x22E53935)

/** 分类图表配色（Tableau 10 色系），循环取用 */
val ChartPalette = listOf(
    Color(0xFF4E79A7), Color(0xFFF28E2B), Color(0xFFE15759), Color(0xFF76B7B2),
    Color(0xFF59A14F), Color(0xFFEDC948), Color(0xFFB07AA1), Color(0xFFFF9DA7),
    Color(0xFF9C755F), Color(0xFFBAB0AC)
)

/** 弹窗遮罩：50% 黑 */
val ScrimBlack = Color(0x80000000)

/** 短信来源标签色（与通知蓝 [TransferBlue] 区分） */
val SmsPurple = Color(0xFF8E24AA)

/** 借出卡标题色：暖棕 */
val LentCardLabel = Color(0xFF8D6E63)

/** 借入卡背景：淡蓝 */
val BorrowCardBg = Color(0xFFE3F2FD)

/** 借入卡标题色：蓝灰 */
val BorrowCardLabel = Color(0xFF546E7A)

/** 语音浮层提示文字（深底上的浅灰蓝） */
val VoiceHint = Color(0xFFB9C6DC)

/** 语音识别成功金额色（深底上的亮绿） */
val VoiceSuccess = Color(0xFF7EE38B)

/** 语音识别告警色（深底上的亮黄） */
val VoiceWarning = Color(0xFFFFC857)

/** 悬浮记账按钮底色：淡蓝 */
val FabBg = Color(0xFFDCEBFF)
