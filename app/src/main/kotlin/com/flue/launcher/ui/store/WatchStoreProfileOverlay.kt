package com.flue.launcher.ui.store

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.R
import com.flue.launcher.throwIfCancellation
import com.flue.launcher.ui.common.FlueImageCropOverlay
import com.flue.launcher.ui.common.InternalFileBrowserHost
import com.flue.launcher.ui.common.InternalFileBrowserRequest
import com.flue.launcher.ui.style.FlueAlertDialog
import com.flue.launcher.ui.style.FlueLoadingIndicator
import com.flue.launcher.ui.style.FlueSettingsRow
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.watchStoreSocialErrorMessage
import com.flue.launcher.watchface.store.HttpWatchStoreApi
import com.flue.launcher.watchface.store.WatchStoreAccount
import com.flue.launcher.watchface.store.WatchStoreAuthStore
import com.flue.launcher.watchface.store.WatchStoreCredentialStore
import com.flue.launcher.watchface.store.WatchStoreProfileUpdate
import com.flue.launcher.watchface.store.WatchStoreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Edit

/**
 * H2 — 个人资料页（入口在市场溢出菜单）。
 *
 * ## 为什么每一项都是「点开一个弹窗改」，而不是一屏输入框
 *
 * 手表屏上一次只放得下一个输入框加一个键盘。把简介 / 时区 / 语言并排摆成表单，键盘一升起来
 * 就会把正在改的那一格顶出可视区。所以这一页是**只读的行 + 点开改一项**：改完立刻 PATCH，
 * 没有「保存」按钮 —— 不存在「改了三项忘了保存」这种状态。
 *
 * ## 头像
 *
 * 点击走系统 SAF，**长按走内置文件选择器**（老师明确要的：很多 Wear 机型没有 SAF）。
 * 两条路都落到同一个 1:1 裁剪覆层（[FlueImageCropOverlay]），再由 [cropAndEncodeAvatar]
 * 压到上游约束内。上传成功后**只改本地那一份头像地址**（[WatchStoreAuthStore.writeAvatarUrl]），
 * 不重拉整个账号 —— 上游那个端点直接把新地址回给我们了。
 *
 * ## 资料先画缓存，再覆盖
 *
 * 进页面先用登录时缓存的 profile 快照渲染（[WatchStoreAuthStore] 里那几项），同时拉
 * `/my/me`。手表上一次往返肉眼可见，先画上次的名字比先转圈好；拉回来再覆盖。
 */
