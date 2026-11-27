package com.example.vr

import android.content.Context
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import jcifs.smb.SmbFile

/**
 * 播放器根层级弹窗集合（开源许可 / 网页嗅探 / URL 播放 / 媒体信息 / 音视频轨选择 / 文件来源 / SMB 浏览）（从 VRPlayerScreen.kt 拆出）。
 */
@Composable
internal fun PlayerRootDialogsHost(
    context: Context,
    accentColor: Color,
    showLicensesDialog: Boolean,
    onDismissLicensesDialog: () -> Unit,
    showWebSniffer: Boolean,
    onCloseWebSniffer: () -> Unit,
    onPlayWebChoice: (url: String, pageUrl: String, cookie: String?, subtitleName: String?, subtitleCues: List<SubtitleCue>) -> Unit,
    showUrlPlayDialog: Boolean,
    onDismissUrlPlayDialog: () -> Unit,
    onPlayDirectUrl: (url: String, referer: String?) -> Unit,
    videoInfoDialogText: String?,
    onDismissVideoInfoDialog: () -> Unit,
    trackDialogOpen: Boolean,
    onDismissTrackDialog: () -> Unit,
    playerInstance: VrPlayerBackend?,
    selectedVideoTrack: Int,
    selectedAudioTrack: Int,
    selectedTextTrack: Int,
    onSelectMediaTrack: (Int, Int) -> Unit,
    pickerSourceDialogOpen: Boolean,
    onDismissPickerSourceDialog: () -> Unit,
    onPickGallery: () -> Unit,
    onPickAnyFile: () -> Unit,
    onOpenUrlPlay: () -> Unit,
    onOpenWebSniffer: () -> Unit,
    smbDialogOpen: Boolean,
    onDismissSmbDialog: () -> Unit,
    smbHost: String,
    onSmbHostChange: (String) -> Unit,
    smbUser: String,
    onSmbUserChange: (String) -> Unit,
    smbPass: String,
    onSmbPassChange: (String) -> Unit,
    onConnectSmb: () -> Unit,
    smbPath: String,
    onBrowseSmbParent: (String) -> Unit,
    smbError: String,
    smbEntries: List<SmbFile>,
    onBrowseSmbDir: (String) -> Unit,
    onPlaySmbFile: (SmbFile) -> Unit,
) {
    if (showLicensesDialog) {
        OpenSourceLicensesDialog(onDismiss = onDismissLicensesDialog)
    }

    if (showWebSniffer) {
        WebSnifferScreen(
            onPlay = { choice ->
                onPlayWebChoice(choice.url, choice.pageUrl, choice.cookie, choice.subtitleName, choice.subtitleCues)
            },
            onClose = onCloseWebSniffer,
            accentColor = accentColor,
            accentOnColor = Color.Black
        )
    }

    if (showUrlPlayDialog) {
        UrlPlayDialog(
            onDismiss = onDismissUrlPlayDialog,
            onPlay = { u, referer -> onPlayDirectUrl(u, referer) },
            accentColor = accentColor
        )
    }

    videoInfoDialogText?.let { info ->
        AlertDialog(
            onDismissRequest = onDismissVideoInfoDialog,
            containerColor = Color(0xFC18171C),
            title = { Text(stringResource(R.string.action_media_info), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = info,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissVideoInfoDialog) {
                    Text(stringResource(R.string.action_close), color = accentColor)
                }
            }
        )
    }

    if (trackDialogOpen) {
        val groups = playerInstance?.exo?.currentTracks?.groups ?: emptyList()
        TrackSelectionDialog(
            groups = groups,
            selectedVideoTrack = selectedVideoTrack,
            selectedAudioTrack = selectedAudioTrack,
            selectedTextTrack = selectedTextTrack,
            onSelectTrack = onSelectMediaTrack,
            onDismiss = onDismissTrackDialog,
            accentColor = accentColor
        )
    }

    if (pickerSourceDialogOpen) {
        PickerSourceDialog(
            onDismiss = onDismissPickerSourceDialog,
            onPickGallery = onPickGallery,
            onPickAnyFile = onPickAnyFile,
            onOpenUrlPlay = onOpenUrlPlay,
            onOpenWebSniffer = onOpenWebSniffer,
            accentColor = accentColor
        )
    }

    if (smbDialogOpen) {
        SmbBrowserDialog(
            onDismiss = onDismissSmbDialog,
            smbHost = smbHost,
            onSmbHostChange = onSmbHostChange,
            smbUser = smbUser,
            onSmbUserChange = onSmbUserChange,
            smbPass = smbPass,
            onSmbPassChange = onSmbPassChange,
            onConnect = onConnectSmb,
            smbPath = smbPath,
            onBrowseParent = onBrowseSmbParent,
            smbError = smbError,
            smbEntries = smbEntries,
            onSelectEntry = { entry ->
                if (entry.isDirectory) {
                    val base = smbPath.trimEnd('/')
                    val next = "$base/${entry.name}/"
                    onBrowseSmbDir(next)
                } else if (MediaFormats.isSupportedVideoExtension(
                        MediaFormats.extensionOfName(entry.name)
                    )
                ) {
                    onPlaySmbFile(entry)
                }
            },
            accentColor = accentColor
        )
    }
}
