package com.flue.launcher

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.settings.miuix.MiuixLoadingIndicator
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
internal fun watchStoreUploadErrorMessage(context: Context, error: Throwable): String = when {
    error is WatchStoreHttpException && error.statusCode == 401 ->
        context.getString(R.string.watch_store_error_auth_expired)
    error is WatchStoreHttpException && error.statusCode == 400 ->
        error.detail?.let { context.getString(R.string.watch_store_error_rejected_detail_fmt, it) }
            ?: context.getString(R.string.watch_store_error_rejected)
    error is WatchStoreHttpException && error.statusCode == 413 ->
        context.getString(R.string.watch_store_error_package_too_large)
    error is WatchStoreHttpException && error.statusCode == 429 ->
        context.getString(R.string.watch_store_error_rate_limited)
    error is WatchStoreHttpException ->
        error.detail?.let {
            context.getString(R.string.watch_store_error_service_detail_fmt, error.statusCode, it)
        } ?: context.getString(R.string.watch_store_error_service_fmt, error.statusCode)
    error is WatchStorePayloadTooLargeException ->
        context.getString(R.string.watch_store_error_package_too_large)
    error is IllegalArgumentException || error is IllegalStateException ->
        error.message ?: context.getString(R.string.watch_store_error_upload)
    else ->
        error.message?.takeIf(String::isNotBlank)?.let {
            context.getString(R.string.watch_store_error_network_detail_fmt, it)
        } ?: context.getString(R.string.watch_store_error_network)
}

internal fun watchStoreAuthErrorMessage(
    context: Context,
    error: Throwable,
    register: Boolean,
): String = when {
    error is WatchStoreHttpException && error.statusCode == 401 ->
        context.getString(R.string.watch_store_error_invalid_credentials)
    error is WatchStoreHttpException && error.statusCode == 400 ->
        error.detail?.let { context.getString(R.string.watch_store_error_auth_rejected_detail_fmt, it) }
            ?: if (register) {
                context.getString(R.string.watch_store_error_auth_requirements)
            } else {
                context.getString(R.string.watch_store_error_invalid_credentials)
            }
    error is WatchStoreHttpException && error.statusCode == 409 ->
        context.getString(R.string.watch_store_error_username_taken)
    error is WatchStoreHttpException && error.statusCode == 429 ->
        context.getString(R.string.watch_store_error_auth_rate_limited)
    else -> watchStoreUploadErrorMessage(context, error)
}

/** 提交前的本地校验；返回 null 表示可以提交。 */
internal fun watchStoreAuthInputError(
    context: Context,
    username: String,
    password: String,
): String? {
    if (!username.trim().matches(WATCH_STORE_USERNAME_PATTERN)) {
        return context.getString(R.string.watch_store_error_username_format)
    }
    if (password.length < 6) return context.getString(R.string.watch_store_error_password_length)
    return null
}

private val WATCH_STORE_USERNAME_PATTERN = Regex("^[A-Za-z0-9_]{3,50}$")

internal enum class WatchStoreAuthAction {
    LOGIN,
    REGISTER,
}

internal enum class WatchStoreAuthPage {
    LOGIN,
    REGISTER,
}

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
    authPage: WatchStoreAuthPage,
    onAuthPageChange: (WatchStoreAuthPage) -> Unit,
    enabled: Boolean,
    loadingAction: WatchStoreAuthAction? = null,
    onLogin: () -> Unit,
    onRegister: () -> Unit,
) {
    val style = LauncherTheme.style
    val accentContentColor = if (style.accentColor.luminance() > 0.55f) Color.Black else Color.White
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(style.cardShape)
            .background(style.cardColor)
            .border(1.dp, style.outlineColor, style.cardShape)
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        Crossfade(
            targetState = authPage,
            animationSpec = tween(180),
            modifier = Modifier.fillMaxWidth(),
            label = "watch_store_auth_page",
        ) { page ->
            val isRegister = page == WatchStoreAuthPage.REGISTER
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(
                        if (isRegister) R.string.watch_store_register else R.string.watch_store_login,
                    ),
                    color = style.titleColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        if (isRegister) {
                            R.string.watch_store_register_security_note
                        } else {
                            R.string.watch_store_login_security_note
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = style.secondaryTextColor,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { onUsernameChange(it.take(50)) },
                    label = { Text(stringResource(R.string.watch_store_username)) },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    shape = style.rowShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = style.accentColor,
                        unfocusedBorderColor = style.outlineColor,
                        focusedContainerColor = style.screenBackground,
                        unfocusedContainerColor = style.screenBackground,
                        cursorColor = style.accentColor,
                        focusedTextColor = style.bodyColor,
                        unfocusedTextColor = style.bodyColor,
                    ),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { onPasswordChange(it.take(128)) },
                    label = { Text(stringResource(R.string.watch_store_password)) },
                    singleLine = true,
                    enabled = enabled,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    shape = style.rowShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = style.accentColor,
                        unfocusedBorderColor = style.outlineColor,
                        focusedContainerColor = style.screenBackground,
                        unfocusedContainerColor = style.screenBackground,
                        cursorColor = style.accentColor,
                        focusedTextColor = style.bodyColor,
                        unfocusedTextColor = style.bodyColor,
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = rememberUsername,
                        onCheckedChange = { checked ->
                            onRememberChange(checked, if (checked) rememberPassword else false)
                        },
                        enabled = enabled,
                        colors = CheckboxDefaults.colors(checkedColor = style.accentColor),
                    )
                    Text(
                        stringResource(R.string.watch_store_remember_username),
                        style = MaterialTheme.typography.bodySmall,
                        color = style.titleColor,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = rememberPassword,
                        onCheckedChange = { checked ->
                            onRememberChange(if (checked) true else rememberUsername, checked)
                        },
                        enabled = enabled,
                        colors = CheckboxDefaults.colors(checkedColor = style.accentColor),
                    )
                    Text(
                        stringResource(R.string.watch_store_remember_password),
                        style = MaterialTheme.typography.bodySmall,
                        color = style.titleColor,
                    )
                }
                val expectedAction = if (isRegister) {
                    WatchStoreAuthAction.REGISTER
                } else {
                    WatchStoreAuthAction.LOGIN
                }
                Button(
                    onClick = if (isRegister) onRegister else onLogin,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    shape = style.rowShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = style.accentColor,
                        contentColor = accentContentColor,
                    ),
                ) {
                    if (loadingAction == expectedAction) {
                        MiuixLoadingIndicator(
                            Modifier.size(17.dp),
                            strokeWidth = 2.dp,
                            color = accentContentColor,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        stringResource(
                            if (isRegister) R.string.watch_store_register else R.string.watch_store_login,
                        ),
                    )
                }
                TextButton(
                    onClick = {
                        onAuthPageChange(
                            if (isRegister) WatchStoreAuthPage.LOGIN else WatchStoreAuthPage.REGISTER,
                        )
                    },
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        stringResource(
                            if (isRegister) {
                                R.string.watch_store_back_to_login
                            } else {
                                R.string.watch_store_go_to_register
                            },
                        ),
                        color = style.accentColor,
                    )
                }
            }
        }
    }
}