@Composable
fun WatchStoreProfileOverlay(
    repository: WatchStoreRepository,
    api: HttpWatchStoreApi,
    token: String,
    onOpenWeb: () -> Unit,
    onOpenWebExternal: () -> Unit,
    onLogout: () -> Unit,
    onTokenExpired: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authStore = remember(context) { WatchStoreAuthStore(context) }
    val credentialStore = remember(context) { WatchStoreCredentialStore(context) }
    val cached = remember(authStore) { authStore.read() }

    var account by remember(cached) { mutableStateOf(cached?.toProfileAccountSnapshot()) }
    var avatarUrl by remember { mutableStateOf(cached?.avatarUrl) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    /** 非空时盖一层转圈 + 文案（上传头像 / 提交资料）。 */
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var logoutConfirmVisible by remember { mutableStateOf(false) }

    var editing by remember { mutableStateOf<ProfileEdit?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }
    var cropUri by remember { mutableStateOf<Uri?>(null) }
    var browserRequest by remember { mutableStateOf<InternalFileBrowserRequest?>(null) }

    val imagePicker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) cropUri = uri
    }

    LaunchedEffect(token) {
        loading = true
        loadError = null
        runCatching { withContext(Dispatchers.IO) { api.getAccount(token) } }
            .onSuccess { fetched ->
                account = fetched
                avatarUrl = fetched.avatarUrl
                // 快照刷新一次：下次进来首屏就是最新的。
                withContext(Dispatchers.IO) { authStore.writeProfile(fetched) }
            }
            .onFailure { error ->
                error.throwIfCancellation()
                if (error.isTokenExpired()) {
                    onTokenExpired()
                    return@LaunchedEffect
                }
                loadError = watchStoreSocialErrorMessage(context, error)
            }
        loading = false
    }

    /** 提交一项资料修改。成功后用服务端回的账号对象覆盖本地 —— 不猜服务端做了什么规范化。 */
    fun submit(update: WatchStoreProfileUpdate) {
        busyMessage = context.getString(R.string.watch_store_profile_saving)
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.updateProfile(token, update) } }
                .onSuccess { updated ->
                    account = updated
                    avatarUrl = updated.avatarUrl
                    withContext(Dispatchers.IO) { authStore.writeProfile(updated) }
                    notice = context.getString(R.string.watch_store_profile_saved)
                }
                .onFailure { error ->
                    error.throwIfCancellation()
                    if (error.isTokenExpired()) onTokenExpired()
                    else notice = watchStoreSocialErrorMessage(context, error)
                }
            busyMessage = null
        }
    }

    BackHandler { onDismiss() }

    val current = account
    val username = current?.username ?: cached?.username.orEmpty()

    StoreLibraryScaffold(
        title = stringResource(R.string.watch_store_profile),
        subtitle = username.takeIf(String::isNotBlank),
        onBack = onDismiss,
        action = null,
        loading = loading && current == null,
        loadError = loadError,
    ) {
        val style = LauncherTheme.style
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box {
                    StoreAvatar(
                        repository = repository,
                        avatarUrl = avatarUrl,
                        name = username,
                        size = 76.dp,
                        onClick = { imagePicker.launch(AVATAR_PICKER_MIME_TYPES) },
                        onLongClick = {
                            browserRequest = InternalFileBrowserRequest(
                                title = context.getString(R.string.watch_store_profile_pick_avatar),
                                extensions = AVATAR_IMAGE_EXTENSIONS,
                                onSelected = { uris ->
                                    browserRequest = null
                                    cropUri = uris.firstOrNull()
                                },
                            )
                        },
                    )
                    // 一个小铅笔角标：圆头像本身看不出「可以点」。
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(24.dp)
                            .clip(style.rowShape)
                            .background(style.cardColor)
                            .border(1.dp, style.outlineColor, style.rowShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            MiuixIcons.Edit,
                            contentDescription = null,
                            tint = style.accentColor,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
                Text(
                    stringResource(R.string.watch_store_profile_avatar_hint),
                    color = style.secondaryTextColor,
                    fontSize = 10.sp,
                )
            }

            ProfileValueRow(
                label = stringResource(R.string.watch_store_profile_username),
                value = username.takeIf(String::isNotBlank),
                onClick = null,
            )
            ProfileValueRow(
                label = stringResource(R.string.watch_store_profile_description),
                value = current?.description?.takeIf(String::isNotBlank),
                onClick = {
                    editing = ProfileEdit(
                        ProfileFieldKind.DESCRIPTION,
                        current?.description.orEmpty(),
                    )
                },
            )
            ProfileValueRow(
                label = stringResource(R.string.watch_store_profile_timezone),
                value = current?.timezone?.takeIf(String::isNotBlank),
                onClick = {
                    editing = ProfileEdit(ProfileFieldKind.TIMEZONE, current?.timezone.orEmpty())
                },
            )
            ProfileValueRow(
                label = stringResource(R.string.watch_store_profile_language),
                value = current?.language?.takeIf(String::isNotBlank)?.let(::storeLanguageLabel),
                onClick = {
                    editing = ProfileEdit(ProfileFieldKind.LANGUAGE, current?.language.orEmpty())
                },
            )
            ProfileValueRow(
                label = stringResource(R.string.watch_store_profile_change_password),
                value = null,
                onClick = { passwordVisible = true },
            )
            Spacer(Modifier.height(2.dp))
            StoreWebLinks(
                label = stringResource(R.string.watch_store_web_open),
                onOpenInApp = onOpenWeb,
                onOpenExternal = onOpenWebExternal,
            )
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = { logoutConfirmVisible = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.watch_store_logout),
                    color = LauncherTheme.style.secondaryTextColor,
                )
            }
        }
    }

    if (logoutConfirmVisible) {
        FlueAlertDialog(
            onDismissRequest = { logoutConfirmVisible = false },
            title = stringResource(R.string.watch_store_logout),
            text = stringResource(R.string.watch_store_logout_confirm),
            confirmText = stringResource(R.string.watch_editor_confirm),
            onConfirm = {
                logoutConfirmVisible = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        authStore.clear()
                        credentialStore.clear()
                    }
                    onLogout()
                }
            },
            dismissText = stringResource(R.string.action_cancel),
        )
    }

    // ── 各项编辑弹窗 ────────────────────────────────────────────────────────
    when (editing?.field) {
        ProfileFieldKind.DESCRIPTION -> ProfileTextDialog(
            title = stringResource(R.string.watch_store_profile_description),
            initial = editing?.value.orEmpty(),
            maxLength = WatchStoreProfileUpdate.MAX_DESCRIPTION,
            singleLine = false,
            onDismiss = { editing = null },
            onConfirm = { text ->
                editing = null
                // 允许清空简介：上游把空串当「删掉」，这与 `require(至少改一项)` 不冲突。
                submit(WatchStoreProfileUpdate(description = text))
            },
        )

        ProfileFieldKind.TIMEZONE -> ProfileChoiceDialog(
            title = stringResource(R.string.watch_store_profile_timezone),
            current = editing?.value.orEmpty(),
            options = WATCH_STORE_PROFILE_TIMEZONES,
            label = { it },
            onDismiss = { editing = null },
            onConfirm = { timezone ->
                editing = null
                submit(WatchStoreProfileUpdate(timezone = timezone))
            },
        )

        ProfileFieldKind.LANGUAGE -> ProfileLanguageDialog(
            current = editing?.value.orEmpty(),
            onDismiss = { editing = null },
            onConfirm = { code ->
                editing = null
                submit(WatchStoreProfileUpdate(language = code))
            },
        )

        null -> Unit
    }

    if (passwordVisible) {
        ProfilePasswordDialog(
            onDismiss = { passwordVisible = false },
            onConfirm = { currentPassword, newPassword ->
                passwordVisible = false
                busyMessage = context.getString(R.string.watch_store_profile_saving)
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            api.changePassword(token, currentPassword, newPassword)
                        }
                    }.onSuccess {
                        notice = context.getString(R.string.watch_store_profile_password_changed)
                    }.onFailure { error ->
                        error.throwIfCancellation()
                        if (error.isTokenExpired()) onTokenExpired()
                        else notice = watchStoreSocialErrorMessage(context, error)
                    }
                    busyMessage = null
                }
            },
        )
    }

    cropUri?.let { pending ->
        FlueImageCropOverlay(
            uri = pending,
            title = stringResource(R.string.watch_store_profile_crop_title),
            hint = stringResource(R.string.watch_store_profile_crop_hint),
            // 头像永远画在一个圆里，裁剪框锁 1:1。
            aspectRatio = 1f,
            onCancel = { cropUri = null },
            onConfirm = { sourceRect ->
                cropUri = null
                busyMessage = context.getString(R.string.watch_store_profile_uploading_avatar)
                scope.launch {
                    when (val encoded = cropAndEncodeAvatar(context, pending, sourceRect)) {
                        AvatarEncodeResult.DecodeFailed -> {
                            busyMessage = null
                            notice = context.getString(
                                R.string.watch_store_profile_avatar_unreadable,
                            )
                        }

                        AvatarEncodeResult.TooLarge -> {
                            busyMessage = null
                            notice = context.getString(
                                R.string.watch_store_profile_avatar_too_large,
                            )
                        }

                        is AvatarEncodeResult.Success -> {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    api.uploadAvatar(token, encoded.file)
                                }
                            }.onSuccess { uploadedUrl ->
                                // 上游回的是新地址。它可能与旧地址同名（覆盖上传），所以
                                // 即便字符串没变也要让头像重新取一次 —— 这里靠 remember key
                                // 变化做不到，交给下面的 avatarUrl 赋值 + 缓存写入。
                                avatarUrl = uploadedUrl ?: avatarUrl
                                withContext(Dispatchers.IO) {
                                    authStore.writeAvatarUrl(uploadedUrl)
                                }
                                notice = context.getString(R.string.watch_store_profile_saved)
                            }.onFailure { error ->
                                error.throwIfCancellation()
                                if (error.isTokenExpired()) onTokenExpired()
                                else notice = watchStoreSocialErrorMessage(context, error)
                            }
                            // 上传用的临时文件用完就删，不留在缓存目录里。
                            withContext(Dispatchers.IO) { encoded.file.delete() }
                            busyMessage = null
                        }
                    }
                }
            },
        )
    }

    InternalFileBrowserHost(
        request = browserRequest,
        onDismiss = { browserRequest = null },
    )

    busyMessage?.let { message ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LauncherTheme.style.screenBackground.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FlueLoadingIndicator(size = 28.dp)
                Spacer(Modifier.height(8.dp))
                Text(message, color = LauncherTheme.style.bodyColor, fontSize = 12.sp)
            }
        }
    }

    notice?.let { message ->
        FlueAlertDialog(
            onDismissRequest = { notice = null },
            title = stringResource(R.string.watch_store_profile),
            text = message,
            confirmText = stringResource(R.string.watch_editor_confirm),
            onConfirm = { notice = null },
        )
    }
}

