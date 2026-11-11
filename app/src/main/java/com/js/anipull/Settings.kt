package com.js.anipull

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer

// 1.9-dev4.8.4 Settings redesign, Nuvio-style: the old single long
// page was messy, so Settings is now a hub with large headings and
// grouped cards, and each group opens its own sub-page with the
// same large heading (like Nuvio's "Appearance" page). Hub rows:
// Appearance, Layout | Downloads, Notifications | Storage, About.
// Every option here is real: download options are saved now and
// apply when the 2.0 engine lands (the page says so plainly),
// everything else applies immediately.
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var subPage by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = subPage != null) { subPage = null }
    // 1.9-dev4.8.4: sub-pages slide in from the right, hub slides
    // back in from the left — same attached-pages feel as onboarding.
    androidx.compose.animation.AnimatedContent(
        targetState = subPage,
        transitionSpec = {
            if (targetState == null)
                androidx.compose.animation.slideInHorizontally { -it } + androidx.compose.animation.fadeIn() togetherWith androidx.compose.animation.slideOutHorizontally { it } + androidx.compose.animation.fadeOut()
            else
                androidx.compose.animation.slideInHorizontally { it } + androidx.compose.animation.fadeIn() togetherWith androidx.compose.animation.slideOutHorizontally { -it } + androidx.compose.animation.fadeOut()
        },
        label = "settingsPage",
    ) { page ->
        if (page == null) {
            SettingsHub(onBack = onBack, onOpen = { subPage = it })
        } else {
            SettingsSubPage(title = page, onBack = { subPage = null }) {
                when (page) {
                    "Appearance" -> AppearancePage()
                    "Layout" -> LayoutPage()
                    "Downloads" -> DownloadsSettingsPage()
                    "Notifications" -> NotificationsPage()
                    "Storage" -> StoragePage()
                    "About" -> AboutPage()
                    "Crash Logs" -> CrashLogsPage()
                    // 2.0-dev1.9 TEMPORARY debug only: Download Logs
                    // — the rolling download-pipeline log. Remove
                    // this route (and DownloadLogs.kt) once downloads
                    // work; nothing else depends on it.
                    "Download Logs" -> DownloadLogsPage()
                }
            }
        }
    }
}

@Composable
fun SettingsHub(onBack: () -> Unit, onOpen: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp),
    ) {
        item {
            BackPill(onBack = onBack)
            Spacer(Modifier.height(16.dp))
            Text("Settings", color = TextMain, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, fontFamily = HeadingFont)
            Spacer(Modifier.height(20.dp))
        }
        item {
            SettingsCard {
                SettingsRow(R.drawable.ic_palette, "Appearance", "Themes, accent colour, and display", { onOpen("Appearance") })
                SettingsDivider()
                SettingsRow(R.drawable.ic_settings, "Layout", "Home, cards, details, and navigation", { onOpen("Layout") })
            }
            Spacer(Modifier.height(16.dp))
            SettingsCard {
                SettingsRow(R.drawable.ic_download, "Downloads", "Parallel, speed, folder, and quality", { onOpen("Downloads") })
                SettingsDivider()
                SettingsRow(R.drawable.ic_bell, "Notifications", "Download progress and completion pings", { onOpen("Notifications") })
            }
            Spacer(Modifier.height(16.dp))
            SettingsCard {
                SettingsRow(R.drawable.ic_folder, "Storage", "Home cache and download folder", { onOpen("Storage") })
                SettingsDivider()
                SettingsRow(R.drawable.ic_info, "About", "Version, GitHub, and acknowledgements", { onOpen("About") })
            }
            // 4.9 fix 9: DEBUGGING section, as in JS's screenshot —
            // Crash Logs row, styled like our own rows (same card,
            // same 40dp icon box, same chevron), red-tinted icon
            // box to match the screenshot's red bug icon.
            Spacer(Modifier.height(20.dp))
            SettingsSectionLabel("DEBUGGING")
            SettingsCard {
                Row(modifier = Modifier.fillMaxWidth().clickable(onClick = { onOpen("Crash Logs") }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFFF3B30).copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Image(painter = painterResource(R.drawable.ic_info), contentDescription = null, modifier = Modifier.size(20.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFFFF3B30)))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Crash Logs", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("View app crash reports & error logs", color = Muted, fontSize = 13.sp)
                    }
                    Text("›", color = Muted, fontSize = 22.sp)
                }
            }
            // 2.0-dev1.9 TEMPORARY debug only: Download Logs, under
            // Crash Logs in DEBUGGING. Same row style, Pink-tinted
            // icon box. Will be removed once downloads work.
            Spacer(Modifier.height(12.dp))
            SettingsCard {
                Row(modifier = Modifier.fillMaxWidth().clickable(onClick = { onOpen("Download Logs") }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Pink.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Image(painter = painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(20.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Pink))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Download Logs", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Temporary — why a download failed, step by step", color = Muted, fontSize = 13.sp)
                    }
                    Text("›", color = Muted, fontSize = 22.sp)
                }
            }
            Spacer(Modifier.height(28.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.foundation.Image(painter = painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("Anipull", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, fontFamily = HeadingFont)
                Text("Download-only anime. No streaming, no tracking.", color = Muted, fontSize = 13.sp)
                Text("2.0-dev2.4 · com.js.anipull", color = Faint, fontSize = 12.sp)
            }
            Spacer(Modifier.height(100.dp))
        }
    }
}

@Composable
fun SettingsSubPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("←", color = Pink, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onBack))
                Spacer(Modifier.width(12.dp))
                Text(title, color = TextMain, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, fontFamily = HeadingFont)
            }
            Spacer(Modifier.height(20.dp))
        }
        item { Column(content = content) }
        item { Spacer(Modifier.height(100.dp)) }
    }
}

@Composable
fun SettingsSectionLabel(text: String) {
    // 4.9 fix 8: old label was 11sp Faint grey — smaller and greyer
    // than the content inside its card, hardly readable. Now 13sp
    // Muted (lighter grey, not faint), ExtraBold, tighter tracking.
    // Still smaller than card titles (15-16sp) and far smaller
    // than the main page heading (36sp), as JS asked.
    Text(text, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
    Spacer(Modifier.height(10.dp))
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, Line, RoundedCornerShape(20.dp)).background(Card).padding(horizontal = 16.dp, vertical = 8.dp),
        content = content,
    )
}

