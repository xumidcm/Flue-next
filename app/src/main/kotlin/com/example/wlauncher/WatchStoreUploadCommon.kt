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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
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
import com.flue.launcher.ui.style.FlueButton
import com.flue.launcher.ui.style.FlueLoadingIndicator
import com.flue.launcher.ui.style.FlueExpressiveButtonGroup
import com.flue.launcher.ui.style.useMaterial3ExpressiveComponents
import com.flue.launcher.ui.style.FlueTextField
import com.flue.launcher.watchface.store.HttpWatchStoreApi
import com.flue.launcher.watchface.store.WatchStoreAuthSession
import com.flue.launcher.watchface.store.WatchStoreAuthStore
import com.flue.launcher.watchface.store.WatchStoreCredentialStore
import com.flue.launcher.watchface.store.MyWatchFaceEntry
import com.flue.launcher.watchface.store.WatchStoreHttpException
import com.flue.launcher.watchface.store.WatchStoreReviewStatus
import com.flue.launcher.watchface.store.WatchStoreNonCompliantArchiveException
import com.flue.launcher.watchface.store.WatchStorePayloadTooLargeException
import com.flue.launcher.watchface.store.WatchStoreUploadFinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 商店上传覆层（编辑器入口 + 商店页入口）共享的登录/注册表单、凭据记忆
 * 与错误文案映射。两个覆层的行为必须保持一致，因此逻辑只在这里出现一次。
 */

/** 把商店写接口的失败翻译成给用户看的人话。 */
internal fun watchStoreUploadErrorMessage(context: Context, error: Throwable): String = when {
    // 本地预检不通过：把具体违规项逐条列出来，作者才知道该改哪个图层的哪个属性。
    // 这条要排在 IllegalArgumentException 之前 —— 顺序错了会被后面那条泛化分支吃掉。
    error is WatchStoreNonCompliantArchiveException ->
        context.getString(
            R.string.watch_store_error_noncompliant_fmt,
            watchStoreNonCompliantDetail(context, error.findings),
        )
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

/**
 * 把预检结论排成逐行清单。
 *
 * 只列前几条：一块表盘可能有几十个图层同时缺属性（比如整批 ring 都是旧默认属性建的），
 * 全铺出来会把弹窗撑爆，作者看前几条就够动手了。致命项（禁用文件）排在最前面。
 */
private fun watchStoreNonCompliantDetail(
    context: Context,
    findings: List<WatchStoreUploadFinding>,
): String {
    val ordered = findings.sortedByDescending(WatchStoreUploadFinding::fatal)
    val shown = ordered.take(MAX_SHOWN_FINDINGS)
    return buildString {
        shown.forEach { finding ->
            append("• ").append(watchStoreNonCompliantLine(context, finding)).append('\n')
        }
        val remaining = ordered.size - shown.size
        if (remaining > 0) {
            append(context.getString(R.string.watch_store_noncompliant_more_fmt, remaining))
        }
    }.trimEnd()
}

private const val MAX_SHOWN_FINDINGS = 8

private fun watchStoreNonCompliantLine(
    context: Context,
    finding: WatchStoreUploadFinding,
): String = when (finding) {
    is WatchStoreUploadFinding.MissingFile ->
        context.getString(R.string.watch_store_noncompliant_missing_file_fmt, finding.name)
    is WatchStoreUploadFinding.BlockedFile ->
        context.getString(R.string.watch_store_noncompliant_blocked_file_fmt, finding.name)
    is WatchStoreUploadFinding.UnknownLayerType ->
        context.getString(
            R.string.watch_store_noncompliant_unknown_layer_fmt,
            finding.layerPosition,
            finding.type,
        )
    is WatchStoreUploadFinding.Format2LayerInFormat1 ->
        context.getString(
            R.string.watch_store_noncompliant_format2_layer_fmt,
            finding.layerPosition,
            finding.type,
        )
    is WatchStoreUploadFinding.MissingAttribute ->
        context.getString(
            R.string.watch_store_noncompliant_missing_attribute_fmt,
            finding.layerPosition,
            finding.type,
            finding.attribute,
        )
}

/** 搜索框文本拆成上游的两个查询字段。 */
internal data class WatchStoreSearchFields(
    val search: String?,
    val tag: String?,
)

/**
 * `#运动` 当标签搜，其余当名称搜。
 *
 * 上游把 `search` 与 `tag` 做成两个独立参数（`search` 同时匹配名称和标签、`tag` 只精确
 * 匹配标签）。手表屏幕上再摆一个「搜索模式」切换开关不值得，所以用 `#` 前缀区分 ——
 * 这也是详情页点标签跳搜索时会填进去的形式，两条路殊途同归。
 *
 * 上游 `tag` 限 1–50 字符，所以超长的 `#…` 退回当普通搜索，免得白吃一个 400。
 */
internal fun watchStoreSearchFields(raw: String): WatchStoreSearchFields {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("#")) {
        return WatchStoreSearchFields(search = trimmed.takeIf(String::isNotEmpty), tag = null)
    }
    val tag = trimmed.removePrefix("#").trim()
    return if (tag.isEmpty() || tag.length > MAX_STORE_TAG_LENGTH) {
        // 只打了一个 `#`、或者标签太长：当普通搜索，别把输入吃掉。
        WatchStoreSearchFields(search = trimmed, tag = null)
    } else {
        WatchStoreSearchFields(search = null, tag = tag)
    }
}

private const val MAX_STORE_TAG_LENGTH = 50

