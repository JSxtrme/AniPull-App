package com.js.anipull

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ---- 1.9-dev5.0: in-app updater sheet (AnymeX-style) ------------------
// Half-height (or less) bottom sheet, home dimmed behind it
// (spotlight on the sheet), drag handle on top, header + buttons
// pinned, changelog card scrollable INSIDE the sheet. Flow:
// sheet on home entry -> Download & Install -> button flips to
// "Downloading..." (greyed) + progress card "Downloaded X MB /
// Y MB — N%" -> 100% "Installing..." -> Android's own
// "Do you want to update this app?" dialog -> Update -> app
// closes, reopen = updated. NO silent auto-update.
// "Later" snoozes that versionCode (auto-check won't re-show it
// this install); a plain dismiss (scrim/back) just hides it for
// this launch. Manual "Check for updates" in About ignores the
// snooze.

private enum class UpdatePhase { Idle, Downloading, Installing }

private fun fmtMB(bytes: Long): String =
    if (bytes < 0) "?" else String.format("%.1f MB", bytes / 1024.0 / 1024.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(info: UpdateInfo, currentVersionName: String, onLater: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(UpdatePhase.Idle) }
    var downloaded by remember { mutableStateOf(0L) }
    var total by remember { mutableStateOf(-1L) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.55f).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Header (pinned): download icon in a grey circle,
            // title, old -> new version pills.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (ThemeState.lightMode) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(if (ThemeState.lightMode) TextMain else Color.White),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("New Version Available", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = HeadingFont)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (ThemeState.lightMode) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.08f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text(currentVersionName, color = Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text("  →  ", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Box(modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Pink.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text(info.versionName, color = Pink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            // What's New card — scrollable INSIDE the sheet while
            // header and buttons stay pinned.
            Box(
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)).background(Bg).padding(14.dp),
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (info.features.isNotEmpty()) {
                        Text("What's new", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        info.features.forEach { Text("•  $it", color = Muted, fontSize = 13.5.sp, lineHeight = 19.sp) }
                    }
                    if (info.fixes.isNotEmpty()) {
                        if (info.features.isNotEmpty()) Spacer(Modifier.height(8.dp))
                        Text("Bug fixes", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        info.fixes.forEach { Text("•  $it", color = Muted, fontSize = 13.5.sp, lineHeight = 19.sp) }
                    }
                    if (info.features.isEmpty() && info.fixes.isEmpty()) {
                        Text("See the changelog on GitHub for what's in this build.", color = Muted, fontSize = 13.5.sp, lineHeight = 19.sp)
                    }
                }
            }
            // Progress card — appears above the buttons while
            // downloading / installing, buttons stay pinned below.
            if (phase == UpdatePhase.Downloading || phase == UpdatePhase.Installing) {
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (ThemeState.lightMode) Color.Black.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.06f))
                        .padding(14.dp),
                ) {
                    val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
                    Text(
                        if (phase == UpdatePhase.Installing) "Installing... 100%"
                        else "Downloaded ${fmtMB(downloaded)} / ${fmtMB(total)} — $pct%",
                        color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { if (phase == UpdatePhase.Installing) 1f else if (total > 0) downloaded.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
                        color = Pink,
                        trackColor = if (ThemeState.lightMode) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.12f),
                    )
                }
            }
            if (errorMsg != null) {
                Spacer(Modifier.height(10.dp))
                Text(errorMsg!!, color = Color(0xFFFF3B30), fontSize = 13.5.sp, lineHeight = 19.sp)
            }
            Spacer(Modifier.height(14.dp))
            // Buttons (pinned): Later (dark, left) + Download &
            // Install (pink, right). While downloading the pink
            // button greys out to "Downloading...".
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onLater,
                    modifier = Modifier.weight(1f).height(50.dp),
                    enabled = phase == UpdatePhase.Idle,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain),
                ) { Text("Later", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = {
                        phase = UpdatePhase.Downloading
                        errorMsg = null
                        downloaded = 0L
                        total = -1L
                        scope.launch {
                            val file = downloadUpdate(context, info) { d, t -> downloaded = d; total = t }
                            if (file == null) {
                                phase = UpdatePhase.Idle
                                errorMsg = "Download failed — check your connection and try again."
                                return@launch
                            }
                            phase = UpdatePhase.Installing
                            if (!installUpdate(context, file)) {
                                // One-time "install unknown apps" allow
                                // isn't on yet — send them to the toggle.
                                phase = UpdatePhase.Idle
                                errorMsg = "Allow Anipull to install apps first, then tap Download & Install again."
                                openUnknownSourcesSettings(context)
                            } else {
                                // Android takes over from here: system
                                // dialog -> Update -> app closes.
                                onDismiss()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                    enabled = phase == UpdatePhase.Idle,
                    colors = ButtonDefaults.buttonColors(containerColor = Pink, disabledContainerColor = Pink.copy(alpha = 0.4f)),
                ) {
                    Text(
                        if (phase == UpdatePhase.Downloading) "Downloading..." else "Download & Install",
                        color = OnAccent, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