/** 正在编辑哪一项。带上进弹窗时的初值，免得弹窗自己再去读一遍状态。 */
private data class ProfileEdit(val field: ProfileFieldKind, val value: String)

private enum class ProfileFieldKind { DESCRIPTION, TIMEZONE, LANGUAGE }

/** 一行「标签 + 当前值 + 可点」。值为空时显示灰色的「未设置」，而不是留空。 */
@Composable
private fun ProfileValueRow(label: String, value: String?, onClick: (() -> Unit)?) {
    val style = LauncherTheme.style
    FlueSettingsRow(
        modifier = Modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        minHeight = 0.dp,
        // 老师原话「把我的资料页面那些文字边框去掉」：这几行本来就靠底色和间距分隔，
        // 深色档下那圈近白的描边纯属噪音。
        border = null,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = style.titleColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                value ?: stringResource(R.string.watch_store_profile_not_set),
                color = style.secondaryTextColor,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (onClick != null) {
            Icon(
                MiuixIcons.Edit,
                contentDescription = null,
                tint = style.secondaryTextColor,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/** 简介 / 时区共用的输入弹窗。 */
@Composable
private fun ProfileTextDialog(
    title: String,
    initial: String,
    maxLength: Int,
    singleLine: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    placeholder: String? = null,
) {
    var draft by remember { mutableStateOf(initial) }
    FlueAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = LauncherTheme.style.titleColor) },
        text = {
            OutlinedTextField(
                value = draft,
                // 就地截断到上游上限：不截就只剩服务端 400 一条路。
                onValueChange = { draft = it.take(maxLength) },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 3,
                placeholder = placeholder?.let { hint ->
                    { Text(hint, color = LauncherTheme.style.secondaryTextColor, fontSize = 12.sp) }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft.trim()) }) {
                Text(
                    stringResource(R.string.watch_editor_confirm),
                    color = LauncherTheme.style.accentColor,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.watch_editor_cancel),
                    color = LauncherTheme.style.secondaryTextColor,
                )
            }
        },
    )
}

