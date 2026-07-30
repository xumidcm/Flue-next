package com.flue.launcher

import com.flue.launcher.i18n.FlueComponentActivity

import android.app.Application
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flue.launcher.ui.home.BuiltInWatchFacePreview
import com.flue.launcher.ui.home.FIXED_PREVIEW_CLOCK
import com.flue.launcher.ui.common.flueElasticNestedScroll
import com.flue.launcher.ui.input.flueRotaryScrollable
import com.flue.launcher.ui.input.requestFocusAfterFirstFrame
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.util.RecentsVisibility
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.BUILT_IN_PHOTO_WATCHFACE_ID
import com.flue.launcher.watchface.BuiltInWatchFaceOptions
import com.flue.launcher.watchface.InternalWatchFaceStorage
import com.flue.launcher.watchface.WatchClockColorMode
import com.flue.launcher.watchface.WatchClockPosition
import com.flue.launcher.watchface.WatchFaceClockStyle
import com.flue.launcher.watchface.WatchFaceMd3eShape
import kotlinx.coroutines.launch
import java.io.File

const val EXTRA_INTERNAL_WATCHFACE_ID = "internal_watchface_id"

class InternalWatchFaceConfigActivity : FlueComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecentsVisibility.apply(this)
        val watchFaceId = intent.getStringExtra(EXTRA_INTERNAL_WATCHFACE_ID) ?: BUILT_IN_PHOTO_WATCHFACE_ID
        setContent {
            val viewModel: LauncherViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val uiStyle by viewModel.uiStyle.collectAsStateWithLifecycle()
            val globalUiScalePercent by viewModel.globalUiScalePercent.collectAsStateWithLifecycle()
            WatchLauncherTheme(themeMode = themeMode, uiStyle = uiStyle) {
                ProvideGlobalUiScale(globalUiScalePercent) {
                    InternalWatchFaceConfigScreen(
                        watchFaceId = watchFaceId,
                        onBack = { finish() }
                    )
                }
            }
        }
    }
}
@Composable
private fun InternalWatchFaceConfigScreen(
    watchFaceId: String,
    onBack: () -> Unit
) {
    val vm: LauncherViewModel = viewModel()
    val application = vm.getApplication<Application>()
    val launcherStyle = LauncherTheme.style
    val photoPath by vm.builtInPhotoPath.collectAsStateWithLifecycle()
    val videoPath by vm.builtInVideoPath.collectAsStateWithLifecycle()
    val photoClockPosition by vm.builtInPhotoClockPosition.collectAsStateWithLifecycle()
    val videoClockPosition by vm.builtInVideoClockPosition.collectAsStateWithLifecycle()
    val photoClockSize by vm.builtInPhotoClockSize.collectAsStateWithLifecycle()
    val videoClockSize by vm.builtInVideoClockSize.collectAsStateWithLifecycle()
    val photoClockBold by vm.builtInPhotoClockBold.collectAsStateWithLifecycle()
    val videoClockBold by vm.builtInVideoClockBold.collectAsStateWithLifecycle()
    val videoFillScreen by vm.builtInVideoFillScreen.collectAsStateWithLifecycle()
    val videoClockColorMode by vm.builtInVideoClockColorMode.collectAsStateWithLifecycle()
    val watchFaceFontPath by vm.builtInWatchFaceFontPath.collectAsStateWithLifecycle()
    val photoClockStyle by vm.builtInPhotoClockStyle.collectAsStateWithLifecycle()
    val videoClockStyle by vm.builtInVideoClockStyle.collectAsStateWithLifecycle()
    val photoMd3eShape by vm.builtInPhotoMd3eShape.collectAsStateWithLifecycle()
    val videoMd3eShape by vm.builtInVideoMd3eShape.collectAsStateWithLifecycle()
    val photoUseThemeTextColor by vm.builtInPhotoUseThemeTextColor.collectAsStateWithLifecycle()
    val videoUseThemeTextColor by vm.builtInVideoUseThemeTextColor.collectAsStateWithLifecycle()
    val photoTextColorArgb by vm.builtInPhotoTextColorArgb.collectAsStateWithLifecycle()
    val videoTextColorArgb by vm.builtInVideoTextColorArgb.collectAsStateWithLifecycle()
    val photoMd3eAutoColors by vm.builtInPhotoMd3eAutoColors.collectAsStateWithLifecycle()
    val videoMd3eAutoColors by vm.builtInVideoMd3eAutoColors.collectAsStateWithLifecycle()
    val photoMd3eTextColorArgb by vm.builtInPhotoMd3eTextColorArgb.collectAsStateWithLifecycle()
    val videoMd3eTextColorArgb by vm.builtInVideoMd3eTextColorArgb.collectAsStateWithLifecycle()
    val photoMd3eFaceColorArgb by vm.builtInPhotoMd3eFaceColorArgb.collectAsStateWithLifecycle()
    val videoMd3eFaceColorArgb by vm.builtInVideoMd3eFaceColorArgb.collectAsStateWithLifecycle()
    val photoMd3eHourColorArgb by vm.builtInPhotoMd3eHourColorArgb.collectAsStateWithLifecycle()
    val videoMd3eHourColorArgb by vm.builtInVideoMd3eHourColorArgb.collectAsStateWithLifecycle()
    val photoMd3eMinuteColorArgb by vm.builtInPhotoMd3eMinuteColorArgb.collectAsStateWithLifecycle()
    val videoMd3eMinuteColorArgb by vm.builtInVideoMd3eMinuteColorArgb.collectAsStateWithLifecycle()
    val photoMd3eSecondColorArgb by vm.builtInPhotoMd3eSecondColorArgb.collectAsStateWithLifecycle()
    val videoMd3eSecondColorArgb by vm.builtInVideoMd3eSecondColorArgb.collectAsStateWithLifecycle()
    val photoShowSeconds by vm.builtInPhotoShowSeconds.collectAsStateWithLifecycle()
    val videoShowSeconds by vm.builtInVideoShowSeconds.collectAsStateWithLifecycle()
    val photoCustomText by vm.builtInPhotoCustomText.collectAsStateWithLifecycle()
    val videoCustomText by vm.builtInVideoCustomText.collectAsStateWithLifecycle()
    val isPhoto = watchFaceId == BUILT_IN_PHOTO_WATCHFACE_ID
    val currentPath = if (isPhoto) photoPath else videoPath
    val activeClockPosition = if (isPhoto) photoClockPosition else videoClockPosition
    val activeClockSize = if (isPhoto) photoClockSize else videoClockSize
    val activeClockBold = if (isPhoto) photoClockBold else videoClockBold
    val activeClockStyle = if (isPhoto) photoClockStyle else videoClockStyle
    val activeMd3eShape = if (isPhoto) photoMd3eShape else videoMd3eShape
    val activeUseThemeTextColor = if (isPhoto) photoUseThemeTextColor else videoUseThemeTextColor
    val activeTextColorArgb = if (isPhoto) photoTextColorArgb else videoTextColorArgb
    val activeMd3eAutoColors = if (isPhoto) photoMd3eAutoColors else videoMd3eAutoColors
    val activeMd3eTextColorArgb = if (isPhoto) photoMd3eTextColorArgb else videoMd3eTextColorArgb
    val activeMd3eFaceColorArgb = if (isPhoto) photoMd3eFaceColorArgb else videoMd3eFaceColorArgb
    val activeMd3eHourColorArgb = if (isPhoto) photoMd3eHourColorArgb else videoMd3eHourColorArgb
    val activeMd3eMinuteColorArgb = if (isPhoto) photoMd3eMinuteColorArgb else videoMd3eMinuteColorArgb
    val activeMd3eSecondColorArgb = if (isPhoto) photoMd3eSecondColorArgb else videoMd3eSecondColorArgb
    val activeShowSeconds = if (isPhoto) photoShowSeconds else videoShowSeconds
    val activeCustomText = if (isPhoto) photoCustomText else videoCustomText
    var localPath by remember(watchFaceId) { mutableStateOf(currentPath) }
    var localClockPosition by remember(watchFaceId) { mutableStateOf(activeClockPosition) }
    var localClockSize by remember(watchFaceId) { mutableFloatStateOf(activeClockSize.toFloat()) }
    var localClockBold by remember(watchFaceId) { mutableStateOf(activeClockBold) }
    var localClockStyle by remember(watchFaceId) { mutableStateOf(activeClockStyle) }
    var localMd3eShape by remember(watchFaceId) { mutableStateOf(activeMd3eShape) }
    var localUseThemeTextColor by remember(watchFaceId) { mutableStateOf(activeUseThemeTextColor) }
    var localTextColorArgb by remember(watchFaceId) { mutableStateOf(activeTextColorArgb) }
    var localMd3eAutoColors by remember(watchFaceId) { mutableStateOf(activeMd3eAutoColors) }
    var localMd3eTextColorArgb by remember(watchFaceId) { mutableStateOf(activeMd3eTextColorArgb) }
    var localMd3eFaceColorArgb by remember(watchFaceId) { mutableStateOf(activeMd3eFaceColorArgb) }
    var localMd3eHourColorArgb by remember(watchFaceId) { mutableStateOf(activeMd3eHourColorArgb) }
    var localMd3eMinuteColorArgb by remember(watchFaceId) { mutableStateOf(activeMd3eMinuteColorArgb) }
    var localMd3eSecondColorArgb by remember(watchFaceId) { mutableStateOf(activeMd3eSecondColorArgb) }
    var localShowSeconds by remember(watchFaceId) { mutableStateOf(activeShowSeconds) }
    var localCustomText by remember(watchFaceId) { mutableStateOf(activeCustomText) }
    var localVideoFillScreen by remember(watchFaceId) { mutableStateOf(videoFillScreen) }
    var localVideoClockColorMode by remember(watchFaceId) { mutableStateOf(videoClockColorMode) }

    androidx.compose.runtime.LaunchedEffect(
        watchFaceId,
        currentPath,
        activeClockPosition,
        activeClockSize,
        activeClockBold,
        activeClockStyle,
        activeMd3eShape,
        activeUseThemeTextColor,
        activeTextColorArgb,
        activeMd3eAutoColors,
        activeMd3eTextColorArgb,
        activeMd3eFaceColorArgb,
        activeMd3eHourColorArgb,
        activeMd3eMinuteColorArgb,
        activeMd3eSecondColorArgb,
        activeShowSeconds,
        activeCustomText,
        videoFillScreen,
        videoClockColorMode
    ) {
        localPath = currentPath
        localClockPosition = activeClockPosition
        localClockSize = activeClockSize.toFloat()
        localClockBold = activeClockBold
        localClockStyle = activeClockStyle
        localMd3eShape = activeMd3eShape
        localUseThemeTextColor = activeUseThemeTextColor
        localTextColorArgb = activeTextColorArgb
        localMd3eAutoColors = activeMd3eAutoColors
        localMd3eTextColorArgb = activeMd3eTextColorArgb
        localMd3eFaceColorArgb = activeMd3eFaceColorArgb
        localMd3eHourColorArgb = activeMd3eHourColorArgb
        localMd3eMinuteColorArgb = activeMd3eMinuteColorArgb
        localMd3eSecondColorArgb = activeMd3eSecondColorArgb
        localShowSeconds = activeShowSeconds
        localCustomText = activeCustomText
        localVideoFillScreen = videoFillScreen
        localVideoClockColorMode = videoClockColorMode
    }

    fun persistPendingChanges() {
        if (isPhoto) {
            if (localPath != photoPath) vm.setBuiltInPhotoPath(localPath)
            if (localClockPosition != photoClockPosition) vm.setBuiltInPhotoClockPosition(localClockPosition)
            if (localClockSize.toInt() != photoClockSize) vm.setBuiltInPhotoClockSize(localClockSize.toInt())
            if (localClockBold != photoClockBold) vm.setBuiltInPhotoClockBold(localClockBold)
            if (localClockStyle != photoClockStyle) vm.setBuiltInPhotoClockStyle(localClockStyle)
            if (localMd3eShape != photoMd3eShape) vm.setBuiltInPhotoMd3eShape(localMd3eShape)
            if (localUseThemeTextColor != photoUseThemeTextColor) vm.setBuiltInPhotoUseThemeTextColor(localUseThemeTextColor)
            if (localTextColorArgb != photoTextColorArgb) vm.setBuiltInPhotoTextColorArgb(localTextColorArgb)
            if (localMd3eAutoColors != photoMd3eAutoColors) vm.setBuiltInPhotoMd3eAutoColors(localMd3eAutoColors)
            if (localMd3eTextColorArgb != photoMd3eTextColorArgb) vm.setBuiltInPhotoMd3eTextColorArgb(localMd3eTextColorArgb)
            if (localMd3eFaceColorArgb != photoMd3eFaceColorArgb) vm.setBuiltInPhotoMd3eFaceColorArgb(localMd3eFaceColorArgb)
            if (localMd3eHourColorArgb != photoMd3eHourColorArgb) vm.setBuiltInPhotoMd3eHourColorArgb(localMd3eHourColorArgb)
            if (localMd3eMinuteColorArgb != photoMd3eMinuteColorArgb) vm.setBuiltInPhotoMd3eMinuteColorArgb(localMd3eMinuteColorArgb)
            if (localMd3eSecondColorArgb != photoMd3eSecondColorArgb) vm.setBuiltInPhotoMd3eSecondColorArgb(localMd3eSecondColorArgb)
            if (localShowSeconds != photoShowSeconds) vm.setBuiltInPhotoShowSeconds(localShowSeconds)
            if (localCustomText != photoCustomText) vm.setBuiltInPhotoCustomText(localCustomText)
        } else {
            if (localPath != videoPath) vm.setBuiltInVideoPath(localPath)
            if (localClockPosition != videoClockPosition) vm.setBuiltInVideoClockPosition(localClockPosition)
            if (localClockSize.toInt() != videoClockSize) vm.setBuiltInVideoClockSize(localClockSize.toInt())
            if (localClockBold != videoClockBold) vm.setBuiltInVideoClockBold(localClockBold)
            if (localClockStyle != videoClockStyle) vm.setBuiltInVideoClockStyle(localClockStyle)
            if (localMd3eShape != videoMd3eShape) vm.setBuiltInVideoMd3eShape(localMd3eShape)
            if (localUseThemeTextColor != videoUseThemeTextColor) vm.setBuiltInVideoUseThemeTextColor(localUseThemeTextColor)
            if (localTextColorArgb != videoTextColorArgb) vm.setBuiltInVideoTextColorArgb(localTextColorArgb)
            if (localMd3eAutoColors != videoMd3eAutoColors) vm.setBuiltInVideoMd3eAutoColors(localMd3eAutoColors)
            if (localMd3eTextColorArgb != videoMd3eTextColorArgb) vm.setBuiltInVideoMd3eTextColorArgb(localMd3eTextColorArgb)
            if (localMd3eFaceColorArgb != videoMd3eFaceColorArgb) vm.setBuiltInVideoMd3eFaceColorArgb(localMd3eFaceColorArgb)
            if (localMd3eHourColorArgb != videoMd3eHourColorArgb) vm.setBuiltInVideoMd3eHourColorArgb(localMd3eHourColorArgb)
            if (localMd3eMinuteColorArgb != videoMd3eMinuteColorArgb) vm.setBuiltInVideoMd3eMinuteColorArgb(localMd3eMinuteColorArgb)
            if (localMd3eSecondColorArgb != videoMd3eSecondColorArgb) vm.setBuiltInVideoMd3eSecondColorArgb(localMd3eSecondColorArgb)
            if (localShowSeconds != videoShowSeconds) vm.setBuiltInVideoShowSeconds(localShowSeconds)
            if (localCustomText != videoCustomText) vm.setBuiltInVideoCustomText(localCustomText)
            if (localVideoFillScreen != videoFillScreen) vm.setBuiltInVideoFillScreen(localVideoFillScreen)
            if (localVideoClockColorMode != videoClockColorMode) vm.setBuiltInVideoClockColorMode(localVideoClockColorMode)
        }
    }

    val picker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            val savedPath = handlePickedMedia(
                application = application,
                watchFaceId = watchFaceId,
                sourceUri = uri,
                onMessage = { message ->
                    Toast.makeText(application, message, Toast.LENGTH_SHORT).show()
                }
            )
            if (!savedPath.isNullOrBlank()) {
                localPath = savedPath
                if (isPhoto) {
                    vm.setBuiltInPhotoPath(savedPath)
                } else {
                    vm.setBuiltInVideoPath(savedPath)
                }
            }
        }
    }
    val fontPicker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            val savedPath = InternalWatchFaceStorage.copyFont(application, uri)
            if (savedPath.isNullOrBlank()) {
                Toast.makeText(
                    application,
                    application.getString(R.string.internal_watchface_font_save_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                vm.setBuiltInWatchFaceFontPath(savedPath)
                Toast.makeText(
                    application,
                    application.getString(R.string.internal_watchface_font_updated),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val fileManagerPicker = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@rememberLauncherForActivityResult
        val uriText = result.data?.getStringExtra(EXTRA_FILE_MANAGER_RESULT_URI).orEmpty()
        val uri = uriText.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return@rememberLauncherForActivityResult
        val savedPath = handlePickedMedia(
            application = application,
            watchFaceId = watchFaceId,
            sourceUri = uri,
            onMessage = { message ->
                Toast.makeText(application, message, Toast.LENGTH_SHORT).show()
            }
        )
        if (!savedPath.isNullOrBlank()) {
            localPath = savedPath
            if (isPhoto) vm.setBuiltInPhotoPath(savedPath) else vm.setBuiltInVideoPath(savedPath)
        }
    }

    BackHandler {
        persistPendingChanges()
        onBack()
    }

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocusAfterFirstFrame()
    }

    Column(
            modifier = Modifier
                .fillMaxSize()
                .background(launcherStyle.screenBackground)
                .flueRotaryScrollable(focusRequester, 0.9f) { rotaryDelta ->
                    scope.launch { scrollState.scrollBy(-rotaryDelta) }
                }
                .flueElasticNestedScroll(scrollState)
                .verticalScroll(scrollState)
                .padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Text(
            text = stringResource(
                if (isPhoto) R.string.internal_watchface_photo_title
                else R.string.internal_watchface_video_title,
            ),
            color = launcherStyle.titleColor,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(
                if (isPhoto) R.string.internal_watchface_photo_subtitle
                else R.string.internal_watchface_video_subtitle,
            ),
            color = WatchColors.TextTertiary,
            fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(22.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(30.dp))
                .background(Color(0xFF10141D)),
            contentAlignment = Alignment.Center
        ) {
            BuiltInWatchFacePreview(
                watchFaceId = watchFaceId,
                photoPath = if (isPhoto) localPath else photoPath,
                videoPath = if (isPhoto) videoPath else localPath,
                photoOptions = BuiltInWatchFaceOptions(
                    clockPosition = if (isPhoto) localClockPosition else photoClockPosition,
                    clockSizeSp = localClockSize.toInt(),
                    boldClock = if (isPhoto) localClockBold else photoClockBold,
                    clockStyle = if (isPhoto) localClockStyle else photoClockStyle,
                    showSeconds = if (isPhoto) localShowSeconds else photoShowSeconds,
                    customText = if (isPhoto) localCustomText else photoCustomText,
                    fontPath = watchFaceFontPath,
                    md3eShape = if (isPhoto) localMd3eShape else photoMd3eShape,
                    useThemeTextColor = if (isPhoto) localUseThemeTextColor else photoUseThemeTextColor,
                    textColorArgb = if (isPhoto) localTextColorArgb else photoTextColorArgb,
                    md3eAutoColors = if (isPhoto) localMd3eAutoColors else photoMd3eAutoColors,
                    md3eTextColorArgb = if (isPhoto) localMd3eTextColorArgb else photoMd3eTextColorArgb,
                    md3eFaceColorArgb = if (isPhoto) localMd3eFaceColorArgb else photoMd3eFaceColorArgb,
                    md3eHourHandColorArgb = if (isPhoto) localMd3eHourColorArgb else photoMd3eHourColorArgb,
                    md3eMinuteHandColorArgb = if (isPhoto) localMd3eMinuteColorArgb else photoMd3eMinuteColorArgb,
                    md3eSecondHandColorArgb = if (isPhoto) localMd3eSecondColorArgb else photoMd3eSecondColorArgb
                ),
                videoOptions = BuiltInWatchFaceOptions(
                    clockPosition = if (isPhoto) videoClockPosition else localClockPosition,
                    clockSizeSp = localClockSize.toInt(),
                    boldClock = if (isPhoto) videoClockBold else localClockBold,
                    cropToFill = if (isPhoto) videoFillScreen else localVideoFillScreen,
                    clockColorMode = if (isPhoto) videoClockColorMode else localVideoClockColorMode,
                    clockStyle = if (isPhoto) videoClockStyle else localClockStyle,
                    showSeconds = if (isPhoto) videoShowSeconds else localShowSeconds,
                    customText = if (isPhoto) videoCustomText else localCustomText,
                    fontPath = watchFaceFontPath,
                    md3eShape = if (isPhoto) videoMd3eShape else localMd3eShape,
                    useThemeTextColor = if (isPhoto) videoUseThemeTextColor else localUseThemeTextColor,
                    textColorArgb = if (isPhoto) videoTextColorArgb else localTextColorArgb,
                    md3eAutoColors = if (isPhoto) videoMd3eAutoColors else localMd3eAutoColors,
                    md3eTextColorArgb = if (isPhoto) videoMd3eTextColorArgb else localMd3eTextColorArgb,
                    md3eFaceColorArgb = if (isPhoto) videoMd3eFaceColorArgb else localMd3eFaceColorArgb,
                    md3eHourHandColorArgb = if (isPhoto) videoMd3eHourColorArgb else localMd3eHourColorArgb,
                    md3eMinuteHandColorArgb = if (isPhoto) videoMd3eMinuteColorArgb else localMd3eMinuteColorArgb,
                    md3eSecondHandColorArgb = if (isPhoto) videoMd3eSecondColorArgb else localMd3eSecondColorArgb
                ),
                clockOverride = FIXED_PREVIEW_CLOCK,
                showClock = true,
                playVideo = true,
                showBottomFade = false,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        Text(
            text = stringResource(R.string.internal_watchface_style),
            color = launcherStyle.titleColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        ClockStylePickerRow(
            current = localClockStyle,
            onSelect = {
                localClockStyle = it
                if (isPhoto) vm.setBuiltInPhotoClockStyle(it) else vm.setBuiltInVideoClockStyle(it)
            }
        )

        if (localClockStyle == WatchFaceClockStyle.MD3E_CLOCK) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.internal_watchface_md3e_shape),
                color = launcherStyle.titleColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Md3eShapePickerRow(
                current = localMd3eShape,
                onSelect = {
                    localMd3eShape = it
                    if (isPhoto) vm.setBuiltInPhotoMd3eShape(it) else vm.setBuiltInVideoMd3eShape(it)
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        if (localClockStyle == WatchFaceClockStyle.MD3E_CLOCK) {
            ToggleRow(
                label = stringResource(R.string.internal_watchface_auto_colors),
                enabled = localMd3eAutoColors,
                onToggle = {
                    localMd3eAutoColors = !localMd3eAutoColors
                    if (isPhoto) {
                        vm.setBuiltInPhotoMd3eAutoColors(localMd3eAutoColors)
                    } else {
                        vm.setBuiltInVideoMd3eAutoColors(localMd3eAutoColors)
                    }
                }
            )
            if (!localMd3eAutoColors) {
                Spacer(modifier = Modifier.height(10.dp))
                ColorRgbSliderGroup(
                    title = stringResource(R.string.internal_watchface_text_color),
                    argb = localMd3eTextColorArgb,
                    onColorChange = {
                        localMd3eTextColorArgb = it
                        if (isPhoto) vm.setBuiltInPhotoMd3eTextColorArgb(it) else vm.setBuiltInVideoMd3eTextColorArgb(it)
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                ColorRgbSliderGroup(
                    title = stringResource(R.string.internal_watchface_face_color),
                    argb = localMd3eFaceColorArgb,
                    onColorChange = {
                        localMd3eFaceColorArgb = it
                        if (isPhoto) vm.setBuiltInPhotoMd3eFaceColorArgb(it) else vm.setBuiltInVideoMd3eFaceColorArgb(it)
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                ColorRgbSliderGroup(
                    title = stringResource(R.string.internal_watchface_hour_hand_color),
                    argb = localMd3eHourColorArgb,
                    onColorChange = {
                        localMd3eHourColorArgb = it
                        if (isPhoto) vm.setBuiltInPhotoMd3eHourColorArgb(it) else vm.setBuiltInVideoMd3eHourColorArgb(it)
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                ColorRgbSliderGroup(
                    title = stringResource(R.string.internal_watchface_minute_hand_color),
                    argb = localMd3eMinuteColorArgb,
                    onColorChange = {
                        localMd3eMinuteColorArgb = it
                        if (isPhoto) vm.setBuiltInPhotoMd3eMinuteColorArgb(it) else vm.setBuiltInVideoMd3eMinuteColorArgb(it)
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                ColorRgbSliderGroup(
                    title = stringResource(R.string.internal_watchface_second_hand_color),
                    argb = localMd3eSecondColorArgb,
                    onColorChange = {
                        localMd3eSecondColorArgb = it
                        if (isPhoto) vm.setBuiltInPhotoMd3eSecondColorArgb(it) else vm.setBuiltInVideoMd3eSecondColorArgb(it)
                    }
                )
            }
        } else {
            ToggleRow(
                label = stringResource(R.string.internal_watchface_use_theme_color),
                enabled = localUseThemeTextColor,
                onToggle = {
                    localUseThemeTextColor = !localUseThemeTextColor
                    if (isPhoto) {
                        vm.setBuiltInPhotoUseThemeTextColor(localUseThemeTextColor)
                    } else {
                        vm.setBuiltInVideoUseThemeTextColor(localUseThemeTextColor)
                    }
                }
            )
            if (!localUseThemeTextColor) {
                Spacer(modifier = Modifier.height(10.dp))
                ColorRgbSliderGroup(
                    title = stringResource(R.string.internal_watchface_text_color),
                    argb = localTextColorArgb,
                    onColorChange = {
                        localTextColorArgb = it
                        if (isPhoto) vm.setBuiltInPhotoTextColorArgb(it) else vm.setBuiltInVideoTextColorArgb(it)
                    }
                )
            }
        }

        if (localClockStyle == WatchFaceClockStyle.DIGITAL) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
            text = stringResource(R.string.internal_watchface_clock_position),
            color = launcherStyle.titleColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        PositionPickerRow(
            current = localClockPosition,
            onSelect = {
                localClockPosition = it
                if (isPhoto) {
                    vm.setBuiltInPhotoClockPosition(it)
                } else {
                    vm.setBuiltInVideoClockPosition(it)
                }
            }
        )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
            text = stringResource(
                R.string.internal_watchface_clock_size_fmt,
                localClockSize.toInt(),
            ),
            color = launcherStyle.titleColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth()
        )
            Slider(
            value = localClockSize,
            onValueChange = {
                localClockSize = it
            },
            onValueChangeFinished = {
                val value = localClockSize.toInt()
                if (isPhoto) vm.setBuiltInPhotoClockSize(value) else vm.setBuiltInVideoClockSize(value)
            },
            valueRange = 28f..92f,
            steps = 15,
            colors = SliderDefaults.colors(
                thumbColor = WatchColors.ActiveCyan,
                activeTrackColor = WatchColors.ActiveCyan
            )
        )

            Spacer(modifier = Modifier.height(8.dp))
            ToggleRow(
            label = stringResource(R.string.internal_watchface_bold_clock),
            enabled = localClockBold,
            onToggle = {
                localClockBold = !localClockBold
                if (isPhoto) {
                    vm.setBuiltInPhotoClockBold(localClockBold)
                } else {
                    vm.setBuiltInVideoClockBold(localClockBold)
                }
            }
        )
        }

        Spacer(modifier = Modifier.height(8.dp))
        ToggleRow(
            label = stringResource(R.string.internal_watchface_show_seconds),
            enabled = localShowSeconds,
            onToggle = {
                localShowSeconds = !localShowSeconds
                if (isPhoto) {
                    vm.setBuiltInPhotoShowSeconds(localShowSeconds)
                } else {
                    vm.setBuiltInVideoShowSeconds(localShowSeconds)
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))
        CustomTextField(
            value = localCustomText,
            onValueChange = { text ->
                localCustomText = text.take(32)
                if (isPhoto) {
                    vm.setBuiltInPhotoCustomText(localCustomText)
                } else {
                    vm.setBuiltInVideoCustomText(localCustomText)
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))
        val fontActionText = watchFaceFontPath?.let { path ->
            stringResource(R.string.internal_watchface_change_font_fmt, File(path).name)
        } ?: stringResource(R.string.internal_watchface_select_font)
        ActionButton(text = fontActionText) {
            fontPicker.launch(
                arrayOf(
                    "font/*",
                    "application/x-font-ttf",
                    "application/x-font-otf",
                    "application/vnd.ms-opentype",
                    "application/octet-stream"
                )
            )
        }

        if (!watchFaceFontPath.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            ActionButton(text = stringResource(R.string.internal_watchface_reset_font)) {
                vm.setBuiltInWatchFaceFontPath(null)
            }
        }

        if (!isPhoto) {
            Spacer(modifier = Modifier.height(8.dp))
            ToggleRow(
                label = stringResource(R.string.internal_watchface_video_fill_screen),
                enabled = localVideoFillScreen,
                onToggle = {
                    localVideoFillScreen = !localVideoFillScreen
                    vm.setBuiltInVideoFillScreen(localVideoFillScreen)
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.internal_watchface_clock_color),
                color = launcherStyle.titleColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            ClockColorPickerRow(
                current = localVideoClockColorMode,
                onSelect = {
                    localVideoClockColorMode = it
                    vm.setBuiltInVideoClockColorMode(it)
                }
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        ActionButton(
            text = if (localPath.isNullOrBlank()) {
                stringResource(
                    if (isPhoto) R.string.internal_watchface_select_image
                    else R.string.internal_watchface_select_video,
                )
            } else {
                stringResource(
                    if (isPhoto) R.string.internal_watchface_change_image
                    else R.string.internal_watchface_change_video,
                )
            },
            onLongClick = {
                val intent = Intent(vm.getApplication(), BuiltInFileManagerActivity::class.java).apply {
                    putExtra(
                        EXTRA_FILE_MANAGER_MODE,
                        if (isPhoto) FILE_MANAGER_MODE_IMAGE else FILE_MANAGER_MODE_VIDEO
                    )
                }
                fileManagerPicker.launch(intent)
            }
        ) {
            picker.launch(arrayOf(if (isPhoto) "image/*" else "video/*"))
        }

        Spacer(modifier = Modifier.height(10.dp))

        ActionButton(
            text = stringResource(
                if (isPhoto) R.string.internal_watchface_clear_image
                else R.string.internal_watchface_clear_video,
            ),
            enabled = !localPath.isNullOrBlank()
        ) {
            localPath = null
            if (isPhoto) {
                vm.setBuiltInPhotoPath(null)
            } else {
                vm.setBuiltInVideoPath(null)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        ActionButton(text = stringResource(R.string.common_back)) {
            persistPendingChanges()
            onBack()
        }
    }
}
@Composable
private fun ActionButton(
    text: String,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.958f else 1f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 170),
        label = "internal_action_press_scale"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) WatchColors.SurfaceGlass else WatchColors.InactiveGray)
            .pointerInput(enabled, onLongClick) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onLongPress = { onLongClick?.invoke() },
                    onTap = { onClick() }
                )
            }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) WatchColors.ActiveCyan else WatchColors.TextTertiary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun handlePickedMedia(
    application: Application,
    watchFaceId: String,
    sourceUri: Uri,
    onMessage: (String) -> Unit
): String? {
    val context = application
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            sourceUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
    val savedPath = if (watchFaceId == BUILT_IN_PHOTO_WATCHFACE_ID) {
        InternalWatchFaceStorage.copyPhoto(context, sourceUri)
    } else {
        InternalWatchFaceStorage.copyVideo(context, sourceUri)
    }

    if (savedPath.isNullOrBlank()) {
        onMessage(context.getString(R.string.internal_watchface_media_save_failed))
        return null
    }
    onMessage(context.getString(R.string.internal_watchface_media_updated))
    return savedPath
}
@Composable
private fun ClockStylePickerRow(
    current: WatchFaceClockStyle,
    onSelect: (WatchFaceClockStyle) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            SmallChoiceChip(
                label = stringResource(R.string.internal_watchface_style_digital),
                selected = current == WatchFaceClockStyle.DIGITAL,
                modifier = Modifier.weight(1f)
            ) { onSelect(WatchFaceClockStyle.DIGITAL) }
            SmallChoiceChip(
                label = stringResource(R.string.internal_watchface_style_apple),
                selected = current == WatchFaceClockStyle.APPLE_WATCH,
                modifier = Modifier.weight(1f)
            ) { onSelect(WatchFaceClockStyle.APPLE_WATCH) }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            SmallChoiceChip(
                label = stringResource(R.string.internal_watchface_style_md3),
                selected = current == WatchFaceClockStyle.MD3_ANALOG,
                modifier = Modifier.weight(1f)
            ) { onSelect(WatchFaceClockStyle.MD3_ANALOG) }
            SmallChoiceChip(
                label = stringResource(R.string.internal_watchface_style_md3e),
                selected = current == WatchFaceClockStyle.MD3E_CLOCK,
                modifier = Modifier.weight(1f)
            ) { onSelect(WatchFaceClockStyle.MD3E_CLOCK) }
        }
    }
}

@Composable
private fun Md3eShapePickerRow(
    current: WatchFaceMd3eShape,
    onSelect: (WatchFaceMd3eShape) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        SmallChoiceChip(
            label = stringResource(R.string.internal_watchface_shape_cookie),
            selected = current == WatchFaceMd3eShape.COOKIE,
            modifier = Modifier.weight(1f)
        ) { onSelect(WatchFaceMd3eShape.COOKIE) }
        SmallChoiceChip(
            label = stringResource(R.string.internal_watchface_shape_soft_star),
            selected = current == WatchFaceMd3eShape.SOFT_STAR,
            modifier = Modifier.weight(1f)
        ) { onSelect(WatchFaceMd3eShape.SOFT_STAR) }
        SmallChoiceChip(
            label = stringResource(R.string.internal_watchface_shape_circle),
            selected = current == WatchFaceMd3eShape.CIRCLE,
            modifier = Modifier.weight(1f)
        ) { onSelect(WatchFaceMd3eShape.CIRCLE) }
    }
}

@Composable
private fun CustomTextField(
    value: String,
    onValueChange: (String) -> Unit
) {
    val launcherStyle = LauncherTheme.style
    Text(
        text = stringResource(R.string.internal_watchface_custom_text),
        color = launcherStyle.titleColor,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(8.dp))
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = {
            Text(
                stringResource(R.string.internal_watchface_custom_text_hint),
                color = WatchColors.TextTertiary,
            )
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = WatchColors.SurfaceGlass,
            unfocusedContainerColor = WatchColors.SurfaceGlass,
            disabledContainerColor = WatchColors.SurfaceGlass,
            focusedTextColor = launcherStyle.bodyColor,
            unfocusedTextColor = launcherStyle.bodyColor,
            focusedIndicatorColor = WatchColors.ActiveCyan,
            unfocusedIndicatorColor = launcherStyle.outlineColor,
            cursorColor = WatchColors.ActiveCyan
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
    )
}

@Composable
private fun ColorRgbSliderGroup(
    title: String,
    argb: Int,
    onColorChange: (Int) -> Unit
) {
    val launcherStyle = LauncherTheme.style
    val red = android.graphics.Color.red(argb)
    val green = android.graphics.Color.green(argb)
    val blue = android.graphics.Color.blue(argb)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(WatchColors.SurfaceGlass)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = launcherStyle.titleColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color(argb))
            )
        }
        ColorChannelSlider("\u0052", red) { onColorChange(rgbArgb(it, green, blue)) }
        ColorChannelSlider("\u0047", green) { onColorChange(rgbArgb(red, it, blue)) }
        ColorChannelSlider("\u0042", blue) { onColorChange(rgbArgb(red, green, it)) }
    }
}

@Composable
private fun ColorChannelSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Text(
        text = "$label  $value",
        color = WatchColors.TextTertiary,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 8.dp)
    )
    Slider(
        value = value.toFloat(),
        onValueChange = { onValueChange(it.toInt().coerceIn(0, 255)) },
        valueRange = 0f..255f,
        steps = 254,
        colors = SliderDefaults.colors(
            thumbColor = WatchColors.ActiveCyan,
            activeTrackColor = WatchColors.ActiveCyan
        )
    )
}

private fun rgbArgb(red: Int, green: Int, blue: Int): Int {
    val r = red.coerceIn(0, 255)
    val g = green.coerceIn(0, 255)
    val b = blue.coerceIn(0, 255)
    return android.graphics.Color.argb(255, r, g, b)
}

@Composable
private fun PositionPickerRow(
    current: WatchClockPosition,
    onSelect: (WatchClockPosition) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            listOf(
                WatchClockPosition.TOP_LEFT to stringResource(R.string.internal_watchface_position_top_left),
                WatchClockPosition.TOP_CENTER to stringResource(R.string.internal_watchface_position_top_center),
                WatchClockPosition.TOP_RIGHT to stringResource(R.string.internal_watchface_position_top_right),
            ),
            listOf(
                WatchClockPosition.LEFT_CENTER to stringResource(R.string.internal_watchface_position_center_left),
                WatchClockPosition.CENTER to stringResource(R.string.internal_watchface_position_center),
                WatchClockPosition.RIGHT_CENTER to stringResource(R.string.internal_watchface_position_center_right),
            ),
            listOf(
                WatchClockPosition.BOTTOM_LEFT to stringResource(R.string.internal_watchface_position_bottom_left),
                WatchClockPosition.BOTTOM_CENTER to stringResource(R.string.internal_watchface_position_bottom_center),
                WatchClockPosition.BOTTOM_RIGHT to stringResource(R.string.internal_watchface_position_bottom_right),
            ),
        ).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                rowItems.forEach { item ->
                    val (position, label) = item
                    SmallChoiceChip(
                        label = label,
                        selected = position == current,
                        modifier = Modifier
                            .width(92.dp)
                            .padding(horizontal = 4.dp)
                    ) {
                        onSelect(position)
                    }
                }
            }
        }
    }
}