@Composable
fun SettingsDivider() {
    Spacer(Modifier.height(4.dp))
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Line))
    Spacer(Modifier.height(4.dp))
}

@Composable
fun SettingsRow(icon: Int, title: String, sub: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (ThemeState.lightMode) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(if (ThemeState.lightMode) TextMain else Color.White))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Muted, fontSize = 13.sp)
        }
        Text("›", color = Muted, fontSize = 22.sp)
    }
}

@Composable
fun SettingsPrefs(): android.content.SharedPreferences {
    val ctx = LocalContext.current
    return remember { ctx.getSharedPreferences("anipull_settings", Context.MODE_PRIVATE) }
}

// ---- Appearance -------------------------------------------------------
val AccentOptions = listOf(
    "Anipull Pink" to "FF4D8D", "White" to "FFFFFF", "Crimson" to "FF3B30",
    "Ocean" to "0A84FF", "Violet" to "BF5AF2", "Emerald" to "30D158",
    "Amber" to "FF9F0A", "Rose" to "FF375F", "Teal" to "2DD4BF", "Lavender" to "A78BFA",
    "Sunset" to "FF6B35",
)
// White stays in the list now that OnAccent fixes white-on-white
// (black text/icons on a white pill/button). Sunset is the new
// fixed colour JS asked for; the last cell is a custom picker. 

