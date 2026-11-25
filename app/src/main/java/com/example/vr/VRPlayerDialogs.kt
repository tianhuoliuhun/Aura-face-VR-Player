package com.example.vr

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import jcifs.smb.SmbFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

/**
 * VRPlayerScreen 的弹窗抽离集合（P4 技术债解耦）。
 *
 * 包含：
 * - [UrlPlayDialog]：URL 直链播放输入对话框
 * - [TrackSelectionDialog]：音轨 / 内嵌字幕轨切换对话框
 * - [PickerSourceDialog]：视频来源选择对话框（相册 / 任意文件 / 直链 / 网页嗅探）
 */

/**
 * URL 直接播放对话框（粘贴直链与可选 Referer 即可播放）。
 */
@Composable
fun UrlPlayDialog(
    onDismiss: () -> Unit,
    onPlay: (url: String, referer: String?) -> Unit,
    accentColor: Color = Color(0xFFD0BCFF)
) {
    val context = LocalContext.current
    var urlText by remember { mutableStateOf("") }
    // v2.9.0：直链防盗链 —— CDN 校验的 Referer 是**站点页面地址**，不是直链
    // 所在的 CDN 域名。留空则不发送 Referer（只带桌面 UA + Range）。
    var refererText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFC18171C),
        title = {
            Text(
                stringResource(R.string.url_play_title),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            stringResource(R.string.url_play_hint),
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.35f)
                        )
                    },
                    singleLine = false,
                    maxLines = 3,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 12.sp,
                        color = Color.White
                    )
                )
                // v2.9.0：可选的来源页（Referer）。只在直链被 CDN 拦截时才需要填。
                OutlinedTextField(
                    value = refererText,
                    onValueChange = { refererText = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = {
                        Text(
                            stringResource(R.string.url_play_referer_label),
                            fontSize = 10.sp
                        )
                    },
                    placeholder = {
                        Text(
                            stringResource(R.string.url_play_referer_hint),
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.35f)
                        )
                    },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 12.sp,
                        color = Color.White
                    )
                )
                Text(
                    text = stringResource(R.string.url_play_note),
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val u = urlText.trim()
                    if (u.startsWith("http://", true) || u.startsWith("https://", true)) {
                        onPlay(u, refererText.trim().takeIf { it.isNotBlank() })
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.url_play_invalid),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            ) {
                Text(stringResource(R.string.url_play_action), color = accentColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.action_close),
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    )
}

/**
 * 音轨 / 字幕轨选择对话框。
 */
@Composable
fun TrackSelectionDialog(
    groups: List<androidx.media3.common.Tracks.Group>,
    selectedAudioTrack: Int,
    selectedTextTrack: Int,
    onSelectTrack: (trackType: Int, index: Int) -> Unit,
    onDismiss: () -> Unit,
    accentColor: Color = Color(0xFFD0BCFF),
    selectedVideoTrack: Int = -1
) {
    val videoGroup = groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
    val hasMultiVideoQuality = videoGroup != null && videoGroup.mediaTrackGroup.length > 1
    val audioGroup = groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
    val textGroup = groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFC18171C),
        title = {
            Text(
                stringResource(R.string.track_dialog_title),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    // v2.6.10：**限高必须配滚动**。
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!hasMultiVideoQuality && audioGroup == null && textGroup == null) {
                    Text(
                        stringResource(R.string.track_none),
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp
                    )
                }

                if (hasMultiVideoQuality && videoGroup != null) {
                    Text(
                        stringResource(R.string.track_video_quality),
                        color = accentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedVideoTrack == -1) Color(0x33D0BCFF) else Color.White.copy(alpha = 0.05f))
                            .clickable { onSelectTrack(androidx.media3.common.C.TRACK_TYPE_VIDEO, -1) }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (selectedVideoTrack == -1) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (selectedVideoTrack == -1) accentColor else Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.track_video_auto),
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 10.sp
                        )
                    }
                    for (i in 0 until videoGroup.mediaTrackGroup.length) {
                        val f = videoGroup.mediaTrackGroup.getFormat(i)
                        val isSel = selectedVideoTrack == i
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) Color(0x33D0BCFF) else Color.White.copy(alpha = 0.05f))
                                .clickable { onSelectTrack(androidx.media3.common.C.TRACK_TYPE_VIDEO, i) }
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSel) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSel) accentColor else Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = LiveReconnectPolicy.formatVideoTrackLabel(f.width, f.height, f.bitrate, i),
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                audioGroup?.let { g ->
                    Text(
                        stringResource(R.string.track_audio),
                        color = accentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    for (i in 0 until g.mediaTrackGroup.length) {
                        val f = g.mediaTrackGroup.getFormat(i)
                        val isSel = selectedAudioTrack == i
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) Color(0x33D0BCFF) else Color.White.copy(alpha = 0.05f))
                                .clickable { onSelectTrack(androidx.media3.common.C.TRACK_TYPE_AUDIO, i) }
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSel) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSel) accentColor else Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.track_audio_item, i + 1) +
                                    (f.language?.let { " ($it)" } ?: ""),
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (textGroup != null) {
                    Text(
                        stringResource(R.string.track_subtitle_embedded),
                        color = accentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    // Disable subtitles option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedTextTrack == -1) Color(0x33D0BCFF) else Color.White.copy(alpha = 0.05f))
                            .clickable { onSelectTrack(androidx.media3.common.C.TRACK_TYPE_TEXT, -1) }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (selectedTextTrack == -1) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (selectedTextTrack == -1) accentColor else Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.track_subtitle_off),
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 10.sp
                        )
                    }
                    for (i in 0 until textGroup.mediaTrackGroup.length) {
                        val f = textGroup.mediaTrackGroup.getFormat(i)
                        val isSel = selectedTextTrack == i
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) Color(0x33D0BCFF) else Color.White.copy(alpha = 0.05f))
                                .clickable { onSelectTrack(androidx.media3.common.C.TRACK_TYPE_TEXT, i) }
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSel) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSel) accentColor else Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.track_subtitle_item, i + 1) +
                                    (f.language?.let { " ($it)" } ?: ""),
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.track_subtitle_note),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 9.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = accentColor)
            }
        }
    )
}

