package com.bookkeeping.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.data.AppDatabase
import com.bookkeeping.app.data.entity.ParseRule
import kotlinx.coroutines.launch

// ─── 规则编辑弹窗 ────────────────────────────────────────────

@Composable
internal fun RuleEditDialog(
    initialRule: ParseRule?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isNew = initialRule == null

    var channelName by remember { mutableStateOf(initialRule?.channelName ?: "") }
    var channel by remember { mutableStateOf(initialRule?.channel ?: "") }
    var matchKeyword by remember { mutableStateOf(initialRule?.matchKeyword ?: "") }
    var patternAmount by remember { mutableStateOf(initialRule?.patternAmount ?: """(¥|人民币)\s*(\d+(?:\.\d{1,2})?)""") }
    var patternExpense by remember { mutableStateOf(initialRule?.patternExpense ?: "支出|消费|扣款|支付|汇出") }
    var patternIncome by remember { mutableStateOf(initialRule?.patternIncome ?: "收入|入账|转入|到账") }
    var patternTransfer by remember { mutableStateOf(initialRule?.patternTransfer ?: "转账|互联汇出|互联汇入") }
    var priority by remember { mutableStateOf(initialRule?.priority?.toString() ?: "50") }
    var isEnabled by remember { mutableStateOf(initialRule?.isEnabled ?: true) }
    var errorMsg by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0x80000000).copy(alpha = 0.5f),
        onClick = onDismiss
    ) {
        Box(contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .height(620.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (isNew) "新建规则" else "编辑规则",
                        fontWeight = FontWeight.Bold, fontSize = 18.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                                androidx.compose.material3.OutlinedTextField(
                                    value = channelName, onValueChange = { channelName = it },
                                    label = { Text("渠道名称（显示用）") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                                )
                                androidx.compose.material3.OutlinedTextField(
                                    value = channel, onValueChange = { channel = it },
                                    label = { Text("渠道标识（包名/代号）") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                                )
                                androidx.compose.material3.OutlinedTextField(
                                    value = matchKeyword, onValueChange = { matchKeyword = it },
                                    label = { Text("匹配关键词（包含即命中）") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                                )
                                androidx.compose.material3.OutlinedTextField(
                                    value = patternAmount, onValueChange = { patternAmount = it },
                                    label = { Text("金额正则") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                                )
                                androidx.compose.material3.OutlinedTextField(
                                    value = patternExpense, onValueChange = { patternExpense = it },
                                    label = { Text("支出关键词正则") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                                )
                                androidx.compose.material3.OutlinedTextField(
                                    value = patternIncome, onValueChange = { patternIncome = it },
                                    label = { Text("收入关键词正则") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                                )
                                androidx.compose.material3.OutlinedTextField(
                                    value = patternTransfer, onValueChange = { patternTransfer = it },
                                    label = { Text("转账关键词正则") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("优先级", fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    androidx.compose.material3.OutlinedTextField(
                                        value = priority, onValueChange = { priority = it.filter { c -> c.isDigit() } },
                                        singleLine = true, modifier = Modifier.width(100.dp)
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("启用", fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    Switch(checked = isEnabled, onCheckedChange = { isEnabled = it })
                                }

                                if (errorMsg.isNotEmpty()) {
                                    Text(errorMsg, color = Color(0xFFE53935), fontSize = 12.sp)
                                }

                                Spacer(Modifier.height(4.dp))
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (!isNew) {
                            androidx.compose.material3.TextButton(onClick = {
                                scope.launch {
                                    AppDatabase.getInstance(context).parseRuleDao()
                                        .deleteById(initialRule!!.id)
                                    refreshServiceRules()
                                    onSaved()
                                }
                            }) { Text("删除", color = Color(0xFFE53935)) }
                        }
                        androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
                        Spacer(Modifier.width(4.dp))
                        androidx.compose.material3.Button(onClick = {
                            if (channelName.isBlank() || channel.isBlank() || patternAmount.isBlank()) {
                                errorMsg = "名称/标识/金额正则不能为空"
                                return@Button
                            }
                            scope.launch {
                                val rule = ParseRule(
                                    id = initialRule?.id ?: 0,
                                    channel = channel,
                                    channelName = channelName,
                                    matchKeyword = matchKeyword,
                                    patternAmount = patternAmount,
                                    patternExpense = patternExpense,
                                    patternIncome = patternIncome,
                                    patternTransfer = patternTransfer,
                                    isEnabled = isEnabled,
                                    priority = priority.toIntOrNull() ?: 50
                                )
                                if (isNew) {
                                    AppDatabase.getInstance(context).parseRuleDao().insert(rule)
                                } else {
                                    AppDatabase.getInstance(context).parseRuleDao().update(rule)
                                }
                                refreshServiceRules()
                                onSaved()
                            }
                        }) { Text("保存") }
                    }
                }
            }
        }
    }
}