@Composable
fun AppearancePage() {
    val prefs = SettingsPrefs()
    var accentHex by remember { mutableStateOf(prefs.getString("accent_hex", "FF4D8D") ?: "FF4D8D") }
    var amoled by remember { mutableStateOf(prefs.getBoolean("amoled", false)) }
    var sfHeadings by remember { mutableStateOf(prefs.getBoolean("sf_headings", true)) }
    var reduceMotion by remember { mutableStateOf(prefs.getBoolean("reduce_motion", false)) }
    var lightMode by remember { mutableStateOf(prefs.getBoolean("light_mode", false)) }
    var showPicker by remember { mutableStateOf(false) }
    fun save() {
        prefs.edit().putString("accent_hex", accentHex).putBoolean("amoled", amoled).putBoolean("sf_headings", sfHeadings).putBoolean("reduce_motion", reduceMotion).putBoolean("light_mode", lightMode).apply()
    }
    if (showPicker) {
        ColorPickerDialog(initialHex = accentHex, onDismiss = { showPicker = false }, onApply = { hex ->
            accentHex = hex
            try { AccentState.color = Color(("FF" + hex).toLong(16)) } catch (_: Exception) { }
            save(); showPicker = false
        })
    }
    SettingsSectionLabel("THEME")
    SettingsCard {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            val accentItems = AccentOptions + listOf("Custom" to "CUSTOM")
            accentItems.chunked(3).forEach { trio ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    trio.forEach { (name, hex) ->
                        val isCustom = hex == "CUSTOM"
                        val color = if (isCustom) {
                            try { Color(("FF" + accentHex).toLong(16)) } catch (_: Exception) { Pink }
                        } else {
                            try { Color(("FF" + hex).toLong(16)) } catch (_: Exception) { Pink }
                        }
                        val selected = if (isCustom) AccentOptions.none { it.second.equals(accentHex, ignoreCase = true) } else accentHex.equals(hex, ignoreCase = true)
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(999.dp))
                                    .border(if (selected) 2.dp else 0.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(999.dp))
                                    .background(if (isCustom) androidx.compose.ui.graphics.Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)) else androidx.compose.ui.graphics.Brush.linearGradient(listOf(color, color)))
                                    .clickable {
                                        if (isCustom) showPicker = true else { accentHex = hex; AccentState.color = color; save() }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isCustom) Text("+", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                else if (selected) Text("✓", color = if (color.luminance() > 0.6f) Color.Black else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(name, color = if (selected) TextMain else Muted, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    repeat(3 - trio.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    Text("Accent colours the buttons, pills, dock highlight and wordmark instantly — it is saved, so it survives restarts.", color = Faint, fontSize = 12.sp)
    Spacer(Modifier.height(20.dp))
    SettingsSectionLabel("DISPLAY")
    SettingsCard {
        SettingsToggle("Light mode", "Light surfaces, dark text. Default off. Turning it on turns AMOLED off, and vice versa.", lightMode) { lightMode = it; ThemeState.lightMode = it; if (it) { amoled = false; ThemeState.amoled = false }; save() }
        SettingsDivider()
        SettingsToggle("AMOLED Black", "Use pure black backgrounds for OLED screens.", amoled) { amoled = it; ThemeState.amoled = it; if (it) { lightMode = false; ThemeState.lightMode = false }; save() }
        SettingsDivider()
        SettingsToggle("SF headings", "Headings in SF Pro Display Semibold, body in Outfit. Off = Outfit everywhere.", sfHeadings) { sfHeadings = it; ThemeState.sfHeadings = it; save() }
        SettingsDivider()
        SettingsToggle("Reduce motion", "Turns off auto-scroll and poster press scale. The hero detail spring still runs, just gentler.", reduceMotion) { reduceMotion = it; ThemeState.reduceMotion = it; save() }
    }
}

// ---- Beautified dropdown (4.8.7) --------------------------------------
// The old dropdown was the raw Material3 menu: default surface,
// no selected state, no descriptions — that is why it looked bad.
// This one matches the app: Card field, Pink border when open,
// chevron that rotates, menu in Card with 16dp corners, selected
// row tinted Pink with a check, and a subtitle under each option
// so the choice actually explains itself.
@Composable
fun SettingsDropdown(
    selectedLabel: String,
    options: List<Triple<String, String, String>>,
    currentValue: String,
    onSelect: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(targetValue = if (menuOpen) 180f else 0f, animationSpec = tween(250), label = "dropdownChevron")
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, if (menuOpen) Pink else Color.White.copy(alpha = 0.09f), RoundedCornerShape(16.dp)).background(Card).clickable { menuOpen = true }.padding(horizontal = 16.dp, vertical = 13.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(selectedLabel, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("▾", color = Pink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.graphicsLayer { rotationZ = chevronRotation })
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = Card,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
        ) {
            options.forEach { (value, title, subtitle) ->
                val selected = value == currentValue
                DropdownMenuItem(
                    text = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(title, color = if (selected) TextMain else Muted, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                                if (selected) Text("✓", color = Pink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                            if (subtitle.isNotBlank()) Text(subtitle, color = Faint, fontSize = 12.sp)
                        }
                    },
                    onClick = { onSelect(value); menuOpen = false },
                    modifier = Modifier.fillMaxWidth().background(if (selected) Pink.copy(alpha = 0.12f) else Color.Transparent),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

// ---- Layout -----------------------------------------------------------
@Composable
fun LayoutPage() {
    val prefs = SettingsPrefs()
    var translucentDock by remember { mutableStateOf(prefs.getBoolean("translucent_dock", true)) }
    var dockMode by remember { mutableStateOf(prefs.getString("dock_mode", "adaptive") ?: "adaptive") }
    var carouselAuto by remember { mutableStateOf(prefs.getBoolean("carousel_auto", true)) }
    var particles by remember { mutableStateOf(prefs.getBoolean("particles", true)) }
    var showExplore by remember { mutableStateOf(prefs.getBoolean("show_explore", true)) }
    var exploreCols by remember { mutableStateOf(prefs.getInt("explore_columns", 3)) }
    // 1.9-dev5.0 QOL 3: haptics (long-press + dock tap, default on)
    // and Show Editor's Picks (show/hide the carousel itself —
    // auto-scroll below only controls motion, not visibility).
    var haptics by remember { mutableStateOf(prefs.getBoolean("haptics", true)) }
    var showEditorsPicks by remember { mutableStateOf(prefs.getBoolean("show_editor_picks", true)) }
    // 4.9 fix 3: old hero_motion easing options (Ease in/out,
    // Overshoot, Bouncy, Cinematic) no longer make sense — curve
    // is fixed easeInOutCubic since 4.8.16. Replaced with 3 speed
    // options, same pref file, new key transition_speed.
    var transitionSpeed by remember { mutableStateOf(prefs.getString("transition_speed", "normal") ?: "normal") }
    fun save() {
        prefs.edit().putBoolean("translucent_dock", translucentDock).putString("dock_mode", dockMode).putBoolean("carousel_auto", carouselAuto).putBoolean("particles", particles).putBoolean("show_explore", showExplore).putInt("explore_columns", exploreCols).putString("transition_speed", transitionSpeed).putBoolean("haptics", haptics).putBoolean("show_editor_picks", showEditorsPicks).apply()
    }
    SettingsSectionLabel("NAVIGATION")
    SettingsCard {
        SettingsToggle("Translucent dock", "Real backdrop blur on the dock (Haze), not full liquid glass. Off = solid dock.", translucentDock) { translucentDock = it; save() }
        SettingsDivider()
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text("Dock behaviour", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val dockLabel = when (dockMode) { "expanded" -> "Always expanded"; "compact" -> "Always compact"; else -> "Adaptive" }
            SettingsDropdown(
                selectedLabel = dockLabel,
                options = listOf(
                    Triple("expanded", "Always expanded", "Dock stays large"),
                    Triple("compact", "Always compact", "Dock stays small"),
                    Triple("adaptive", "Adaptive", "Morphs with scroll"),
                ),
                currentValue = dockMode,
                onSelect = { dockMode = it; save() },
            )
            Text("Adaptive = the current scroll morph. Expanded / compact lock the dock in that size.", color = Faint, fontSize = 12.sp)
        }
    }
    Spacer(Modifier.height(20.dp))
    SettingsSectionLabel("HOME")
    SettingsCard {
        SettingsToggle("Show Explore on Home", "The Explore grid after the catalogs. Off = footer shows straight after the genre rows.", showExplore) { showExplore = it; save() }
        SettingsDivider()
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text("Explore cards per row", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            SettingsDropdown(
                selectedLabel = "$exploreCols per row",
                options = listOf(
                    Triple("2", "2 per row", "Bigger cards"),
                    Triple("3", "3 per row", "Balanced"),
                    Triple("4", "4 per row", "More titles at a glance"),
                ),
                currentValue = exploreCols.toString(),
                onSelect = { exploreCols = it.toInt(); save() },
            )
            Text("Bigger cards at 2, more titles at a glance at 4. Skeletons match the chosen count, so rows never jump on load.", color = Faint, fontSize = 12.sp)
        }
        SettingsDivider()
        SettingsToggle("Auto-scroll Editor's picks", "One poster every 5 seconds. Pauses off-screen and while you drag.", carouselAuto) { carouselAuto = it; save() }
        SettingsDivider()
        SettingsToggle("Show Editor's picks", "The carousel itself on Home. Off = the picks row disappears entirely, not just stops scrolling.", showEditorsPicks) { showEditorsPicks = it; save() }
        SettingsDivider()
        SettingsToggle("Haptics", "A light buzz on long-press and dock taps. Off = silent taps everywhere.", haptics) { haptics = it; save() }
        SettingsDivider()
        SettingsToggle("Home particles", "Drifting, glowing, twinkling dots on the home page.", particles) { particles = it; save() }
        SettingsDivider()
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text("Poster transition speed", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val speedLabel = when (transitionSpeed) { "slow" -> "Slow"; "fast" -> "Fast"; else -> "Normal" }
            SettingsDropdown(
                selectedLabel = speedLabel,
                options = listOf(
                    Triple("slow", "Slow", "450ms — relaxed, cinematic"),
                    Triple("normal", "Normal", "300ms — current speed"),
                    Triple("fast", "Fast", "200ms — snappy"),
                ),
                currentValue = transitionSpeed,
                onSelect = { transitionSpeed = it; save() },
            )
            Text("How fast the poster flies home to details (and back). Curve stays easeInOutCubic — only speed changes, so poster and page always stay together.", color = Faint, fontSize = 12.sp)
        }
    }
}

// ---- Downloads --------------------------------------------------------
@Composable
fun DownloadsSettingsPage() {
    val ctx = LocalContext.current
    val prefs = SettingsPrefs()
    var parallel by remember { mutableStateOf(prefs.getInt("parallel", 2)) }
    var speedLimit by remember { mutableStateOf(prefs.getInt("speed_mbps", 0)) }
    var folder by remember { mutableStateOf(prefs.getString("folder", "Downloads/Anipull") ?: "Downloads/Anipull") }
    var quality by remember { mutableStateOf(prefs.getString("quality", "1080p") ?: "1080p") }
    var language by remember { mutableStateOf(prefs.getString("language", "Sub") ?: "Sub") }
    var wifiOnly by remember { mutableStateOf(prefs.getBoolean("wifi_only", true)) }
    var askSeason by remember { mutableStateOf(prefs.getBoolean("ask_season", true)) }
    var subs by remember { mutableStateOf(prefs.getBoolean("subs", true)) }
    var autoRetry by remember { mutableStateOf(prefs.getBoolean("auto_retry", true)) }
    var keepScreenOn by remember { mutableStateOf(prefs.getBoolean("keep_screen_on", false)) }
    // 2.0-dev2.8: "Prefer smaller files" — at the chosen quality,
    // the race picks the smallest file instead of the fastest
    // server. Default OFF (fastest first, current behaviour).
    var preferSmaller by remember { mutableStateOf(prefs.getBoolean("prefer_smaller_files", false)) }
    // 2.0-dev1.6: default download server — 0 = Auto (try servers in
    // priority order 1..9), 1..9 = always try that server first (the
    // rest still back it up if it fails). Same key the sheet reads.
    var preferredServer by remember { mutableStateOf(prefs.getInt("preferred_server", 0)) }
    // 2.0-dev1.7: Sources priority order — the auto-select /
    // failover order, rearrangeable with the up/down arrows below.
    // Default 1..9 (easiest first); persisted as "server_order".
    var serverOrder by remember { mutableStateOf(ServerRegistry.orderedNumbers(ctx)) }
    // 2.0-dev1.7: auto-test state — per-server health results from
    // the last test run (empty = never tested), plus a one-line
    // summary after a run. The test itself lives in ServerHealth.
    val settingsScope = rememberCoroutineScope()
    var testingServers by remember { mutableStateOf(false) }
    var serverHealth by remember { mutableStateOf<Map<Int, HealthResult>>(emptyMap()) }
    var testSummary by remember { mutableStateOf<String?>(null) }
    fun runServerTest() {
        if (testingServers) return
        settingsScope.launch {
            testingServers = true
            serverHealth = emptyMap()
            testSummary = null
            val results = ServerHealth.testAll(ctx, serverOrder)
            serverHealth = results
            val next = ServerHealth.reorderByHealth(serverOrder, results)
            serverOrder = next
            ServerRegistry.saveOrder(ctx, next)
            testingServers = false
            val ok = results.count { it.value.status == HealthStatus.OK }
            val unknown = results.count { it.value.status == HealthStatus.UNKNOWN }
            val failed = results.count { it.value.status == HealthStatus.FAILED }
            testSummary = "Auto-test done — $ok working, $unknown need a check, $failed failed. Fastest working servers first."
        }
    }
    fun moveServer(number: Int, delta: Int) {
        val from = serverOrder.indexOf(number)
        val to = from + delta
        if (from < 0 || to < 0 || to >= serverOrder.size) return
        val next = serverOrder.toMutableList().also { it.add(to, it.removeAt(from)) }
        serverOrder = next
        ServerRegistry.saveOrder(ctx, next)
    }
    fun save() {
        prefs.edit().putInt("parallel", parallel).putInt("speed_mbps", speedLimit).putString("folder", folder).putString("quality", quality).putString("language", language).putBoolean("wifi_only", wifiOnly).putBoolean("ask_season", askSeason).putBoolean("subs", subs).putBoolean("auto_retry", autoRetry).putBoolean("keep_screen_on", keepScreenOn).putBoolean("prefer_smaller_files", preferSmaller).putInt("preferred_server", preferredServer).apply()
    }
    val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) { }
            val raw = java.net.URLDecoder.decode(uri.toString(), "UTF-8")
            val display = raw.substringAfter(":", raw).removePrefix("/tree/").replace("primary:", "").trim()
            if (display.isNotBlank()) { folder = display; prefs.edit().putString("folder", display).putString("folder_uri", uri.toString()).apply() }
        }
    }
    Text("Saved on this phone. Downloads use the real engine — these settings apply to every pull.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
    Spacer(Modifier.height(20.dp))
    SettingsSectionLabel("QUEUE")
    SettingsCard {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text("Parallel downloads", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(if (parallel <= 1) "1 episode at a time" else "$parallel episodes at once", color = Pink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Slider(value = parallel.toFloat(), onValueChange = { parallel = it.toInt(); save() }, valueRange = 1f..5f, steps = 3)
            Spacer(Modifier.height(8.dp))
            Text("Download speed limit", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(if (speedLimit == 0) "Unlimited" else "$speedLimit MB/s", color = Pink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Slider(value = speedLimit.toFloat(), onValueChange = { speedLimit = it.toInt(); save() }, valueRange = 0f..10f, steps = 9)
        }
    }
    Spacer(Modifier.height(20.dp))
    SettingsSectionLabel("DESTINATION")
    SettingsCard {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text("Download folder", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(12.dp)).background(Bg).padding(horizontal = 12.dp, vertical = 11.dp)) {
                    androidx.compose.foundation.text.BasicTextField(value = folder, onValueChange = { folder = it; save() }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = TextMain, fontSize = 14.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(Pink))
                }
                Spacer(Modifier.width(8.dp))
                Box(modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Pink).clickable { folderPicker.launch(null) }.padding(horizontal = 14.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
                    Text("Browse", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text("Default is the public Downloads/Anipull folder — finished videos show in Gallery and in Files › Downloads › Anipull. Tap Browse to pick a custom folder instead.", color = Faint, fontSize = 12.sp)
        }
    }
    Spacer(Modifier.height(20.dp))
    SettingsSectionLabel("QUALITY & LANGUAGE")
    SettingsCard {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text("Preferred quality", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("360p", "480p", "720p", "1080p").forEach { q ->
                    Box(modifier = Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, if (quality == q) Pink else Color.White.copy(alpha = 0.09f), RoundedCornerShape(999.dp)).background(if (quality == q) Pink.copy(alpha = 0.2f) else Card).clickable { quality = q; save() }.padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) { Text(q, color = if (quality == q) Color.White else Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Preferred language", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Sub", "Dub").forEach { lang ->
                    Box(modifier = Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, if (language == lang) Pink else Color.White.copy(alpha = 0.09f), RoundedCornerShape(999.dp)).background(if (language == lang) Pink.copy(alpha = 0.2f) else Card).clickable { language = lang; save() }.padding(horizontal = 18.dp, vertical = 8.dp), contentAlignment = Alignment.Center) { Text(lang, color = if (language == lang) Color.White else Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Text("Sub = Japanese audio with subtitles. Dub = English audio.", color = Faint, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            // 2.0-dev2.8: "Prefer smaller files" — at the chosen
            // quality, the race picks the smallest file instead of
            // the fastest server. Default OFF (fastest first).
            SettingsToggle(
                "Prefer smaller files",
                "At your chosen quality, pick the smallest file instead of the fastest server. May take longer — finding the smallest file means waiting to compare servers instead of taking the first one.",
                preferSmaller,
            ) { preferSmaller = it; save() }
        }
    }
    Spacer(Modifier.height(20.dp))
    SettingsSectionLabel("SERVER")
    SettingsCard {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text("Preferred server", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            // 2.0-dev1.6: no source names anywhere user-visible —
            // servers are "Server 1".."Server 9" with the generic
            // descriptions from the registry. Auto is the default.
            val serverOptions = listOf(Triple("0", "Auto", "Tries servers in order until one works")) +
                ServerRegistry.servers.map { Triple(it.number.toString(), "Server ${it.number}", it.description) }
            SettingsDropdown(
                selectedLabel = if (preferredServer == 0) "Auto" else "Server $preferredServer",
                options = serverOptions,
                currentValue = preferredServer.toString(),
                onSelect = { preferredServer = it.toIntOrNull() ?: 0; save() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (preferredServer == 0) "Auto tries servers in your Sources order below until one works. Pick a server to always try it first — the rest still back it up if it fails. A single download can still override this in its sheet."
                else ServerRegistry.description(preferredServer),
                color = Faint, fontSize = 12.sp,
            )
        }
    }
    Spacer(Modifier.height(20.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsSectionLabel("SOURCES")
        Box(
            modifier = Modifier.clip(RoundedCornerShape(999.dp))
                .background(if (testingServers) Card else Pink)
                .clickable(enabled = !testingServers) { runServerTest() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (testingServers) "Testing…" else "Auto-test",
                color = if (testingServers) Muted else Color.White,
                fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
    Text("Auto-select tries servers in this order — easiest first. Move a server up or down to change the order, or tap Auto-test to check every server and put the fastest working ones first. A server that needs a check is never ranked as failed — it stays where it was.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
    if (testSummary != null) {
        Spacer(Modifier.height(8.dp))
        Text(testSummary!!, color = Teal, fontSize = 13.sp, lineHeight = 19.sp)
    }
    Spacer(Modifier.height(12.dp))
    SettingsCard {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            serverOrder.forEachIndexed { index, number ->
                ServerOrderRow(
                    number = number,
                    position = index,
                    total = serverOrder.size,
                    health = serverHealth[number],
                    testing = testingServers,
                    onMoveUp = { moveServer(number, -1) },
                    onMoveDown = { moveServer(number, 1) },
                )
                if (index < serverOrder.size - 1) SettingsDivider()
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    SettingsSectionLabel("BEHAVIOUR")
    SettingsCard {
        SettingsToggle("Download only on Wi-Fi", "Pause downloads on mobile data.", wifiOnly) { wifiOnly = it; save() }
        SettingsDivider()
        SettingsToggle("Ask before a whole season", "Confirm before pulling every episode at once.", askSeason) { askSeason = it; save() }
        SettingsDivider()
        SettingsToggle("Include subtitles when available", "Soft subs are fine — hard subs are not required.", subs) { subs = it; save() }
        SettingsDivider()
        SettingsToggle("Auto-retry failed downloads", "Retry a failed episode once before giving up. Applies when the engine lands.", autoRetry) { autoRetry = it; save() }
        SettingsDivider()
        SettingsToggle("Keep screen on while downloading", "Don't let the phone sleep mid-download. Applies when the engine lands.", keepScreenOn) { keepScreenOn = it; save() }
    }
}

// 2.0-dev1.7: one row of the Sources priority list — rank,
// "Server N" + its generic description (capabilities only, never
// a source name), and up/down arrows. Arrows, not drag-and-drop:
// robust on every phone, no reorder gesture to get wrong. The
// first row's up arrow and the last row's down arrow are greyed
// out and disabled. onClickLabel keeps the arrows announced
// properly by screen readers.
@Composable
private fun ServerOrderRow(number: Int, position: Int, total: Int, health: HealthResult?, testing: Boolean, onMoveUp: () -> Unit, onMoveDown: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${position + 1}", color = Pink, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Server $number", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(ServerRegistry.description(number), color = Muted, fontSize = 12.5.sp)
            // 2.0-dev1.7: auto-test result for this server — latency
            // when it answered, "needs a check" when the test could
            // not judge it (e.g. gated), "failed" when it did not
            // answer at all. Nothing shows before the first test.
            val statusText = when {
                testing -> "Testing…" to Muted
                health?.status == HealthStatus.OK -> "✓ ${health.latencyMs ?: 0} ms" to Teal
                health?.status == HealthStatus.UNKNOWN -> "? Needs a check" to Muted
                health?.status == HealthStatus.FAILED -> "✗ Failed" to Color(0xFFFF3B30)
                else -> null
            }
            if (statusText != null) {
                Spacer(Modifier.height(2.dp))
                Text(statusText.first, color = statusText.second, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "↑", color = if (position > 0) Pink else Faint, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(enabled = position > 0, onClickLabel = "Move Server $number up") { onMoveUp() }
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
            Text(
                "↓", color = if (position < total - 1) Pink else Faint, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(enabled = position < total - 1, onClickLabel = "Move Server $number down") { onMoveDown() }
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

// ---- Notifications ----------------------------------------------------
@Composable
fun NotificationsPage() {
    val prefs = SettingsPrefs()
    var notifOn by remember { mutableStateOf(prefs.getBoolean("notif_enabled", true)) }
    var completionOn by remember { mutableStateOf(prefs.getBoolean("completion_enabled", true)) }
    fun save() { prefs.edit().putBoolean("notif_enabled", notifOn).putBoolean("completion_enabled", completionOn).apply() }
    Text("Notifications are only for download progress — Anipull never sends marketing or tracking pings. The permission itself is asked on the onboarding notification page.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
    Spacer(Modifier.height(20.dp))
    SettingsSectionLabel("DOWNLOADS")
    SettingsCard {
        SettingsToggle("Download progress notifications", "Per-episode progress, MB/s, and Pause/Cancel while downloading.", notifOn) { notifOn = it; save() }
        SettingsDivider()
        SettingsToggle("Completion ping", "A short ping when a download finishes, tapping through to Downloads.", completionOn) { completionOn = it; save() }
    }
    Spacer(Modifier.height(20.dp))
    Text("See the exact design in the Downloads tab — 'Preview download notification' posts a sample progress notification, then a completion ping.", color = Faint, fontSize = 12.sp)
}

// ---- Storage ----------------------------------------------------------
@Composable
fun StoragePage() {
    val ctx = LocalContext.current
    val prefs = SettingsPrefs()
    var cacheMsg by remember { mutableStateOf<String?>(null) }
    var backupMsg by remember { mutableStateOf<String?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val folder = prefs.getString("folder", "Downloads/Anipull") ?: "Downloads/Anipull"
    // 1.9-dev5.0 QOL 3: show the real poster-cache size, so "Clear home
    // cache" is an informed tap, not a guess. Computed once per visit
    // (a few hundred stat calls, no network).
    val cacheSizeText = remember {
        try {
            var bytes = 0L
            ctx.cacheDir.resolve("posters").walkTopDown().forEach { if (it.isFile) bytes += it.length() }
            if (bytes < 1024L * 1024L) "${bytes / 1024L} KB" else String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        } catch (_: Exception) { "?" }
    }
    // Backup & restore Saved library: a plain JSON file, no account,
    // no upload — export shares/saves it, import reads it back and
    // replaces the Saved list (duplicates by id are dropped).
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val json = ctx.getSharedPreferences("anipull_library", Context.MODE_PRIVATE).getString("saved_json", "[]") ?: "[]"
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val count = try { org.json.JSONArray(json).length() } catch (_: Exception) { 0 }
            backupMsg = "Saved library exported — $count title(s) in the file."
        } catch (_: Exception) { backupMsg = "Export failed — try again." }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val titles = libraryTitlesFromJson(text).distinctBy { it.id }
            if (titles.isEmpty()) { backupMsg = "That file has no saved titles in it."; return@rememberLauncherForActivityResult }
            saveLibraryTitles(ctx, titles)
            backupMsg = "Restored ${titles.size} saved title(s) — check the Library tab."
        } catch (_: Exception) { backupMsg = "Import failed — that file is not an Anipull library backup." }
    }
    if (showResetConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset all settings?", color = TextMain, fontWeight = FontWeight.Bold, fontFamily = HeadingFont) },
            text = { Text("Every setting goes back to its default — accent, dock, downloads, notifications, everything. Your Saved library and home cache are NOT touched.", color = Muted, fontSize = 14.sp) },
            confirmButton = {
                Box(modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Pink).clickable {
                    prefs.edit().clear().apply()
                    AccentState.color = Color(0xFFFF4D8D)
                    ThemeState.amoled = false; ThemeState.sfHeadings = true; ThemeState.reduceMotion = false; ThemeState.lightMode = false
                    showResetConfirm = false
                    cacheMsg = "Settings reset to defaults."
                }.padding(horizontal = 20.dp, vertical = 10.dp)) { Text("Reset", color = OnAccent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { Text("Cancel", color = Muted, modifier = Modifier.clickable(onClick = { showResetConfirm = false }).padding(horizontal = 12.dp, vertical = 10.dp)) },
        )
    }
    SettingsSectionLabel("CACHE")
    SettingsCard {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text("Home cache", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Last good home catalogs plus downloaded posters ($cacheSizeText on disk). Clearing it reloads live on next open.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(12.dp)).background(Bg).clickable {
                ctx.cacheDir.resolve("posters").deleteRecursively()
                ctx.getSharedPreferences("anipull_cache", Context.MODE_PRIVATE).edit().clear().apply()
                cacheMsg = "Home cache cleared — next open reloads live."
            }.padding(horizontal = 16.dp, vertical = 12.dp)) { Text("Clear home cache", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            if (cacheMsg != null) { Spacer(Modifier.height(8.dp)); Text(cacheMsg!!, color = Teal, fontSize = 12.5.sp) }
        }
    }
    Spacer(Modifier.height(20.dp))
    SettingsSectionLabel("LIBRARY BACKUP")
    SettingsCard {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text("Saved library", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Export your Saved list to a file, import it back after a reinstall. No account, nothing leaves your phone except the file you choose.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(12.dp)).background(Bg).clickable { exportLauncher.launch("anipull-library.json") }.padding(horizontal = 16.dp, vertical = 12.dp)) { Text("Export", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                Box(modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(12.dp)).background(Bg).clickable { importLauncher.launch(arrayOf("application/json", "text/*")) }.padding(horizontal = 16.dp, vertical = 12.dp)) { Text("Import", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
            if (backupMsg != null) { Spacer(Modifier.height(8.dp)); Text(backupMsg!!, color = Teal, fontSize = 12.5.sp) }
        }
    }
    Spacer(Modifier.height(20.dp))
    SettingsSectionLabel("RESET")
    SettingsCard {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text("Reset all settings", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Back to defaults for every setting. Saved library and home cache are kept.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Color(0xFFFF3B30).copy(alpha = 0.4f), RoundedCornerShape(12.dp)).background(Bg).clickable { showResetConfirm = true }.padding(horizontal = 16.dp, vertical = 12.dp)) { Text("Reset all settings", color = Color(0xFFFF3B30), fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
    Spacer(Modifier.height(20.dp))
    SettingsSectionLabel("DOWNLOAD FOLDER")
    SettingsCard {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            // 2.0-dev2.6: show the REAL location. Default is the
            // public Downloads/Anipull folder (visible in Gallery
            // and Files) — the old label said the same words while
            // files actually went to the app-private folder. A
            // Browse-picked folder shows its own display name.
            val hasCustomFolder = (prefs.getString("folder_uri", null)?.isNotBlank() == true)
            // 2.0-dev2.6: files left in the old private folder —
            // unfinished downloads move automatically when they
            // finish; anything else here is a partial from a failed
            // or cancelled download. Counted once per visit.
            val legacyCount = remember { DownloadStorage.countLegacyVideoFiles(ctx) }
            if (hasCustomFolder) {
                Text(folder, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Custom folder, picked in Downloads settings — files go there instead of the public Downloads folder.", color = Muted, fontSize = 13.sp)
            } else {
                Text("Download/Anipull", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Public Downloads folder — finished videos appear in Gallery and in Files › Downloads › Anipull.", color = Muted, fontSize = 13.sp)
            }
            if (legacyCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (legacyCount == 1) "1 older file is still in the app's private folder — an unfinished download moves here automatically when it finishes."
                    else "$legacyCount older files are still in the app's private folder — unfinished downloads move here automatically when they finish.",
                    color = Muted, fontSize = 13.sp,
                )
            }
            Text("Change it in Downloads settings, with the Browse picker.", color = Muted, fontSize = 13.sp)
        }
    }
}

// ---- About ------------------------------------------------------------
// ---- 1.9-dev5.0: update-channel radio row (Nuvio-style) --------------
@Composable
private fun ChannelOption(title: String, sub: String, selected: Boolean, onPick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onPick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onPick, colors = RadioButtonDefaults.colors(selectedColor = Pink, unselectedColor = Muted))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

// 2.0-dev1.0: manual "Check for updates" success popup. One Canvas
// + Animatable, no new dependency: circle draws 0→1 (~400ms), then
// the check path draws (~300ms), then a gentle pulse ring repeats.
// The check Path is precomputed once in remember — nothing is
// allocated per frame. reduceMotion draws the finished check statically.
@Composable
private fun AnimatedSuccessCheck() {
    val density = LocalDensity.current
    val radiusPx = with(density) { 28.dp.toPx() }
    val strokePx = with(density) { 3.dp.toPx() }
    val reduceMotion = ThemeState.reduceMotion
    val circleTrim = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val checkTrim = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val checkPath = remember(density) {
        Path().apply {
            with(density) {
                moveTo(22.dp.toPx(), 36.dp.toPx())
                lineTo(32.dp.toPx(), 46.dp.toPx())
                lineTo(50.dp.toPx(), 26.dp.toPx())
            }
        }
    }
    val pulse = rememberInfiniteTransition(label = "successPulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(animation = tween(1600), repeatMode = RepeatMode.Restart),
        label = "successPulseScale",
    )
    LaunchedEffect(Unit) {
        if (reduceMotion) return@LaunchedEffect
        circleTrim.animateTo(1f, animationSpec = tween(400))
        checkTrim.animateTo(1f, animationSpec = tween(300))
    }
    val measure = remember { PathMeasure() }
    val segment = remember { Path() }
    Canvas(modifier = Modifier.size(72.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        // Pulse ring, only once the check is fully drawn.
        if (!reduceMotion && checkTrim.value >= 1f) {
            val frac = (pulseScale - 1f) / 0.6f
            drawCircle(
                color = Pink.copy(alpha = 0.35f * (1f - frac)),
                radius = radiusPx * pulseScale,
                center = center,
                style = Stroke(width = strokePx * 0.75f),
            )
        }
        drawArc(
            color = Pink,
            startAngle = -90f,
            sweepAngle = 360f * circleTrim.value,
            useCenter = false,
            topLeft = center - androidx.compose.ui.geometry.Offset(radiusPx, radiusPx),
            size = androidx.compose.ui.geometry.Size(radiusPx * 2f, radiusPx * 2f),
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
        if (checkTrim.value > 0f) {
            measure.setPath(checkPath, false)
            segment.reset()
            measure.getSegment(0f, measure.length * checkTrim.value, segment)
            drawPath(segment, color = Pink, style = Stroke(width = strokePx, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

@Composable
fun AboutPage() {
    val uriHandler = LocalUriHandler.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        androidx.compose.foundation.Image(painter = painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(12.dp))
        Text("Anipull", color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, fontFamily = HeadingFont)
        Text("Download-only anime. No streaming, no tracking.", color = Muted, fontSize = 13.sp)
        Text("2.0-dev2.4 · com.js.anipull", color = Faint, fontSize = 12.sp)
    }
    Spacer(Modifier.height(20.dp))
    SettingsSectionLabel("PROJECT")
    SettingsCard {
        SettingsRow(R.drawable.ic_info, "GitHub — Anipull App", "github.com/JSxtrme/AniPull-App", { uriHandler.openUri("https://github.com/JSxtrme/AniPull-App") })
        SettingsDivider()
        SettingsRow(R.drawable.ic_info, "GitHub — Website", "github.com/JSxtrme/AniPull (demo, paused)", { uriHandler.openUri("https://github.com/JSxtrme/AniPull") })
        SettingsDivider()
        SettingsRow(R.drawable.ic_info, "Website demo", "anipull.vercel.app", { uriHandler.openUri("https://anipull.vercel.app") })
    }
    Spacer(Modifier.height(20.dp))
    SettingsSectionLabel("UPDATES")
    SettingsCard {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var channel by remember { mutableStateOf(updateChannel(ctx)) }
        var showChannelDialog by remember { mutableStateOf(false) }
        var checkMsg by remember { mutableStateOf<String?>(null) }
        var showUpToDateDialog by remember { mutableStateOf(false) }
        var checking by remember { mutableStateOf(false) }
        // Manual check ignores the Later snooze — it always shows
        // the sheet when an update exists for this channel.
        var manualUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
        SettingsRow(R.drawable.ic_download, "Update channel", if (channel == "dev") "Dev" else "Stable", { showChannelDialog = true })
        SettingsDivider()
        SettingsRow(R.drawable.ic_download, "Check for updates", "See if a newer build is out", {
            if (checking) return@SettingsRow
            checking = true
            checkMsg = "Checking for updates…"
            scope.launch {
                val result = checkForUpdate(ctx, channel)
                checking = false
                when (result) {
                    is UpdateCheckResult.Available -> { checkMsg = null; manualUpdate = result.info }
                    UpdateCheckResult.UpToDate -> { checkMsg = null; showUpToDateDialog = true }
                    UpdateCheckResult.NoNetwork -> checkMsg = "Couldn't check for updates — check your connection and try again."
                    is UpdateCheckResult.Failed -> checkMsg = "Couldn't check for updates — ${result.message}"
                }
            }
        })
        SettingsDivider()
        SettingsRow(R.drawable.ic_share, "Share Anipull", "Tell a friend about the app", { shareAnipull(ctx) })
        if (checkMsg != null) {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    checkMsg!!,
                    color = if (checkMsg!!.startsWith("Couldn't")) Color(0xFFFF3B30) else Teal,
                    fontSize = 13.sp, lineHeight = 19.sp,
                )
            }
        }
        if (showChannelDialog) {
            AlertDialog(
                onDismissRequest = { showChannelDialog = false },
                title = { Text("Update channel", color = TextMain, fontWeight = FontWeight.Bold, fontFamily = HeadingFont) },
                text = {
                    Column {
                        ChannelOption("Stable", "Intended for everyday use.", channel == "stable") {
                            setUpdateChannel(ctx, "stable"); channel = "stable"; showChannelDialog = false
                        }
                        Spacer(Modifier.height(4.dp))
                        ChannelOption("Dev", "Early updates that may contain bugs, break features, or behave unexpectedly.", channel == "dev") {
                            setUpdateChannel(ctx, "dev"); channel = "dev"; showChannelDialog = false
                        }
                    }
                },
                confirmButton = { Text("Cancel", color = Muted, modifier = Modifier.clickable(onClick = { showChannelDialog = false }).padding(horizontal = 12.dp, vertical = 10.dp)) },
            )
        }
        manualUpdate?.let { info ->
            UpdateSheet(info = info, currentVersionName = installedVersionName(ctx), onLater = { manualUpdate = null }, onDismiss = { manualUpdate = null })
        }
        // 2.0-dev1.0: manual check only — the silent auto-check in
        // App.kt never opens this. BACK / outside tap dismisses it
        // (Dialog's onDismissRequest covers both). No buttons.
        if (showUpToDateDialog) {
            Dialog(onDismissRequest = { showUpToDateDialog = false }) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Card)
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AnimatedSuccessCheck()
                    Spacer(Modifier.height(16.dp))
                    Text("You're on the latest version", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = HeadingFont, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("Anipull ${installedVersionName(ctx)}", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    SettingsSectionLabel("DATA")
    SettingsCard {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text("Metadata: AniLab (no key, live feed).", color = TextMain, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text("Downloads run on 9 servers, picked automatically.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text("Downloads use the real engine — queue, pause/resume and the foreground service are live.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

// ---- Custom colour picker ------------------------------------------------
// Easiest real picker: an HSV square (saturation left→right, value
// top→bottom) plus a rainbow hue slider. Drag anywhere on the square,
// slide the hue, see the preview + hex, tap Apply. No preset list,
// any colour the user wants, including white — which no longer
// breaks the app because solid accent backgrounds use OnAccent.
@Composable
fun ColorPickerDialog(initialHex: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    val startHsv = remember {
        val hsv = FloatArray(3)
        try {
            val c = android.graphics.Color.parseColor("#" + initialHex)
            android.graphics.Color.colorToHSV(c, hsv)
        } catch (_: Exception) { hsv[0] = 330f; hsv[1] = 1f; hsv[2] = 1f }
        hsv
    }
    var hue by remember { mutableStateOf(startHsv[0]) }
    var sat by remember { mutableStateOf(startHsv[1]) }
    var value by remember { mutableStateOf(startHsv[2]) }
    val current = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
    val hex = String.format("%02X%02X%02X", (current.red * 255).toInt(), (current.green * 255).toInt(), (current.blue * 255).toInt())
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick an accent", color = TextMain, fontWeight = FontWeight.Bold, fontFamily = HeadingFont) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(999.dp)).background(current).border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(999.dp)))
                    Spacer(Modifier.width(12.dp))
                    Text("#$hex", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
                val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                androidx.compose.foundation.layout.BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp))
                        .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.White, hueColor)))
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                sat = (offset.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            }
                        },
                ) {
                    Box(modifier = Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
                    Box(
                        modifier = Modifier
                            .offset(maxWidth * sat - 12.dp, maxHeight * (1f - value) - 12.dp)
                            .size(24.dp).clip(RoundedCornerShape(999.dp)).border(2.dp, Color.White, RoundedCornerShape(999.dp)),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Box(modifier = Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(999.dp)).background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))) {
                    Slider(
                        value = hue, onValueChange = { hue = it }, valueRange = 0f..360f,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        },
        confirmButton = {
            Box(modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(current).clickable { onApply(hex) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text("Apply", color = if (current.luminance() > 0.6f) Color.Black else Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Text("Cancel", color = Muted, modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 12.dp, vertical = 10.dp))
        },
    )
}
