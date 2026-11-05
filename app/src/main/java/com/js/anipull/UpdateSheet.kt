package com.js.anipull

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.rememberModalBottomSheetState
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
import java.io.File

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
// 1.9-dev5.2, sheet: the sheet MUST open fully extended —
// ModalBottomSheet's default skipPartiallyExpanded = false
// opens it at the ~50% partially-expanded anchor, which is
// shorter than this sheet's 0.55-height content, so the
// changelog card and the buttons were cut off at the bottom
// until the user dragged it up (JS screenshot, 5.1).
// skipPartiallyExpanded = true opens it at the expanded
// anchor, i.e. the full intended height, buttons visible.
// 1.9-dev5.2, permission resume: the APK bytes are downloaded
// AT MOST ONCE per update. If the "install unknown apps"
// allow is off, the downloaded file is KEPT (pendingInstallFile
// + the same file on disk, SHA-verified), the user is sent to
// the allow screen through an ActivityResultLauncher, and the
// moment they come back with the allow on, the Android install
// dialog fires straight away — no second tap, no re-download
// (AnymeX does exactly this: download, request permission,
// install the saved file). Same for the pre-download check:
// if the allow was off before downloading, coming back with
// it on auto-starts the download. A SHA mismatch is the only
// legitimate re-download (file deleted, error shown).

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
    // 1.9-dev5.2: the sheet opens fully extended (see header
    // comment) — the default partially-expanded state cut the
    // changelog card and the buttons off at the bottom.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 1.9-dev5.2: resume state. pendingInstallFile = an APK
    // already downloaded for THIS version, kept so the install
    // can fire the moment the user comes back from the allow
    // screen — never downloaded twice. awaitingPermissionForDownload
    // = the allow was off before any download started, so coming
    // back with it on auto-starts the download. Explicit
    // MutableState objects (not `by` delegates) so the launcher
    // callback below always reads the current value.
    val pendingInstallFile = remember { mutableStateOf<File?>(null) }
    val awaitingPermissionForDownload = remember { mutableStateOf(false) }
    // 1.9-dev5.2 Path B trigger (see the LaunchedEffect below the
    // startDownloadAndInstall function): the launcher callback
    // cannot call that function directly — it is declared after
    // the launcher, and Kotlin does not forward-reference a
    // local function from a lambda argument (build-verified).
    // So the callback only bumps this counter; the effect below
    // runs the download when it changes.
    var permissionReturnCount by remember { mutableStateOf(0) }

    // Runs when the user comes back from the "install unknown
    // apps" screen, whichever path sent them there. AnymeX's
    // model: request the permission, then install the file you
    // already downloaded — never download again.
    val permissionSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            // Still off — keep the downloaded file (if any) and
            // the error, and do NOT re-open settings in a loop.
            awaitingPermissionForDownload.value = false
            errorMsg = "Allow Anipull to install apps first — the download is saved, tap Download & Install again and it will install without downloading again."
            return@rememberLauncherForActivityResult
        }
        val pending = pendingInstallFile.value
        if (pending != null) {
            // Path A: APK already on disk — install it NOW.
            if (!pending.exists() || pending.length() <= 0 ||
                (info.sha256 != null && sha256Of(pending)?.equals(info.sha256, ignoreCase = true) != true)
            ) {
                pendingInstallFile.value = null
                errorMsg = "The downloaded file was changed or removed — tap Download & Install to download it again."
                return@rememberLauncherForActivityResult
            }
            phase = UpdatePhase.Installing
            errorMsg = null
            if (installUpdate(context, pending)) onDismiss()
            else {
                phase = UpdatePhase.Idle
                errorMsg = "Allow Anipull to install apps first — the download is saved, tap Download & Install again and it will install without downloading again."
            }
            return@rememberLauncherForActivityResult
        }
        if (awaitingPermissionForDownload.value) {
            // Path B: allow was off before downloading — the
            // download starts by itself now, no second tap
            // (via permissionReturnCount -> LaunchedEffect below,
            // because this callback cannot call the function
            // declared after it — see the state comment above).
            awaitingPermissionForDownload.value = false
            permissionReturnCount++
        }
    }

    // One entry point for Download & Install, used by the button
    // AND by the permission-return callback above.
    fun startDownloadAndInstall() {
        // 0. An APK already downloaded for THIS version? Install
        // it — downloading it again would waste data for nothing.
        // pendingInstallFile is this sheet's own download;
        // usableDownloadedUpdate is the same file found on disk
        // (sheet dismissed / app killed while in settings), but
        // only when its SHA-256 matches the JSON.
        val already: File? = pendingInstallFile.value?.takeIf { it.exists() && it.length() > 0 &&
            (info.sha256 == null || sha256Of(it)?.equals(info.sha256, ignoreCase = true) == true)
        } ?: usableDownloadedUpdate(context, info)
        if (already != null) {
            pendingInstallFile.value = already
            if (!context.packageManager.canRequestPackageInstalls()) {
                phase = UpdatePhase.Idle
                errorMsg = "Allow Anipull to install apps first — the download is already saved, it'll install when you're back."
                permissionSettingsLauncher.launch(unknownSourcesIntent(context))
                return
            }
            phase = UpdatePhase.Installing
            errorMsg = null
            if (installUpdate(context, already)) onDismiss()
            else {
                phase = UpdatePhase.Idle
                errorMsg = "Allow Anipull to install apps first — the download is already saved, it'll install when you're back."
                permissionSettingsLauncher.launch(unknownSourcesIntent(context))
            }
            return
        }
        // 1. 5.1 refinement, kept: check the install permission
        // BEFORE downloading, so a missing allow never wastes a
        // download. 5.2 addition: coming back with the allow on
        // auto-starts the download (launcher callback, Path B).
        if (!context.packageManager.canRequestPackageInstalls()) {
            awaitingPermissionForDownload.value = true
            errorMsg = "Allow Anipull to install apps first — the download will start automatically when you're back."
            permissionSettingsLauncher.launch(unknownSourcesIntent(context))
            return
        }
        // 2. Fresh download, then install.
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
            // Keep the file: if the allow is off, the user is
            // sent to settings and the install fires from THIS
            // file on return — no re-download (Path A).
            pendingInstallFile.value = file
            phase = UpdatePhase.Installing
            if (!installUpdate(context, file)) {
                phase = UpdatePhase.Idle
                errorMsg = "Allow Anipull to install apps first — the download is saved, it'll install when you're back."
                permissionSettingsLauncher.launch(unknownSourcesIntent(context))
            } else {
                // Android takes over from here: system dialog
                // -> Update -> app closes.
                onDismiss()
            }
        }
    }

    // 1.9-dev5.2 Path B, continued: when the launcher callback
    // above bumps permissionReturnCount, the user has come back
    // from the allow screen with the allow on and a download was
    // waiting to start — start it now, no second tap.
    androidx.compose.runtime.LaunchedEffect(permissionReturnCount) {
        if (permissionReturnCount == 0) return@LaunchedEffect
        startDownloadAndInstall()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
                    onClick = { startDownloadAndInstall() },
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
