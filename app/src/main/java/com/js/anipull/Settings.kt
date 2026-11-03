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
import androidx.compose.animation.core.tween
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
            Spacer(Modifier.height(28.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.foundation.Image(painter = painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("Anipull", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, fontFamily = HeadingFont)
                Text("Download-only anime. No streaming, no tracking.", color = Muted, fontSize = 13.sp)
                Text("1.9-dev4.8.8-stablekey · com.js.anipull", color = Faint, fontSize = 12.sp)
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
    Text(text, color = Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
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
    var heroMotion by remember { mutableStateOf(prefs.getString("hero_motion", "normal") ?: "normal") }
    fun save() {
        prefs.edit().putBoolean("translucent_dock", translucentDock).putString("dock_mode", dockMode).putBoolean("carousel_auto", carouselAuto).putBoolean("particles", particles).putBoolean("show_explore", showExplore).putInt("explore_columns", exploreCols).putString("hero_motion", heroMotion).apply()
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
        SettingsToggle("Home particles", "Drifting, glowing, twinkling dots on the home page.", particles) { particles = it; save() }
        SettingsDivider()
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text("Poster transition motion", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val heroLabel = when (heroMotion) { "easein" -> "Ease in"; "easeout" -> "Ease out"; "overshoot" -> "Overshoot"; "bouncy" -> "Bouncy"; "cinematic" -> "Cinematic"; else -> "Normal" }
            SettingsDropdown(
                selectedLabel = heroLabel,
                options = listOf(
                    Triple("normal", "Normal", "Current spring, no bounce"),
                    Triple("easein", "Ease in", "Slow start, then speeds up"),
                    Triple("easeout", "Ease out", "Fast start, then gradually settles"),
                    Triple("overshoot", "Overshoot", "Bounces slightly past, then settles"),
                    Triple("bouncy", "Bouncy", "More bounce, then settles"),
                    Triple("cinematic", "Cinematic", "Slow, smooth in-out"),
                ),
                currentValue = heroMotion,
                onSelect = { heroMotion = it; save() },
            )
            Text("How the poster flies home to details (and back). Try Ease in if you want the opposite of Ease out — it starts gentle and accelerates into place.", color = Faint, fontSize = 12.sp)
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
    fun save() {
        prefs.edit().putInt("parallel", parallel).putInt("speed_mbps", speedLimit).putString("folder", folder).putString("quality", quality).putString("language", language).putBoolean("wifi_only", wifiOnly).putBoolean("ask_season", askSeason).putBoolean("subs", subs).putBoolean("auto_retry", autoRetry).putBoolean("keep_screen_on", keepScreenOn).apply()
    }
    val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) { }
            val raw = java.net.URLDecoder.decode(uri.toString(), "UTF-8")
            val display = raw.substringAfter(":", raw).removePrefix("/tree/").replace("primary:", "").trim()
            if (display.isNotBlank()) { folder = display; prefs.edit().putString("folder", display).putString("folder_uri", uri.toString()).apply() }
        }
    }
    Text("Saved on this phone. Download settings apply once the download engine lands — right now downloads are still demo-only.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
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
            Text("Tap Browse to pick a folder from your phone — no need to type a path by hand.", color = Faint, fontSize = 12.sp)
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
    val folder = prefs.getString("folder", "Downloads/Anipull") ?: "Downloads/Anipull"
    SettingsSectionLabel("CACHE")
    SettingsCard {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text("Home cache", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Last good home catalogs plus downloaded posters. Clearing it reloads live on next open.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
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
    SettingsSectionLabel("DOWNLOAD FOLDER")
    SettingsCard {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text(folder, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Change it in Downloads settings, with the Browse picker.", color = Muted, fontSize = 13.sp)
        }
    }
}

// ---- About ------------------------------------------------------------
@Composable
fun AboutPage() {
    val uriHandler = LocalUriHandler.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        androidx.compose.foundation.Image(painter = painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(12.dp))
        Text("Anipull", color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, fontFamily = HeadingFont)
        Text("Download-only anime. No streaming, no tracking.", color = Muted, fontSize = 13.sp)
        Text("1.9-dev4.8.8-stablekey · com.js.anipull", color = Faint, fontSize = 12.sp)
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
    SettingsSectionLabel("DATA")
    SettingsCard {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text("Metadata: AniLab (no key, live feed).", color = TextMain, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text("Sources indexed: AnimePahe · AnimeTosho · AnimeOut.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text("Downloads are demo-only until 2.0, when the real engine, queue, pause/resume and foreground service land.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
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
