package com.flue.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.watchface.store.HttpWatchStoreApi
import com.flue.launcher.watchface.store.WatchStoreAuthSession
import com.flue.launcher.watchface.store.WatchStoreAuthStore
import com.flue.launcher.watchface.store.WatchStoreCredentialStore
import com.flue.launcher.watchface.store.WatchStoreHttpException
import com.flue.launcher.watchface.store.WatchStorePayloadTooLargeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 商店上传覆层（编辑器入口 + 商店页入口）共享的登录/注册表单、凭据记忆
 * 与错误文案映射。两个覆层的行为必须保持一致，因此逻辑只在这里出现一次。
 */

/** 把商店写接口的失败翻译成给用户看的人话。 */
internal fun watchStoreUploadErrorMessage(error: Throwable): String = when {
    error is WatchStoreHttpException && error.statusCode == 401 ->
        "登录已过期，请重新登录"
    error is WatchStoreHttpException && error.statusCode == 400 ->
        error.detail?.let { "内容被服务器拒绝：$it" }
            ?: "内容被服务器拒绝，请检查名称、描述和分类"
    error is WatchStoreHttpException && error.statusCode == 413 ->
        "表盘包超过 50MB 上限，请压缩图片资源后再试"
    error is WatchStoreHttpException && error.statusCode == 429 ->
        "操作太频繁，请等一分钟后再试"
    error is WatchStoreHttpException ->
        "商店服务返回了错误（${error.statusCode}）" +
            (error.detail?.let { "：$it" } ?: "，请稍后再试")
    error is WatchStorePayloadTooLargeException ->
        "表盘包超过 50MB 上限，请压缩图片资源后再试"
    error is IllegalArgumentException || error is IllegalStateException ->
        error.message ?: "上传失败"
    else ->
        error.message?.takeIf(String::isNotBlank)?.let { "网络请求失败：$it" }
            ?: "网络请求失败，请检查网络后重试"
}

internal fun watchStoreAuthErrorMessage(error: Throwable, register: Boolean): String = when {
    error is WatchStoreHttpException && error.statusCode == 401 ->
        "用户名或密码不正确"
    error is WatchStoreHttpException && error.statusCode == 400 ->
        error.detail?.let { "服务器拒绝了提交：$it" }
            ?: if (register) {
                "用户名或密码不符合要求（用户名 3-50 位字母数字下划线，密码至少 6 位）"
            } else {
                "用户名或密码不正确"
            }
    error is WatchStoreHttpException && error.statusCode == 409 ->
        "该用户名已被注册，换一个试试"
    error is WatchStoreHttpException && error.statusCode == 429 ->
        "尝试太频繁，请等一分钟后再试"
    else -> watchStoreUploadErrorMessage(error)
}

/** 提交前的本地校验；返回 null 表示可以提交。 */
internal fun watchStoreAuthInputError(username: String, password: String): String? {
    if (!username.trim().matches(WATCH_STORE_USERNAME_PATTERN)) {
        return "用户名需要 3-50 位字母、数字或下划线"
    }
    if (password.length < 6) return "密码至少需要 6 位"
    return null
}

private val WATCH_STORE_USERNAME_PATTERN = Regex("^[A-Za-z0-9_]{3,50}$")

/**
 * 在 IO 上完成登录/注册、令牌落盘与「记住账号/记住密码」的持久化。
 * 失败抛异常，由调用方经 [watchStoreAuthErrorMessage] 转成文案。
 */
internal suspend fun watchStoreLoginOrRegister(
    api: HttpWatchStoreApi,
    authStore: WatchStoreAuthStore,
    credentialStore: WatchStoreCredentialStore,
    username: String,
    password: String,
    register: Boolean,
    rememberUsername: Boolean,
    rememberPassword: Boolean,
): WatchStoreAuthSession = withContext(Dispatchers.IO) {
    val result = if (register) {
        api.register(username, password)
    } else {
        api.login(username, password)
    }
    val session = authStore.write(result.token, result.username)
    // 凭据记忆跟随本次勾选：两个开关都关时等价于清除既有记录。
    credentialStore.persist(
        username = result.username,
        password = password,
        rememberUsername = rememberUsername,
        rememberPassword = rememberPassword,
    )
    session
}

/**
 * 登录/注册表单。记住密码隐含记住账号：勾上密码会自动勾上账号，
 * 取消账号会连带取消密码——语义集中在这里，两个覆层不会漂移。
 */
@Composable
internal fun WatchStoreLoginSection(
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    rememberUsername: Boolean,
    rememberPassword: Boolean,
    onRememberChange: (rememberUsername: Boolean, rememberPassword: Boolean) -> Unit,
    enabled: Boolean,
    onLogin: () -> Unit,
    onRegister: () -> Unit,
) {
    val style = LauncherTheme.style
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = username,
            onValueChange = { onUsernameChange(it.take(50)) },
            label = { Text("用户名") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { onPasswordChange(it.take(128)) },
            label = { Text("密码") },
            singleLine = true,
            enabled = enabled,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = rememberUsername,
                onCheckedChange = { checked ->
                    onRememberChange(checked, if (checked) rememberPassword else false)
                },
                enabled = enabled,
            )
            Text("记住账号", style = MaterialTheme.typography.bodySmall, color = style.titleColor)
            Spacer(Modifier.width(10.dp))
            Checkbox(
                checked = rememberPassword,
                onCheckedChange = { checked ->
                    onRememberChange(if (checked) true else rememberUsername, checked)
                },
                enabled = enabled,
            )
            Text("记住密码", style = MaterialTheme.typography.bodySmall, color = style.titleColor)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onLogin,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) { Text("登录") }
            OutlinedButton(
                onClick = onRegister,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) { Text("注册") }
        }
        Text(
            "注册即创建表盘商店账号（用户名 3-50 位字母数字下划线，密码至少 6 位）。" +
                "勾选「记住密码」后，密码会经 Android 密钥库加密保存在本机；" +
                "未勾选时密码只用于本次登录，不会保存。",
            style = MaterialTheme.typography.labelSmall,
            color = style.secondaryTextColor,
        )
    }
}