/**
 * 我的上传按审核状态分成「需要处理」与「已上架」两组。
 *
 * 这一分组是老师那条反馈的正面回答：上传**永远返回 201**（上游 `my.js` 明写
 * 「A scanner failure NEVER blocks the upload」），被拦只体现在 `status` 与
 * `rejection_reason` 上。列表按服务端顺序平铺的话，一块被驳回的表盘会混在几十块已上架的
 * 中间，用户永远发现不了 —— 所以需要处理的一律置顶。
 *
 * 组内**保持服务端顺序**（上游按创建时间倒序），不再自作主张排序。
 */
internal fun myUploadStatusGroups(
    entries: List<MyWatchFaceEntry>,
): Pair<List<MyWatchFaceEntry>, List<MyWatchFaceEntry>> {
    val attention = ArrayList<MyWatchFaceEntry>(entries.size)
    val published = ArrayList<MyWatchFaceEntry>(entries.size)
    entries.forEach { entry ->
        // UNKNOWN 也进「需要处理」：上游加了新状态而我们还不认识时，宁可让用户看见
        // 一块「状态未知」的表盘，也不要把它悄悄混进「已上架」里当作没事。
        if (entry.status == WatchStoreReviewStatus.PUBLISHED) published += entry else attention += entry
    }
    return attention to published
}

/** 状态徽标文案。`PUBLISHED` 不给徽标 —— 正常态不需要占一行。 */
internal fun watchStoreReviewStatusLabel(context: Context, status: WatchStoreReviewStatus): String? =
    when (status) {
        WatchStoreReviewStatus.PUBLISHED -> null
        WatchStoreReviewStatus.PENDING -> context.getString(R.string.watch_store_status_pending)
        WatchStoreReviewStatus.REJECTED -> context.getString(R.string.watch_store_status_rejected)
        WatchStoreReviewStatus.DELISTED -> context.getString(R.string.watch_store_status_delisted)
        WatchStoreReviewStatus.UNKNOWN -> context.getString(R.string.watch_store_status_unknown)
    }

/**
 * 社交类调用（评论 / 喜欢 / 评分 / 举报 / 合集 / 通知）的错误文案。
 *
 * 与上传、登录各自的映射分开写，因为**同一个状态码在这三类里的含义不同**：403 在上传里是
 * 没权限改别人的表盘，在社交里是**管理员关掉了这个功能**；409 在注册里是用户名被占，在
 * 举报里是同一块表盘同一原因已经报过了。混成一个映射就一定会给错提示。
 */
internal fun watchStoreSocialErrorMessage(context: Context, error: Throwable): String = when {
    error is WatchStoreHttpException && error.statusCode == 401 ->
        context.getString(R.string.watch_store_error_auth_expired)

    // 上游对被禁用的功能一律回 403。UI 本该按 /config 提前隐藏入口，这条是兜底 ——
    // 开关是缓存的，管理员刚关掉的那几分钟里用户还点得到。
    error is WatchStoreHttpException && error.statusCode == 403 ->
        context.getString(R.string.watch_store_error_feature_disabled)

    error is WatchStoreHttpException && error.statusCode == 404 ->
        context.getString(R.string.watch_store_error_gone)

    error is WatchStoreHttpException && error.statusCode == 409 ->
        context.getString(R.string.watch_store_error_already_reported)

    error is WatchStoreHttpException && error.statusCode == 429 ->
        context.getString(R.string.watch_store_error_rate_limited)

    error is WatchStoreHttpException ->
        error.detail?.takeIf(String::isNotBlank)
            ?: context.getString(R.string.watch_store_error_service_fmt, error.statusCode)

    error is IllegalArgumentException ->
        error.message?.takeIf(String::isNotBlank)
            ?: context.getString(R.string.watch_store_error_network)

    else -> context.getString(R.string.watch_store_error_network)
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
    registrationEnabled: Boolean = true,
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
            val isRegister = registrationEnabled && page == WatchStoreAuthPage.REGISTER
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
                FlueTextField(
                    value = username,
                    onValueChange = { onUsernameChange(it.take(50)) },
                    label = stringResource(R.string.watch_store_username),
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlueTextField(
                    value = password,
                    onValueChange = { onPasswordChange(it.take(128)) },
                    label = stringResource(R.string.watch_store_password),
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
                if (useMaterial3ExpressiveComponents(style.uiStyle)) {
                    FlueExpressiveButtonGroup(modifier = Modifier.fillMaxWidth()) {
                        FlueButton(
                            onClick = if (isRegister) onRegister else onLogin,
                            enabled = enabled,
                        ) {
                            if (loadingAction == expectedAction) {
                                FlueLoadingIndicator(size = 17.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                stringResource(
                                    if (isRegister) R.string.watch_store_register else R.string.watch_store_login,
                                ),
                            )
                        }
                        if (registrationEnabled) {
                            TextButton(
                                onClick = {
                                    onAuthPageChange(
                                        if (isRegister) WatchStoreAuthPage.LOGIN else WatchStoreAuthPage.REGISTER,
                                    )
                                },
                                enabled = enabled,
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
                } else {
                    FlueButton(
                        onClick = if (isRegister) onRegister else onLogin,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (loadingAction == expectedAction) {
                            FlueLoadingIndicator(size = 17.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            stringResource(
                                if (isRegister) R.string.watch_store_register else R.string.watch_store_login,
                            ),
                        )
                    }
                    if (registrationEnabled) {
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
    }
}
