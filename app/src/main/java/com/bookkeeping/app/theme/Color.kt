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

/** 桌面小部件：次要标签文字色 */
val WidgetLabelGrey = Color(0xFFAAAAAA)

/** 桌面小部件：分隔线色 */
val WidgetDivider = Color(0xFF333333)