/**
 * 语言选择。
 *
 * 选项文字用**各语言自己的名字**（English / 日本語 / Русский），不做翻译：正在找自己母语的
 * 用户看得懂的只有那个词本身。顺序跟着 [WatchStoreProfileUpdate.ALLOWED_LANGUAGES]，
 * 上游加语言时这里自动跟着多一项。
 */
@Composable
private fun ProfileLanguageDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) = ProfileChoiceDialog(
    title = stringResource(R.string.watch_store_profile_language),
    current = current,
    options = WATCH_STORE_PROFILE_LANGUAGES,
    label = ::storeLanguageLabel,
    onDismiss = onDismiss,
    onConfirm = onConfirm,
)

@Composable
private fun ProfileChoiceDialog(
    title: String,
    current: String,
    options: List<String>,
    label: (String) -> String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val style = LauncherTheme.style
    FlueAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = style.titleColor) },
        text = {
            // **不套 verticalScroll**：FlueDialog 的卡片本身就是可滚动的 Column，
            // 再嵌一层滚动容器会让内层拿到无限高约束并直接抛
            // 「Vertically scrollable component was measured with an infinity maximum height」。
            Column {
                options.forEach { code ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(style.rowShape)
                            .clickable { onConfirm(code) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label(code),
                            color = if (code == current) style.accentColor else style.bodyColor,
                            fontSize = 14.sp,
                            fontWeight = if (code == current) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.watch_editor_cancel),
                    color = style.secondaryTextColor,
                )
            }
        },
    )
}