/**
 * 视频来源选择框（相册 / 任意文件 / URL 直链 / 网页嗅探）。
 */
@Composable
fun PickerSourceDialog(
    onDismiss: () -> Unit,
    onPickGallery: () -> Unit,
    onPickAnyFile: () -> Unit,
    onOpenUrlPlay: () -> Unit,
    onOpenWebSniffer: () -> Unit,
    accentColor: Color = Color(0xFFD0BCFF)
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFC18171C),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.picker_source_title),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── 入口 1：相册（有缩略图，日常主路径）──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.12f))
                        .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .clickable(onClick = onPickGallery)
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.picker_source_gallery),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.picker_source_gallery_desc),
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 10.sp
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = accentColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // ── 入口 2：任意文件（兜底，可覆盖 WMV / ISO / AVI）──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                        .clickable(onClick = onPickAnyFile)
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.picker_source_any_file),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.picker_source_any_file_desc),
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 10.sp
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // ── 入口 3：URL 直接播放（v2.6.4，粘贴 m3u8 / mp4）──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpenUrlPlay)
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.url_play_title),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.url_play_entry_desc),
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 10.sp
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // ── 入口 4：网页直链播放（v2.9.0，粘页面地址自动抓流）──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpenWebSniffer)
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.web_sniffer_title),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.web_sniffer_entry_desc),
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 10.sp
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // ── 支持的格式一览（让用户事先知道能放什么）──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        stringResource(R.string.picker_source_formats_title),
                        color = accentColor.copy(alpha = 0.9f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.picker_source_formats_list),
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 9.5.sp,
                        lineHeight = 14.sp
                    )
                }

                Text(
                    stringResource(R.string.picker_source_hint),
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 10.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = Color.White.copy(alpha = 0.7f))
            }
        }
    )
}

/**
 * 局域网 SMB 共享浏览与播放对话框。
 */
@Composable
fun SmbBrowserDialog(
    onDismiss: () -> Unit,
    smbHost: String,
    onSmbHostChange: (String) -> Unit,
    smbUser: String,
    onSmbUserChange: (String) -> Unit,
    smbPass: String,
    onSmbPassChange: (String) -> Unit,
    onConnect: () -> Unit,
    smbPath: String,
    onBrowseParent: (String) -> Unit,
    smbError: String,
    smbEntries: List<SmbFile>,
    onSelectEntry: (SmbFile) -> Unit,
    accentColor: Color = Color(0xFFD0BCFF)
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFC18171C),
        title = {
            Text(
                stringResource(R.string.smb_title),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    // v2.6.10：**限高必须配滚动**。
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = smbHost,
                    onValueChange = onSmbHostChange,
                    label = { Text(stringResource(R.string.smb_server_addr), fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = smbUser,
                        onValueChange = onSmbUserChange,
                        label = { Text(stringResource(R.string.smb_username), fontSize = 9.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = smbPass,
                        onValueChange = onSmbPassChange,
                        label = { Text(stringResource(R.string.smb_password), fontSize = 9.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    Button(
                        onClick = onConnect,
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(stringResource(R.string.smb_connect), fontSize = 11.sp)
                    }
                }

                if (smbPath.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = smbPath,
                            color = accentColor,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            val parent = smbPath.trimEnd('/')
                            val idx = parent.lastIndexOf('/')
                            if (idx > 6) {
                                onBrowseParent(parent.substring(0, idx + 1))
                            }
                        }) {
                            Text(stringResource(R.string.smb_parent), fontSize = 10.sp, color = accentColor)
                        }
                    }
                }

                if (smbError.isNotEmpty()) {
                    Text(smbError, color = Color(0xFFFF5252), fontSize = 9.sp)
                }

                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(smbEntries.size) { i ->
                        val entry = smbEntries[i]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .clickable { onSelectEntry(entry) }
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (entry.isDirectory) Color(0xFFFFD700) else accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = entry.name,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = accentColor)
            }
        }
    )
}