@Composable
private fun ClockColorPickerRow(
    current: WatchClockColorMode,
    onSelect: (WatchClockColorMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        SmallChoiceChip(
            label = stringResource(R.string.internal_watchface_color_white),
            selected = current == WatchClockColorMode.WHITE,
            modifier = Modifier.width(120.dp)
        ) { onSelect(WatchClockColorMode.WHITE) }
        SmallChoiceChip(
            label = stringResource(R.string.internal_watchface_color_black),
            selected = current == WatchClockColorMode.BLACK,
            modifier = Modifier.width(120.dp)
        ) { onSelect(WatchClockColorMode.BLACK) }
    }
}

@Composable
private fun SmallChoiceChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val launcherStyle = LauncherTheme.style
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.958f else 1f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 170),
        label = "clock_position_chip_press_scale"
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) WatchColors.ActiveCyan.copy(alpha = 0.22f) else WatchColors.SurfaceGlass)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) WatchColors.ActiveCyan else launcherStyle.bodyColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    val launcherStyle = LauncherTheme.style
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.958f else 1f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 170),
        label = "toggle_row_press_scale"
    )
    val knobOffset by animateDpAsState(
        targetValue = if (enabled) 23.dp else 3.dp,
        animationSpec = spring(stiffness = 760f, dampingRatio = 0.82f),
        label = "clock_toggle_knob_offset"
    )
    val trackColor by animateColorAsState(
        targetValue = if (enabled) WatchColors.ActiveGreen else WatchColors.InactiveGray,
        label = "clock_toggle_track_color"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(18.dp))
            .background(WatchColors.SurfaceGlass)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = launcherStyle.bodyColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(trackColor),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .padding(start = knobOffset)
                    .width(20.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White)
            )
        }
    }
}