/** 改密码：当前密码 + 新密码。两栏都空着不给提交。 */
@Composable
private fun ProfilePasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (current: String, new: String) -> Unit,
) {
    val style = LauncherTheme.style
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    val tooShort = newPassword.length < HttpWatchStoreApi.MIN_PASSWORD_LENGTH
    val sameAsOld = newPassword.isNotEmpty() && newPassword == currentPassword
    val canSubmit = currentPassword.isNotEmpty() && !tooShort && !sameAsOld
    FlueAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.watch_store_profile_change_password),
                color = style.titleColor,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = {
                        currentPassword = it.take(HttpWatchStoreApi.MAX_PASSWORD_LENGTH)
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = {
                        Text(stringResource(R.string.watch_store_profile_current_password))
                    },
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it.take(HttpWatchStoreApi.MAX_PASSWORD_LENGTH) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(stringResource(R.string.watch_store_profile_new_password)) },
                )
                // 两条提示都是上游会拒的条件，先在本地说清楚，别让用户吃一个 400。
                if (newPassword.isNotEmpty() && tooShort) {
                    Text(
                        stringResource(
                            R.string.watch_store_profile_password_too_short_fmt,
                            HttpWatchStoreApi.MIN_PASSWORD_LENGTH,
                        ),
                        color = style.secondaryTextColor,
                        fontSize = 11.sp,
                    )
                }
                if (sameAsOld) {
                    Text(
                        stringResource(R.string.watch_store_profile_password_same),
                        color = style.secondaryTextColor,
                        fontSize = 11.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = { onConfirm(currentPassword, newPassword) },
            ) {
                Text(
                    stringResource(R.string.watch_editor_confirm),
                    color = if (canSubmit) style.accentColor else style.secondaryTextColor,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.watch_editor_cancel),
                    color = style.secondaryTextColor,
                )
            }
        },
    )
}

/** 语言代码 → 该语言自己的名字。认不出的代码原样显示（上游加了新语言也不会变成空白）。 */
internal fun storeLanguageLabel(code: String): String = when (code) {
    "en" -> "English"
    "zh-CN" -> "简体中文"
    "zh-TW" -> "繁體中文"
    "ja" -> "日本語"
    "ko" -> "한국어"
    "de" -> "Deutsch"
    "fr" -> "Français"
    "es" -> "Español"
    "pt" -> "Português"
    "ru" -> "Русский"
    else -> code
}

/** 时区是自由文本（上游不校验），给一个够 `Area/Location` 用的上限。 */
private val AVATAR_PICKER_MIME_TYPES = arrayOf("image/*")

/**
 * 内置选择器认的头像类型：与应用列表背景那份同样是「[android.graphics.BitmapRegionDecoder]
 * 能区域裁剪的类型」。gif 不在里面 —— 裁出来只会是第一帧。
 */
private val AVATAR_IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "avif",
)
