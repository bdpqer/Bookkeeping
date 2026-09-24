package com.bookkeeping.app.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.MessageDigest

/**
 * 锁定状态（进程级）。离开 App（onPause）时置 false，重新进入需解锁。
 */
object LockState {
    var unlocked = false
}

private const val PREFS = "settings"
private const val KEY_LOCK_ENABLED = "app_lock_enabled"
private const val KEY_BIOMETRIC = "app_lock_biometric"
private const val KEY_PIN_HASH = "app_lock_pin_hash"

private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

fun isLockEnabled(context: Context) = prefs(context).getBoolean(KEY_LOCK_ENABLED, false)
fun isBiometricUnlockEnabled(context: Context) = prefs(context).getBoolean(KEY_BIOMETRIC, false)
fun hasPinSet(context: Context) = !prefs(context).getString(KEY_PIN_HASH, null).isNullOrBlank()

fun setLockEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_LOCK_ENABLED, enabled).apply()
    if (!enabled) prefs(context).edit().putBoolean(KEY_BIOMETRIC, false).apply()
}

fun setBiometricEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_BIOMETRIC, enabled).apply()
}

fun setPin(context: Context, pin: String) {
    prefs(context).edit().putString(KEY_PIN_HASH, sha256(pin)).apply()
}

fun verifyPin(context: Context, input: String): Boolean {
    val hash = prefs(context).getString(KEY_PIN_HASH, null) ?: return false
    return sha256(input) == hash
}

/** 设备是否支持生物识别 */
fun canAuthenticateBiometric(context: Context): Boolean {
    return BiometricManager.from(context)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
        BiometricManager.BIOMETRIC_SUCCESS
}

private fun sha256(s: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

// ─── 锁屏覆盖层 ─────────────────────────────────────────────

@Composable
fun LockOverlay(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var pinInput by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    val biometricEnabled = remember { isBiometricUnlockEnabled(context) && canAuthenticateBiometric(context) }
    var biometricTried by remember { mutableStateOf(false) }

    // PIN 校验：输入到 4-6 位时尝试匹配，6 位仍不匹配则报错清空
    LaunchedEffect(pinInput) {
        if (pinInput.length >= 4) {
            if (verifyPin(context, pinInput)) {
                LockState.unlocked = true
                onUnlocked()
            } else if (pinInput.length == 6) {
                errorText = "PIN 不正确"
            }
        }
        if (pinInput.isEmpty()) errorText = ""
    }

    // 自动弹出指纹识别
    LaunchedEffect(Unit) {
        if (biometricEnabled && !biometricTried) {
            biometricTried = true
            (context as? FragmentActivity)?.let { activity ->
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(context),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            LockState.unlocked = true
                            onUnlocked()
                        }
                        // onAuthenticationError：用户取消/失败，继续用 PIN
                    }
                )
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("解锁记账助手")
                    .setNegativeButtonText("使用 PIN")
                    .build()
                prompt.authenticate(info)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(enabled = false) { /* 挡住下层点击 */ },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔒", fontSize = 40.sp)
            Spacer(Modifier.height(8.dp))
            Text("记账助手已锁定", fontSize = 18.sp, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(20.dp))

            // PIN 点阵
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(6) { idx ->
                    Box(
                        modifier = Modifier.size(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (idx < pinInput.length) 12.dp else 10.dp)
                                .background(
                                    if (idx < pinInput.length) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    CircleShape
                                )
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            if (errorText.isNotBlank()) {
                Text(errorText, color = Color(0xFFE53935), fontSize = 13.sp)
            } else if (biometricEnabled) {
                Text(
                    "👆 或点击下方指纹解锁",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))

            // 数字键盘
            val keys = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("", "0", "⌫")
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                keys.forEach { rowKeys ->
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        rowKeys.forEach { key ->
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        RoundedCornerShape(14.dp)
                                    )
                                    .clickable(enabled = key.isNotBlank()) {
                                        errorText = ""
                                        when (key) {
                                            "⌫" -> if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1)
                                            else -> if (pinInput.length < 6) {
                                                pinInput += key
                                                if (pinInput.length == 6 && !verifyPin(context, pinInput)) {
                                                    errorText = "PIN 不正确"
                                                }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    key,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (key.isBlank()) Color.Transparent else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // 指纹按钮
            if (biometricEnabled) {
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clickable {
                            (context as? FragmentActivity)?.let { activity ->
                                val prompt = BiometricPrompt(
                                    activity,
                                    ContextCompat.getMainExecutor(context),
                                    object : BiometricPrompt.AuthenticationCallback() {
                                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                            LockState.unlocked = true
                                            onUnlocked()
                                        }
                                    }
                                )
                                val info = BiometricPrompt.PromptInfo.Builder()
                                    .setTitle("解锁记账助手")
                                    .setNegativeButtonText("使用 PIN")
                                    .build()
                                prompt.authenticate(info)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("👆", fontSize = 28.sp)
                }
            }
        }
    }
}

// ─── PIN 设置弹窗（设置页用） ────────────────────────────────

@Composable
fun PinSetupDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置解锁 PIN") },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                    label = { Text("输入 4-6 位数字 PIN") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter(Char::isDigit).take(6) },
                    label = { Text("再次输入确认") },
                    singleLine = true
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFE53935), fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "⚠️ 请牢记 PIN。忘记后如未开启指纹，只能清除应用数据（会清空账目）。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pin.length < 4 -> error = "PIN 至少 4 位"
                    pin != confirm -> error = "两次输入不一致"
                    else -> {
                        setPin(context, pin)
                        onSaved()
                        onDismiss()
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
