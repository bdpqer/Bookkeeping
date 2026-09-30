package com.bookkeeping.app.theme

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
