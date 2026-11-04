package com.js.anipull

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.ArcMode
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.toSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onPlaced
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Colours taken from the web mockup's CSS variables.
private val OutfitFont = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_bold, FontWeight.Bold),
    Font(R.font.outfit_extrabold, FontWeight.ExtraBold),
)

// SF Pro Display Semibold, supplied by JS as
// sf_pro_display_semibold.otf. 1.9-dev4.8: no longer onboarding-only
// — it is now the HEADING font across the app (hero titles, section
// headings, detail title), mixed with Outfit for body text, so the
// font JS supplied is not wasted on a screen shown once. The same
// file is mapped to every weight, so a heading renders SF whatever
// weight the Text asked for. Note: this OTF uses CFF outlines — if
// it ever fails to render on a phone, Compose falls back to the
// system font, and the fix then is a TrueType conversion.
private val SFProDisplaySemibold = FontFamily(
    Font(R.font.sf_pro_display_semibold, FontWeight.SemiBold),
)
private val SFHeadingFamily = FontFamily(
    Font(R.font.sf_pro_display_semibold, FontWeight.Normal),
    Font(R.font.sf_pro_display_semibold, FontWeight.SemiBold),
    Font(R.font.sf_pro_display_semibold, FontWeight.Bold),
    Font(R.font.sf_pro_display_semibold, FontWeight.ExtraBold),
)
// 1.9-dev4.8.1: accent, AMOLED and heading font are now LIVE state,
// not fixed vals, so Settings can change them and the whole app
// recomposes. Pink/Bg/Card/HeadingFont are getters over that state —
// every existing usage keeps working, and AnipullTheme (which reads
// Pink) subscribes the whole tree to accent changes. Saved in
// SharedPreferences "anipull_settings" (accent_hex, amoled,
// sf_headings, reduce_motion); MainActivity loads them before
// setContent so there is no pink flash on launch.
object AccentState { var color by mutableStateOf(Color(0xFFFF4D8D)) }
object ThemeState {
    var amoled by mutableStateOf(false)
    var sfHeadings by mutableStateOf(true)
    var reduceMotion by mutableStateOf(false)
    var lightMode by mutableStateOf(false)
}
// 1.9-dev4.8.2: OnAccent is the foreground for SOLID accent
// backgrounds (dock pill, pink buttons, 1080p pill). White accent
// used to kill the app because white text sat on a white pill —
// now a light accent (luminance > 0.6) gets black text instead.
// Pink TEXT on a dark background stays readable with white, so
// only solid backgrounds use OnAccent.
val OnAccent: Color get() = if (AccentState.color.luminance() > 0.6f) Color.Black else Color.White
val Pink: Color get() = AccentState.color
val HeadingFont: FontFamily get() = if (ThemeState.sfHeadings) SFHeadingFamily else OutfitFont

val Bg: Color get() = when {
    ThemeState.lightMode -> Color(0xFFF2F2F7)
    ThemeState.amoled -> Color.Black
    else -> Color(0xFF07070C)
}
val Bg2: Color get() = when {
    ThemeState.lightMode -> Color.White
    ThemeState.amoled -> Color.Black
    else -> Color(0xFF0B0B13)
}
val Card: Color get() = when {
    ThemeState.lightMode -> Color.White
    ThemeState.amoled -> Color(0xFF111114)
    else -> Color(0xFF16161F)
}
// Light-mode surfaces for places that were hardcoded dark:
// search pill, poster placeholder, and the line colour for
// Settings cards (Settings.kt uses Line instead of a white border).
val SearchBg: Color get() = if (ThemeState.lightMode) Color.White else Color(0xFF10101A)
val PosterBg: Color get() = if (ThemeState.lightMode) Color(0xFFE8E8EE) else Color(0xFF14141F)
val Line: Color get() = if (ThemeState.lightMode) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.09f)

fun loadThemePrefs(context: android.content.Context) {
    val pr = context.getSharedPreferences("anipull_settings", android.content.Context.MODE_PRIVATE)
    val hex = pr.getString("accent_hex", "FF4D8D") ?: "FF4D8D"
    try { AccentState.color = Color(("FF" + hex).toLong(16)) } catch (_: Exception) { }
    ThemeState.amoled = pr.getBoolean("amoled", false)
    ThemeState.sfHeadings = pr.getBoolean("sf_headings", true)
    ThemeState.reduceMotion = pr.getBoolean("reduce_motion", false)
    ThemeState.lightMode = pr.getBoolean("light_mode", false)
}
val TextMain: Color get() = if (ThemeState.lightMode) Color(0xFF11111A) else Color(0xFFF5F5FA)
val Muted: Color get() = if (ThemeState.lightMode) Color(0xFF5A5A6E) else Color(0xFFA2A2B5)
val Faint: Color get() = if (ThemeState.lightMode) Color(0xFF8A8A9E) else Color(0xFF6D6D82)
private val Lavender = Color(0xFFA78BFA)
val Teal = Color(0xFF2DD4BF)

@Composable
fun AnipullTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg,
            surface = Bg2,
            primary = Pink,
            onBackground = TextMain,
            onSurface = TextMain,
        ),
        content = content,
    )
}

@Composable
fun LogoRow(
    modifier: Modifier = Modifier,
    chipSize: androidx.compose.ui.unit.Dp = 28.dp,
    iconSize: androidx.compose.ui.unit.Dp = 22.dp,
    nameSize: androidx.compose.ui.unit.TextUnit = 18.sp,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(chipSize)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(8.dp))
                .background(Bg),
            contentAlignment = Alignment.Center,
        ) {
            // Orbit logo is pink on black — the old pink chip would hide
            // the pink ring and sparkle, leaving nothing visible.
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "Anipull icon",
                modifier = Modifier.size(iconSize),
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = Color.White)) { append("Ani") }
                withStyle(SpanStyle(color = Pink)) { append("pull") }
            },
            fontSize = nameSize,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.4).sp,
        )
    }
}

@Composable
fun TopBar() {
    // 1.9-dev4.5, JS's call: the header is NOT sticky any more — the
    // dock owns navigation now, so a sticky bar is just dead space
    // at the top while scrolling. It scrolls away with the hero,
    // with no solid pill on scroll and no extra gap above it (only
    // the status-bar inset). Logo + name stay in the left corner,
    // bigger again — dev4's 36/28/22 was still not big enough, so
    // now chip 48dp, icon 38dp, name 28sp. The footer LogoRow
    // keeps the old smaller size via defaults.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoRow(chipSize = 48.dp, iconSize = 38.dp, nameSize = 28.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceChips() {
    // HTML sources row wraps (flex-wrap, centred) — AnimeOut lands on a
    // second line on a phone, it must never be clipped off-screen.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("Sources", color = Faint, fontSize = 12.sp, modifier = Modifier.padding(end = 7.dp, top = 6.dp))
        SourceChip("AnimePahe", "healthy", Color(0xFF34D399))
        Spacer(Modifier.width(7.dp))
        SourceChip("AnimeTosho", "healthy", Color(0xFF34D399))
        Spacer(Modifier.width(7.dp))
        SourceChip("AnimeOut", "slow today", Color(0xFFFBBF24))
    }
}

@Composable
fun SourceChip(name: String, status: String, dot: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(7.dp).clip(RoundedCornerShape(999.dp)).background(dot))
        Spacer(Modifier.width(7.dp))
        Text(name, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        Text("· $status", color = Faint, fontSize = 12.sp)
    }
}

@Composable
fun Chip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(999.dp))
            .background(Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        Text(label, color = Muted, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun QuickChips(onChip: (String) -> Unit) {
    // 1.9-dev2 order, JS's call: Movies / Series / OVA / Specials.
    // "1080p ready" is dropped (quality already lives in Settings and
    // in the hero stats). One horizontal line, LazyRow, centred.
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(listOf("Movies", "Series", "OVA", "Specials")) { label ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(999.dp))
                    .background(Card)
                    .clickable { onChip(label) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(label, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.width(7.dp))
        }
    }
}

@Composable
fun StatCell(value: String, label: String) {
    // Tight, like the site's .stat: 12px/8px padding, 18px value,
    // 1px gap to the label — no extra line-height air inside the card.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .background(Card)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(value, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 20.sp)
        Text(label, color = Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 13.sp)
    }
}

@Composable
fun StatsGrid(live: Boolean, loading: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.weight(1f)) {
                // 1.9-dev2: the LIVE/ANILAB FEED cell becomes a title
                // count. AniLab exposes no total count, but its IDs run
                // to 1000006620 (verified 2026-11-02, 6700+ are 404), so
                // "6000+" is evidence-backed and does not overstate.
                if (loading) StatCell("···", "LOADING")
                else StatCell("6000+", "ANIME TITLES")
            }
            Box(Modifier.weight(1f)) { StatCell("3", "DDL SOURCES") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.weight(1f)) { StatCell("1080p", "MAX QUALITY") }
            Box(Modifier.weight(1f)) { StatCell("0", "TRACKERS") }
        }
    }
}

@Composable
fun PosterImage(poster: String?, modifier: Modifier, targetWidthPx: Int = 0) {
    // 1.9-dev4.8.3 (Nuvio lesson): Coil decodes to the size the
    // composable actually shows, not the full poster. We do the same
    // without adding Coil (Gradle cannot fetch new deps on this VM):
    // targetWidthPx is the card width in pixels, loadPoster
    // downsamples to it, so a 118dp card never decodes/uploads a
    // full-size poster texture during a fast scroll.
    var bitmap by remember(poster, targetWidthPx) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (poster != null) {
        LaunchedEffect(poster, targetWidthPx) {
            // 1.9-dev4.8.7 Nuvio-inspired: memory/disk hits load at
            // once (fast, no cost to scroll). A NETWORK fetch waits
            // 120ms first — delay() is cancellable, so a card that
            // flashes past in a fling is disposed before the fetch
            // ever starts, instead of queueing a decode for a card
            // that is already gone. After the fling settles, 120ms
            // is imperceptible; during a fling it is the whole win.
            val cached = PosterCache.get(poster, targetWidthPx)
            if (cached != null) {
                bitmap = cached
            } else {
                // 4.8.10 Nuvio-style lazy load: network posters
                // wait 300ms (was 120ms) before starting, so a
                // card that flashes past in a fling never starts
                // a network fetch + decode at all. Disk/memory
                // hits still load instantly. This is the main
                // scroll-during-loading fix: during initial load,
                // dozens of network fetches + GPU uploads were
                // competing with scroll frames, then everything
                // went smooth once cached — exactly what JS
                // reported. Slower poster appearance during a
                // fling, smoother scroll, same posters after settle.
                if (!isPosterOnDisk(poster, ctx)) kotlinx.coroutines.delay(300)
                bitmap = loadPoster(poster, ctx, targetWidthPx)
            }
        }
    }
    Box(modifier = modifier.background(PosterBg)) {
        val bmp = bitmap
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
            )
        }
    }
}

// 1.9-dev4.8.6-hotfix, root-cause guard (logcat 2026-11-03 21:05):
// boundsInWindow() throws IllegalStateException ("LayoutCoordinate
// operations are only valid when isAttached is true") when called on
// a node whose parent chain is deactivated — exactly what happens to
// a LazyList item being placed as a disappearing item. Checking
// isAttached on the child alone is NOT enough (the log's first node
// was isAttached=true, deactivated=true, and the throw came from a
// parent coordinator). So every placement-time bounds read goes
// through this: try, and skip on failure. A skipped bounds read
// just means "no reverse source this frame", never a crash.
private fun safeBounds(coords: androidx.compose.ui.layout.LayoutCoordinates?): androidx.compose.ui.geometry.Rect? {
    if (coords == null) return null
    return try { coords.boundsInWindow() } catch (e: IllegalStateException) { null }
}

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }
val LocalSharedPosterKey = compositionLocalOf<String?> { null }
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.sharedState(key: String) = rememberSharedContentState(key = key)

// 4.8.15 SharedTransition bounds: poster morphs in 300ms, easeInOutCubic,
// on a circular arc bulging UP (ArcAbove), matching shonenx/AnymeX Hero.
// Same duration/curve as the page slide, so poster + page never drift.
// BoundsTransform is a framework hook — no captured rects, no manual lerp,
// size/position come from real layout in both pages, so balloon is impossible.
@OptIn(ExperimentalSharedTransitionApi::class)
val PosterBoundsTransform: BoundsTransform = BoundsTransform { initialBounds, targetBounds ->
    // 4.8.16 loop: forward (to detail, target top smaller than initial top)
    // mirrors to ArcBelow, reverse (back home, target top larger) keeps
    // ArcAbove. Same 300ms, same easeInOutCubic, ONLY the side flips,
    // so forward + reverse together form a loop, both clockwise in
    // traversal as JS asked, curve path otherwise unchanged.
    val arcMode = if (targetBounds.top < initialBounds.top) ArcMode.ArcBelow else ArcMode.ArcAbove
    keyframes {
        durationMillis = 300
        initialBounds at 0 using arcMode using androidx.compose.animation.core.CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
        targetBounds at 300
    }
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun PosterCard(title: ApiTitle, onClick: () -> Unit, notWired: (() -> Unit)? = null, cardWidth: Dp = 118.dp, onBounds: ((Rect) -> Unit)? = null, onPosterBounds: ((Rect) -> Unit)? = null, reportBoundsOnPlace: Boolean = false, reverseHiddenId: Long? = null, reverseFade: Float = 1f, slot: String? = null) {
    val cardHeight = cardWidth * 3 / 2
    // 1.9-dev4.8.2 scroll optimisation + full-card hero: bounds are
    // NO LONGER reported by onGloballyPositioned, which fires on
    // every scroll frame for every visible card (a real fast-scroll
    // jerk source). Instead the card keeps its LayoutCoordinates in
    // a plain holder (not state, so no recomposition) and reports
    // boundsInWindow() AT TAP TIME, right before onClick runs —
    // fresher than a stored rect, and zero cost while scrolling.
    // The detail card is the exception: it reports on place
    // (reportBoundsOnPlace) so the hero has a destination rect
    // before the user can scroll the detail page. onPosterBounds
    // is the poster-image-only rect, for the carousel hero path.
    val coordsHolder = remember { arrayOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !ThemeState.reduceMotion) 0.96f else 1f,
        animationSpec = spring(stiffness = 400f, dampingRatio = 0.7f),
        label = "posterPress",
    )
    val cardTargetPx = with(LocalDensity.current) { cardWidth.toPx().toInt() }
    // 1.9-dev4.8.6-hotfix: slot-clear by ID (see below) —
    // the old position-match is gone, not just disabled.
    // 1.9-dev4.8.6-hotfix: slot-clear by ID, not by position.
    // The 4.8.6 position-match read boundsInWindow() from onPlaced
    // and set state during layout, for every visible card, on every
    // back — that is the prime crash suspect (stale/unattached
    // coordinates, state change during layout). Now the destination
    // card is simply the one whose id matches, no bounds, no
    // LaunchedEffect, no onPlaced state. Trade-off, stated plainly:
    // every copy of that title hides for the 0.45s flight, not just
    // the one slot — safety first, position-match can return later
    // once the crash log confirms the root cause.
    val isReverseHidden = reverseHiddenId != null && title.id == reverseHiddenId
    // 1.9-dev4.8.7: destination card is NOT an empty slot any
    // more. An empty slot has no clickable, so a tap during reverse
    // (or in the frame it flips back to full) was lost — JS's
    // "tap again, it won't transition again" dead zone, worst on
    // Editor's picks. Now the destination card stays a full,
    // clickable card throughout reverse, fading in with
    // reverseProgress (0 -> 1) underneath the flying overlay.
    // At progress 1 it is already fully visible, so when the
    // overlay clears there is no pop, and a tap at any moment
    // registers (openTitle cancels reverse and reopens, see below).
    val cardAlpha = if (isReverseHidden) reverseFade else 1f
    // 4.8.15 SharedTransition, poster-only: if this card's slot
    // matches the tapped slot (from LocalSharedPosterKey), and
    // we are inside SharedTransitionLayout + AnimatedContent
    // (locals non-null), this poster Box becomes the shared
    // element. Framework hides it in place during transition
    // (leaving the empty slot in home / gap in detail), flies
    // it in the overlay with PosterBoundsTransform (arc, 300ms),
    // and lands it in the detail poster slot. Title below the
    // poster is NOT shared — it slides out with home, detail
    // title slides in with detail, exactly AnymeX poster-only.
    // Always remember the SharedContentState when a scope
    // exists, so remember order stays stable when isShared flips.
    val sharedScope = LocalSharedTransitionScope.current
    val animScope = LocalAnimatedVisibilityScope.current
    val sharedPosterKeyState = LocalSharedPosterKey.current
    // 4.8.16 first-tap fix: ALL posters with a slot are ALWAYS shared,
    // not only the tapped one. Before, a poster only became shared at
    // tap time (when sharedPosterKey changed), so on the FIRST tap
    // the framework had no starting bounds for it and teleported it
    // to detail — second tap worked because sharedPosterKey was
    // already that slot from the previous tap. Now every poster is
    // pre-registered with its unique slot key before any tap, so
    // the first tap flies like every later tap. Only the tapped
    // slot matches detail, others just slide out with home.
    val isSharedPoster = slot != null && sharedScope != null && animScope != null
    val isTappedPoster = slot != null && slot == sharedPosterKeyState
    // 4.8.16 tapped title fade: only for the tapped poster, on fresh
    // composition (after reverse, home recomposed, isTapped true at
    // start), title/meta fade in from below after 300ms landing,
    // not instantly. Before tap, isTapped false, title visible
    // immediately. During forward (outgoing), title already visible,
    // no change.
    var titleVisible by remember { mutableStateOf(!isTappedPoster) }
    LaunchedEffect(isTappedPoster) {
        if (isTappedPoster) {
            kotlinx.coroutines.delay(300)
            titleVisible = true
        }
    }
    val titleAlpha by animateFloatAsState(targetValue = if (titleVisible) 1f else 0f, animationSpec = tween(300, easing = FastOutSlowInEasing), label = "tappedTitleFade")
    val titleTransY = (1f - titleAlpha) * with(LocalDensity.current) { 16.dp.toPx() }
    @OptIn(ExperimentalSharedTransitionApi::class)
    val sharedContentState = if (sharedScope != null) {
        sharedScope.sharedState(slot ?: "unused-${title.id}")
    } else null
    Column(
        modifier = Modifier
            .width(cardWidth)
            .then(if (pressScale != 1f) Modifier.graphicsLayer(scaleX = pressScale, scaleY = pressScale) else Modifier)
            .onPlaced { coords ->
                coordsHolder[0] = coords
                // Reverse-only placement check: when home is freshly
                // composed on back, LaunchedEffect above runs before
                // placement (holder still null), so this is the
                // 4.8.8: also when this IS the reverse destination.
                // Home cards do not report on place normally (that
                // was the scroll jerk), but the ONE destination
                // card reporting once lets the overlay target
                // follow the real card if LazyColumn settles to a
                // different Y than the stale tap-time rect — that
                // stale-rect mismatch was the other snap source:
                // overlay landed at the old rect, real card was
                // elsewhere, swap snapped. safeBounds() catches
                // the deactivated-node crash, and only one card
                // does this, once, not every card per frame.
                if (reportBoundsOnPlace || isReverseHidden) safeBounds(coords)?.let { onBounds?.invoke(it) }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = {
                coordsHolder[0]?.let { c -> safeBounds(c)?.let { onBounds?.invoke(it) } }
                onClick()
            }),
    ) {
        Box(
            modifier = if (isSharedPoster && sharedScope != null && animScope != null && sharedContentState != null) {
                with(sharedScope) {
                    Modifier
                        .sharedElement(sharedContentState, animScope, boundsTransform = PosterBoundsTransform)
                        .alpha(cardAlpha)
                        .size(width = cardWidth, height = cardHeight)
                        .clip(RoundedCornerShape(if (slot != null && slot.startsWith("carousel-")) 20.dp else 16.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(if (slot != null && slot.startsWith("carousel-")) 20.dp else 16.dp))
                        .onPlaced { coords -> safeBounds(coords)?.let { onPosterBounds?.invoke(it) } }
                }
            } else Modifier
                .alpha(cardAlpha)
                .size(width = cardWidth, height = cardHeight)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .onPlaced { coords -> safeBounds(coords)?.let { onPosterBounds?.invoke(it) } },
        ) {
            PosterImage(title.poster, Modifier.fillMaxSize(), cardTargetPx)
            val topLabel = when {
                title.episodes != null -> "EP ${title.episodes}"
                title.score != null -> "★ ${title.score}"
                title.type != null -> title.type
                else -> null
            }
            if (topLabel != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(topLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Pink)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text("1080p", color = OnAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { notWired?.invoke() },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = "Download",
                    modifier = Modifier.size(18.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title.title, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, minLines = 2, lineHeight = 16.sp, modifier = Modifier.alpha(titleAlpha).graphicsLayer { translationY = titleTransY })
        Spacer(Modifier.height(2.dp))
        val meta = listOfNotNull(title.type, title.year).joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, color = Muted, fontSize = 11.sp, modifier = Modifier.alpha(titleAlpha).graphicsLayer { translationY = titleTransY })
    }
}

data class RowTag(val text: String, val solid: Boolean = false)

@Composable
fun SkeletonRow() {
    // 1.9-dev4.8.4: skeleton = the real card, in grey. The old one
    // was poster-only (118x177, corner 12, no title/meta), so when
    // real cards landed, every row grew ~56dp and everything below
    // jumped — JS: "actual positioning is completely different
    // from what the skeletons predicted". Now it matches PosterRow
    // exactly: heading (17dp icon + 17sp text, ~20dp tall), Spacer
    // 12, poster 118x177 corner 16, Spacer 8, title 2 lines
    // (12dp bars, Spacer 4), Spacer 2, meta bar 10dp. Same widths,
    // heights, spacings and corners, so a real row swaps in with
    // zero layout shift.
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(17.dp).clip(RoundedCornerShape(6.dp)).background(Card))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.width(150.dp).height(20.dp).clip(RoundedCornerShape(6.dp)).background(Card))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Column(modifier = Modifier.width(118.dp)) {
                    Box(Modifier.size(width = 118.dp, height = 177.dp).clip(RoundedCornerShape(16.dp)).background(Card))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Card))
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth(0.65f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Card))
                    Spacer(Modifier.height(2.dp))
                    Box(Modifier.fillMaxWidth(0.5f).height(10.dp).clip(RoundedCornerShape(6.dp)).background(Card))
                }
            }
        }
    }
}

@Composable
fun CarouselSkeleton() {
    // Home loading, first viewport: the carousel is the first big
    // item after the header, so a loading home without it jumps
    // 275dp + dots when it appears. Skeleton it too, same size.
    Column {
        Box(Modifier.fillMaxWidth().height(275.dp).clip(RoundedCornerShape(20.dp)).background(Card))
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Box(Modifier.size(width = 22.dp, height = 7.dp).clip(RoundedCornerShape(999.dp)).background(Card))
            Spacer(Modifier.width(8.dp))
            repeat(4) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(999.dp)).background(Card))
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
fun ExploreSkeletonRow(cardW: Dp, cols: Int = 3) {
    // Shimmer: a slow alpha pulse on the whole row, so it reads as
    // "loading" rather than a dead grey box. Only ever shown for
    // one row at a time, so it costs almost nothing.
    val shimmer = rememberInfiniteTransition(label = "exploreShimmer")
    val alpha by shimmer.animateFloat(initialValue = 0.45f, targetValue = 0.9f, animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "shimmerAlpha")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.alpha(alpha)) {
        repeat(cols) {
            Column(modifier = Modifier.width(cardW)) {
                Box(modifier = Modifier.size(width = cardW, height = cardW * 3 / 2).clip(RoundedCornerShape(16.dp)).background(Card))
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Card))
                Spacer(Modifier.height(4.dp))
                Box(modifier = Modifier.fillMaxWidth(0.65f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Card))
                Spacer(Modifier.height(2.dp))
                Box(modifier = Modifier.fillMaxWidth(0.5f).height(10.dp).clip(RoundedCornerShape(6.dp)).background(Card))
            }
        }
    }
}

@Composable
fun SearchSkeletonGrid() {
    // 1.9-dev4.7: search results are a 2-column grid, so the search
    // skeleton must be 2 per row too — the old SkeletonRow (3 across)
    // made the real results jump sideways when they landed.
    // Card height matches PosterCard: width * 3 / 2, plus title lines.
    androidx.compose.foundation.layout.BoxWithConstraints {
        val cardW = (maxWidth - 12.dp) / 2
        val cardH = cardW * 3 / 2
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(3) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(2) {
                        Column(modifier = Modifier.width(cardW)) {
                            Box(Modifier.size(width = cardW, height = cardH).clip(RoundedCornerShape(16.dp)).background(Card))
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Card))
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth(0.65f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Card))
                            Spacer(Modifier.height(2.dp))
                            Box(Modifier.fillMaxWidth(0.5f).height(10.dp).clip(RoundedCornerShape(6.dp)).background(Card))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PosterRow(heading: String, tags: List<RowTag>, titles: List<ApiTitle>, onOpen: (ApiTitle, String) -> Unit, notWired: (() -> Unit)? = null, iconRes: Int? = null, onBounds: ((ApiTitle, Rect) -> Unit)? = null, reverseHiddenId: Long? = null, reverseFade: Float = 1f) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (iconRes != null) {
                androidx.compose.foundation.Image(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Pink),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(heading, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = HeadingFont)
            tags.forEach { tag ->
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(999.dp))
                        .background(if (tag.solid) Color.White else Card)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) { Text(tag.text, color = if (tag.solid) Color.Black else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(titles, key = { it.id }) { PosterCard(it, { onOpen(it, "${heading}-${it.id}") }, notWired, onBounds = { rect -> onBounds?.invoke(it, rect) }, reverseHiddenId = reverseHiddenId, reverseFade = reverseFade, slot = "${heading}-${it.id}") }
        }
    }
}

@Composable
fun CarouselDot(selected: Boolean, onClick: () -> Unit) {
    // Selected dot stretches wide with a spring, instead of just
    // swapping size — a small motion cue that the carousel moved.
    val dotW by animateDpAsState(
        targetValue = if (selected) 22.dp else 7.dp,
        animationSpec = spring(stiffness = 400f, dampingRatio = 0.7f), label = "dotWidth",
    )
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(width = dotW, height = 7.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Pink else Color.White.copy(alpha = 0.3f))
            .clickable(onClick = onClick),
    )
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun EditorsPickCarousel(picks: List<ApiTitle>, onOpen: (ApiTitle, String) -> Unit, notWired: () -> Unit, autoScroll: Boolean = true, onBounds: ((ApiTitle, Rect) -> Unit)? = null, reverseHiddenId: Long? = null, reverseFade: Float = 1f, initialPage: Int = -1, onPageChange: ((Int) -> Unit)? = null) {
    // 1.9-dev4.8: Editor's pick is now Editor's PICKS, a 5-item
    // carousel, and the description lines are GONE altogether (JS's
    // call) — title + type pill + buttons only, over the poster.
    // Auto-scroll: one poster every 5s, manually swipeable too, and
    // it stops the moment it is fully off-screen (bounds check, not
    // a guess), so it never burns battery scrolling a carousel
    // nobody can see. Manual drag also pauses it — the auto timer
    // restarts from the page the user landed on.
    if (picks.isEmpty()) return
    // 1.9-dev4.8.2 infinite carousel: the pager is NOT 5 pages
    // that snap back left after the 5th — it is 5000 pages (5 picks
    // x 1000), starting in the middle, and every page maps to
    // picks[page % 5]. Auto-scroll always goes +1 (right), so it
    // never travels backwards, and the picks just keep repeating.
    // Dots show currentPage % 5, and a dot tap jumps to that pick's
    // NEXT occurrence to the right, never left.
    // 1.9-dev4.8.7-hotfix: pager page is hoisted to App, not
    // remembered inside the carousel. The carousel composable is
    // disposed while a detail is open (home list is gone), so a
    // local pagerState reset to page 500 (pick 0) on every back —
    // the reverse then flew to a slot holding a different pick,
    // and the opened pick's name/buttons jerked back into place
    // when the user swiped to it again. Hoisted, the carousel
    // resumes on the exact pick that was opened.
    val pagerState = rememberPagerState(initialPage = if (initialPage >= 0) initialPage else picks.size * 100, pageCount = { picks.size * 1000 })
    LaunchedEffect(pagerState.currentPage) { onPageChange?.invoke(pagerState.currentPage) }
    val scope = rememberCoroutineScope()
    var carouselVisible by remember { mutableStateOf(true) }
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }
    // 1.9-dev4.8.1 carousel fix: the old effect was keyed on
    // currentPage, and currentPage changes MID-animation (as soon as
    // the pager crosses halfway), which cancelled the effect and
    // therefore cancelled animateScrollToPage — so auto-scroll kept
    // stopping in the middle of two cards. Now it is a loop: delay,
    // animate fully, repeat. A manual drag just delays the next tick
    // (isScrollInProgress check), it never cancels an animation.
    // Reduce-motion also forces auto-scroll off.
    // 1.9-dev4.8.5: auto-scroll FREEZES while any detail is
    // open (screen != null, passed in via autoScroll at the call
    // site). Before, the carousel kept advancing in the background
    // under a detail, so on back the slot the reverse was flying
    // to already held a different pick (e.g. 3rd instead of 1st).
    // LaunchedEffect is keyed on autoOn, so opening a detail
    // cancels the 5s timer and closing it restarts it fresh — the
    // pager never jumps, it resumes from the same pick.
    val autoOn = autoScroll && !ThemeState.reduceMotion
    // 4.8.11: autoScrolling distinguishes an auto tick from a
    // manual drag — both set isScrollInProgress, only manual
    // gets the stacked-cards transform below. Auto must look
    // exactly like it does now, no stacking.
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(carouselVisible, autoOn) {
        if (!carouselVisible || !autoOn) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(5000)
            if (pagerState.isScrollInProgress) continue
            if (pagerState.currentPage >= picks.size * 1000 - 10) {
                // Invisible re-centre: same pick, middle of the pager.
                pagerState.scrollToPage(picks.size * 100 + pagerState.currentPage % picks.size)
            }
            autoScrolling = true
            try { pagerState.animateScrollToPage(pagerState.currentPage + 1, animationSpec = tween(600, easing = FastOutSlowInEasing)) } finally { autoScrolling = false }
        }
    }
    Column {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth()
                .onGloballyPositioned { coords ->
                    val r = safeBounds(coords) ?: return@onGloballyPositioned
                    carouselVisible = r.bottom > 0f && r.top < screenHeightPx
                },
        ) { page ->
            val pick = picks[page % picks.size]
            // 1.9-dev4.8.1: uniform, bigger cards. The old card height
            // was whatever its title wrapped to, so during a pager
            // transition one card looked much bigger than its neighbour
            // — JS liked that bigger one, so ALL cards are now that
            // size and a touch larger: a fixed 275dp, poster filling
            // the whole card, buttons pinned to the bottom. No more
            // tall/short cards side by side.
            // Tap-time bounds + press scale, same optimisation as
            // PosterCard: no per-frame global callbacks while scrolling.
            val cardCoords = remember { arrayOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
            val cInteraction = remember { MutableInteractionSource() }
            val cPressed by cInteraction.collectIsPressedAsState()
            val cScale by animateFloatAsState(
                targetValue = if (cPressed && !ThemeState.reduceMotion) 0.98f else 1f,
                animationSpec = spring(stiffness = 400f, dampingRatio = 0.7f), label = "carouselPress",
            )
            // 1.9-dev4.8.6-hotfix: slot-clear by ID (see below) —
            // the old position-match is gone, not just disabled.
            // 1.9-dev4.8.6-hotfix: same ID rule as PosterCard —
            // no bounds, no LaunchedEffect, no onPlaced state.
            // 1.9-dev4.8.7: same fade-in rule as PosterCard —
            // no empty slot, no pop, tap registers throughout.
            val cardAlpha = if (reverseHiddenId != null && pick.id == reverseHiddenId) reverseFade else 1f
            // 4.8.11 manual stacked cards: only while the user
            // is dragging by hand (isScrollInProgress, NOT an
            // auto tick). The card underneath the one being
            // swiped away sits slightly to the side, slightly
            // down, slightly smaller and faded — a deck, not a
            // flat strip. As it becomes current, it eases to
            // full size, full alpha, centred. Auto-scroll skips
            // all of this, so it still slides normally.
            val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            val absOff = kotlin.math.abs(pageOffset).coerceIn(0f, 1f)
            val isManual = pagerState.isScrollInProgress && !autoScrolling && !ThemeState.reduceMotion
            val stackScale = if (isManual) 0.92f + 0.08f * (1f - absOff) else 1f
            val stackAlpha = if (isManual) 0.65f + 0.35f * (1f - absOff) else 1f
            val stackTransX = if (isManual) pageOffset * with(density) { 24.dp.toPx() } else 0f
            val stackTransY = if (isManual) absOff * with(density) { 16.dp.toPx() } else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(275.dp)
                    .zIndex(if (isManual && absOff < 0.5f) 1f else 0f)
                    .graphicsLayer(scaleX = cScale * stackScale, scaleY = cScale * stackScale, alpha = stackAlpha * cardAlpha, translationX = stackTransX, translationY = stackTransY)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(20.dp))
                    .background(Color(0xFF14141F))
                    .onPlaced { coords ->
                        cardCoords[0] = coords
                    }
                    .clickable(interactionSource = cInteraction, indication = null) {
                        cardCoords[0]?.let { c -> safeBounds(c)?.let { onBounds?.invoke(pick, it) } }
                        onOpen(pick, "carousel-${pick.id}")
                    },
            ) {
                // 4.8.15 carousel shared poster, poster-only:
                // title/buttons/gradient stay in the card and slide
                // out with home, poster flies alone to detail.
                // Inactive until LocalShared* locals are provided
                // (Step 3), so old flight still works meanwhile.
                val carouselSharedScope = LocalSharedTransitionScope.current
                val carouselAnimScope = LocalAnimatedVisibilityScope.current
                val carouselSharedKey = LocalSharedPosterKey.current
                // 4.8.16 first-tap fix, same as PosterCard: carousel poster is ALWAYS shared, not only when tapped, so first tap has starting bounds and flies, not teleports.
                val isCarouselShared = carouselSharedScope != null && carouselAnimScope != null
                @OptIn(ExperimentalSharedTransitionApi::class)
                val carouselSharedState = if (carouselSharedScope != null) {
                    carouselSharedScope.sharedState("carousel-${pick.id}")
                } else null
                // 4.8.16 corners: shared PosterImage is clipped to 20dp (carousel card radius), INSIDE the shared element, so it never becomes a rectangle during flight, and detail poster for a carousel slot is also 20dp (see PosterCard), so no snap at landing — corners stay rounded throughout, morph is size/position only.
                val carouselPosterModifier = if (isCarouselShared && carouselSharedScope != null && carouselAnimScope != null && carouselSharedState != null) {
                    with(carouselSharedScope) { Modifier.sharedElement(carouselSharedState, carouselAnimScope, boundsTransform = PosterBoundsTransform).matchParentSize().clip(RoundedCornerShape(20.dp)) }
                } else Modifier.matchParentSize()
                PosterImage(pick.poster, carouselPosterModifier, with(LocalDensity.current) { config.screenWidthDp.dp.toPx().toInt() })
                // 1.9-dev4.8.4: reference-style card. No top pill
                // (no spotlight text in our data), no dark horizontal
                // gradient hiding the poster, no full-width buttons.
                // Only a bottom gradient for legibility, title at the
                // BOTTOM just above the buttons (so the poster's face
                // and middle stay unobstructed), and two compact
                // buttons in one row: Download (white, like the
                // reference Watch button, with our download icon, no
                // direct 1080p label) + Details (solid dark, info
                // icon, no glass blur).
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.78f))
                        )
                    )
                )
                // 4.8.16 carousel details fade: only for the tapped pick, on fresh composition (after reverse), details (title/meta/buttons) fade in from below after 300ms landing, not instantly. Before tap, details visible immediately. During forward (outgoing), details already visible, no change.
                val isTappedCarousel = "carousel-${pick.id}" == carouselSharedKey
                var detailsVisible by remember { mutableStateOf(!isTappedCarousel) }
                LaunchedEffect(isTappedCarousel) {
                    if (isTappedCarousel) {
                        kotlinx.coroutines.delay(300)
                        detailsVisible = true
                    }
                }
                val detailsAlpha by animateFloatAsState(targetValue = if (detailsVisible) 1f else 0f, animationSpec = tween(300, easing = FastOutSlowInEasing), label = "carouselDetailsFade")
                val detailsTransY = (1f - detailsAlpha) * with(density) { 16.dp.toPx() }
                Column(modifier = Modifier.fillMaxSize().padding(18.dp).alpha(detailsAlpha).graphicsLayer { translationY = detailsTransY }) {
                    Spacer(Modifier.weight(1f))
                    // 4.8.11: centre shadow on the title so it
                    // stays legible over any poster, plus, under
                    // it, rating + type and up to two genre tags
                    // — Nuvio-style info (see its VisionQuest
                    // card), but LEFT-aligned like ours, not
                    // centred like Nuvio's. Hidden gracefully
                    // when a field is missing (pre-enrich picks).
                    Text(pick.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 26.sp, fontFamily = HeadingFont, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), offset = Offset(0f, 2f), blurRadius = 10f)))
                    val metaParts = buildList {
                        if (pick.score != null) add("★ ${pick.score}")
                        if (pick.type != null) add(pick.type!!)
                    }
                    // 4.8.12 readability: format/genre/rating were
                    // gray or pink text straight over a bright
                    // poster — hard to read. Now every meta item
                    // sits in a dock-like translucent pill: dark
                    // translucent (Black 0.60) + white border +
                    // white text, legible over any poster, left-
                    // aligned like ours, not centred like Nuvio.
                    if (metaParts.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (pick.score != null) Box(modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = 0.60f)).border(1.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) { Text("★ ${pick.score}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            if (pick.type != null) Box(modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = 0.60f)).border(1.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) { Text(pick.type!!, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                    val genreTags = pick.genres?.split(",", "/")?.map { it.trim() }?.filter { it.isNotBlank() }?.take(2) ?: emptyList()
                    if (genreTags.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            genreTags.forEach { g ->
                                Box(modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = 0.60f)).border(1.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                                    Text(g, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = notWired,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 11.dp),
                        ) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(R.drawable.ic_download),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.Black),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Download", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Button(
                            onClick = {
                                // 4.8.11: Details used to open with
                                // NO bounds set (card tap sets them,
                                // this button didn't), so the flight
                                // fell back to a stale rect. Set them
                                // exactly like the card tap does.
                                cardCoords[0]?.let { c -> safeBounds(c)?.let { onBounds?.invoke(pick, it) } }
                                onOpen(pick, "carousel-${pick.id}")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A24)),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 11.dp),
                        ) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(R.drawable.ic_info),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Details", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            // 1.9-dev4.8.4, verified in NuvioMobile HomeHeroSection:
            // dots are 8dp tall, width = 8dp + 24dp * visibility,
            // alpha = 0.35 + 0.57 * visibility, colour = onBackground
            // (monochrome, not accent), Row spacedBy 8dp, clickable
            // to the nearest occurrence of that pick. Visibility =
            // 1 - distance from the pager position (currentPage +
            // offset fraction), wrapped for our infinite pager.
            val pagerPos = pagerState.currentPage + pagerState.currentPageOffsetFraction
            picks.indices.forEach { i ->
                val dist = kotlin.math.abs(pagerPos % picks.size - i).let { if (it > picks.size / 2f) picks.size - it else it }
                val weight = (1f - dist.coerceIn(0f, 1f))
                val dotW = 8.dp + (24.dp * weight)
                Box(
                    modifier = Modifier
                        .size(width = dotW, height = 8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(TextMain)
                        .alpha(0.35f + 0.57f * weight)
                        .clickable {
                            scope.launch {
                                var target = pagerState.currentPage - pagerState.currentPage % picks.size + i
                                if (target <= pagerState.currentPage) target += picks.size
                                pagerState.animateScrollToPage(target, animationSpec = tween(600, easing = FastOutSlowInEasing))
                            }
                        },
                )
            }
        }
    }
}


// ---- Finger trail, header only -------------------------------------------
// Behaviour only, borrowed from Nothing OS 3.0's hold-interact: hold your
// finger down, then drag in the SAME gesture — no lift, no button.
// Implementation: detectDragGesturesAfterLongPress on the header Box.
// A tap, or a drag that starts without a long-press, is cancelled by that
// detector, so normal taps, search typing and LazyColumn scrolling still
// work. The trail Canvas lives only inside this header Box, points are
// clamped to its height, and poster rows are separate LazyColumn items
// below it, so the trail cannot leak below the header. Background dots
// live in the top ~third of the header (the hero area), never piled up
// on the search pill.
private data class BgDot(val xFrac: Float, val yFrac: Float, val radius: Float, val color: Color, val phase: Float, val speed: Float, val drift: Float)
private data class TrailDot(val pos: Offset, val radius: Float)

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ParticleLayer(particlesOn: Boolean, bgDots: List<BgDot>, bgOffsets: List<Offset>, searchTopPx: Float?) {
    if (!particlesOn) return
    var animTime by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(50)
            animTime += 50
        }
    }
    Canvas(modifier = Modifier.matchParentSize()) {
        val w = size.width
        val h = size.height
        val searchTop = searchTopPx ?: h
        val t = animTime / 1000f
        bgDots.forEachIndexed { i, dot ->
            val driftX = kotlin.math.sin(t * dot.speed + dot.phase) * dot.drift
            val driftY = kotlin.math.cos(t * dot.speed * 0.8f + dot.phase) * dot.drift * 0.6f
            val twinkle = 0.5f + 0.5f * kotlin.math.sin(t * dot.speed * 1.5f + dot.phase * 2f)
            var pos = Offset(dot.xFrac * w, dot.yFrac * h) + Offset(driftX, driftY) + bgOffsets.getOrElse(i) { Offset.Zero }
            if (pos.y > searchTop - 8f) pos = Offset(pos.x, searchTop - 8f)
            // Light mode: white dots on a light background would be
            // invisible, so they become a soft grey-lavender instead.
            val dotColor = if (ThemeState.lightMode && dot.color == Color.White) Color(0xFFB8B8CC) else dot.color
            drawCircle(color = dotColor, radius = dot.radius * 3f, center = pos, alpha = 0.10f * twinkle)
            drawCircle(color = dotColor, radius = dot.radius, center = pos, alpha = 0.25f + 0.45f * twinkle)
        }
    }
}

@Composable
fun HeaderSection(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onChip: (String) -> Unit,
    live: Boolean,
    loadingHome: Boolean,
    onFocusChange: (Boolean) -> Unit,
    isSearching: Boolean,
    onPillPositioned: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit = {},
    particlesOn: Boolean = true,
    onCloseSearch: () -> Unit = {},
) {
    // No "Trail mode" text anywhere: the trail is an easter egg now.
    // Hold anywhere in the header, drag, lift to fade — nothing announces it.
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var pillFocused by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var trail by remember { mutableStateOf<List<TrailDot>>(emptyList()) }
    var fading by remember { mutableStateOf(false) }
    var headerSize by remember { mutableStateOf(IntSize.Zero) }
    var searchTopPx by remember { mutableStateOf<Float?>(null) }
    var bgOffsets by remember { mutableStateOf(List(48) { Offset.Zero }) }
    // 1.9-dev4.8.2: the 20fps particle ticker used to live HERE, in
    // HeaderSection, so every 50ms the WHOLE header (search pill,
    // chips, stats) recomposed — a fast-scroll jerk source. It now
    // lives in ParticleLayer below, whose only state is its own
    // ticker, so it recomposes just its Canvas.
    var lastFinger by remember { mutableStateOf<Offset?>(null) }
    var lastFingerMs by remember { mutableStateOf(0L) }
    val fadeAnim = remember { Animatable(1f) }
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current

    val bgDots = remember {
        val rnd = kotlin.random.Random(42)
        List(48) { i ->
            BgDot(
                xFrac = rnd.nextFloat(),
                yFrac = rnd.nextFloat() * 0.45f,
                radius = 2f + rnd.nextFloat() * 5f,
                color = if (i % 5 == 0) Pink else Color.White,
                phase = rnd.nextFloat() * 6.28f,
                speed = 0.6f + rnd.nextFloat() * 1.4f,
                drift = 6f + rnd.nextFloat() * 8f,
            )
        }
    }

    LaunchedEffect(armed, fading) {
        while (armed || fading || bgOffsets.any { it.getDistance() > 0.5f }) {
            kotlinx.coroutines.delay(32)
            bgOffsets = bgOffsets.map { off ->
                val decayed = off * 0.80f
                if (decayed.getDistance() < 0.5f) Offset.Zero else decayed
            }
        }
    }

    LaunchedEffect(fading) {
        if (fading) {
            fadeAnim.animateTo(0f, tween(350))
            trail = emptyList()
            lastFinger = null
            fading = false
            armed = false
            fadeAnim.snapTo(1f)
        }
    }

    fun pushDots(finger: Offset, width: Float) {
        bgOffsets = bgDots.mapIndexed { i, dot ->
            val home = Offset(dot.xFrac * width, dot.yFrac * headerSize.height.toFloat())
            val delta = (home + bgOffsets[i]) - finger
            val dist = delta.getDistance()
            if (dist in 0.01f..110f && dist > 0f) {
                val push = (110f - dist) / 110f * 18f
                val next = bgOffsets[i] + delta / dist * push
                if (next.getDistance() > 40f) next / next.getDistance() * 40f else next
            } else {
                bgOffsets[i]
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { headerSize = it }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        armed = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val start = Offset(
                            pos.x.coerceIn(0f, headerSize.width.toFloat()),
                            pos.y.coerceIn(0f, headerSize.height.toFloat()),
                        )
                        trail = listOf(TrailDot(start, 6f))
                        lastFinger = start
                        lastFingerMs = 0L
                        pushDots(start, headerSize.width.toFloat())
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val clamped = Offset(
                            change.position.x.coerceIn(0f, headerSize.width.toFloat()),
                            change.position.y.coerceIn(0f, headerSize.height.toFloat()),
                        )
                        pushDots(clamped, headerSize.width.toFloat())
                        val last = lastFinger
                        val prev = trail.lastOrNull()
                        if (last != null && prev != null) {
                            val dist = (clamped - last).getDistance()
                            if (dist >= 14f) {
                                val dt = (change.uptimeMillis - lastFingerMs).coerceAtLeast(1L)
                                val speed = dist / dt.toFloat()
                                val vel = (speed / 1.5f).coerceIn(0f, 1f)
                                val radius = 4f + vel * 6f + (trail.size % 3) * 1.5f
                                trail = (trail + TrailDot(clamped, radius)).takeLast(40)
                            }
                        }
                        lastFinger = clamped
                        lastFingerMs = change.uptimeMillis
                    },
                    onDragEnd = { fading = true },
                    onDragCancel = { fading = true },
                )
            },
    ) {
        ParticleLayer(particlesOn, bgDots, bgOffsets, searchTopPx)
        Canvas(modifier = Modifier.matchParentSize()) {
            // One continuous glowing line: a wide low-alpha pink stroke
            // for the glow, then a thin bright core stroke. Round caps
            // and joins so the ~14px-spaced points read as a line, not dots.
            if (trail.size >= 2) {
                val path = androidx.compose.ui.graphics.Path()
                path.moveTo(trail.first().pos.x, trail.first().pos.y)
                trail.drop(1).forEach { dot -> path.lineTo(dot.pos.x, dot.pos.y) }
                drawPath(
                    path = path, color = Pink, alpha = 0.35f * fadeAnim.value,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 28f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                )
                drawPath(
                    path = path, color = Color.White, alpha = 1f * fadeAnim.value,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 7f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                )
            } else {
                trail.firstOrNull()?.let {
                    drawCircle(color = Color.White, radius = 4f, center = it.pos, alpha = fadeAnim.value)
                }
            }
        }
        Column {
            // 1.9-dev4.7 search animation, Nuvio-style: ONE animation,
            // not stages. The whole hero block (logo, DOWNLOAD-ONLY
            // pill, Find it / Keep it offline, subheading) lives in a
            // single AnimatedVisibility with fade + expand/shrink, so
            // it collapses smoothly and the search pill rises to the
            // top in the same 320ms FastOutSlowIn motion — no staged
            // snap like the old dock bug. When it is collapsed, a
            // 28dp tappable strip sits above the pill: tap it and the
            // search UI closes, same as the device back button.
            if (isSearching) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                        .clickable {
                            onCloseSearch()
                            keyboard?.hide()
                            focusManager.clearFocus()
                        }
                )
            }
            AnimatedVisibility(
                visible = !isSearching,
                enter = fadeIn(tween(320, easing = FastOutSlowInEasing)) + expandVertically(tween(320, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(320, easing = FastOutSlowInEasing)) + shrinkVertically(tween(320, easing = FastOutSlowInEasing)),
            ) {
            Column {
            // 1.9-dev4.5: header lives here, at the very top of the
            // hero, so the gap JS circled (36dp list spacing + the
            // old 10dp + 24dp hero top spacers = ~70dp of dead space
            // between logo and the DOWNLOAD-ONLY pill) is gone.
            // Header -> pill is now just TopBar's 4dp padding + 12dp.
            TopBar()
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(999.dp))
                        .background(Card)
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(7.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFF34D399)))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "DOWNLOAD-ONLY · NO STREAMING · NO TRACKING",
                            color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Find it. Pull it.",
                color = TextMain, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, fontFamily = HeadingFont,
                letterSpacing = (-1).sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(), lineHeight = 37.sp,
            )
            Text(
                "Keep it offline.",
                color = Pink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, fontFamily = HeadingFont,
                letterSpacing = (-1).sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(), lineHeight = 37.sp,
            )
            Spacer(Modifier.height(13.dp))
            Text(
                "Search any anime, pick resolution, and pull clean direct-download links episode by episode or the whole season in one go.",
                color = Muted, fontSize = 15.sp, lineHeight = 23.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(22.dp))
            }
            }
            // Search pill, website size: 48dp tall, full width (no trail
            // button beside it any more). BasicTextField, not Material
            // TextField, so the pill is not inflated to 56dp+ by the
            // field's own padding, and the placeholder stays one line.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .onGloballyPositioned { searchTopPx = it.positionInParent().y; onPillPositioned(it) }
                    .border(
                        width = 1.dp,
                        color = if (pillFocused) Pink.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.09f),
                        shape = RoundedCornerShape(999.dp),
                    )
                    .background(SearchBg)
                    .padding(start = 15.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Faint),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = TextStyle(color = TextMain, fontSize = 15.sp),
                            cursorBrush = SolidColor(Pink),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                keyboard?.hide()
                                onSearchSubmit()
                            }),
                            modifier = Modifier.fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onFocusChanged { pillFocused = it.isFocused; onFocusChange(it.isFocused) },
                            decorationBox = { inner ->
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    if (query.isEmpty()) {
                                        Text(
                                            "Search anime...",
                                            color = Faint, fontSize = 15.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                    if (query.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        // Unmissable now: 32dp grey circle, 20dp icon in
                        // Muted (not faint), not a faint 16dp glyph.
                        // One tap clears the text, hides the keyboard and
                        // clears focus, so the home rows come straight back.
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Card)
                                .clickable {
                                    onQueryChange("")
                                    keyboard?.hide()
                                    focusManager.clearFocus()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "Clear search and return home",
                                modifier = Modifier.size(20.dp),
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Muted),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    // Fixed 38dp height, content centred: the button can
                    // no longer sit off-centre inside the 48dp pill.
                    Box(
                        modifier = Modifier
                            .height(38.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Pink)
                            .clickable {
                                keyboard?.hide()
                                focusManager.clearFocus()
                                onSearchSubmit()
                            }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Search", color = OnAccent, fontSize = 13.5.sp, fontWeight = FontWeight.Bold) }
                }
            }
            AnimatedVisibility(
                visible = !isSearching,
                enter = fadeIn(tween(320, easing = FastOutSlowInEasing)) + expandVertically(tween(320, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(320, easing = FastOutSlowInEasing)) + shrinkVertically(tween(320, easing = FastOutSlowInEasing)),
            ) {
                Column {
                    Spacer(Modifier.height(13.dp))
                    QuickChips(onChip = onChip)
                    Spacer(Modifier.height(28.dp))
                    StatsGrid(live, loadingHome)
                    Spacer(Modifier.height(12.dp))
                    SourceChips()
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun AnipullApp() {
    // HTML version's font is Outfit — bundle the same OFL family and make
    // every Text in the app use it by default, so the app reads like the site.
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalTextStyle provides androidx.compose.ui.text.TextStyle(fontFamily = OutfitFont)
    ) {
        AnipullAppInner()
    }
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun AnipullAppInner() {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<ApiTitle?>(null) }
    // 4.8.15: tapped poster's slot (e.g. "carousel-123", "Trending-456").
    // Set on tap, BEFORE screen changes, so AnimatedContent transition
    // renders both outgoing home poster and incoming detail poster with
    // the SAME shared key. Never cleared on back — reverse flight needs
    // the same key, and after transition only one content is present,
    // so a stale key is harmless (it just becomes a normal poster).
    var sharedPosterKey by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var sheetFor by remember { mutableStateOf<ApiTitle?>(null) }
    var liveSections by remember { mutableStateOf<List<HomeSection>?>(null) }
    var featured by remember { mutableStateOf<ApiTitle?>(null) }
    var homeFailed by remember { mutableStateOf(false) }
    var extraSections by remember { mutableStateOf<List<HomeSection>>(emptyList()) }
    var extraStarted by remember { mutableStateOf(false) }
    var isChipQuery by remember { mutableStateOf(false) }
    var liveSearchDone by remember { mutableStateOf(false) }
    // 1.9-dev4.8.6 scroll: these were MutableStates set from
    // onPlaced/onGloballyPositioned, i.e. on every scroll frame.
    // Nuvio's list has no placement state at all. Now they are
    // plain holders (not state) — the tap handler reads the
    // CURRENT holder value at tap time, so placement during
    // scroll costs zero recomposition and zero snapshot work.
    val pillCoordsHolder = remember { arrayOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val lazyColumnCoordsHolder = remember { arrayOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val homeKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var liveResults by remember { mutableStateOf<List<ApiTitle>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    // 1.9-dev2 states: Library tab, saved favourites, onboarding,
    // loading screen, and the morphing dock's shrink state.
    var showLibrary by remember { mutableStateOf(false) }
    var savedTitles by remember { mutableStateOf(loadLibraryTitles(context)) }
    var onboardingDone by remember {
        mutableStateOf(
            context.getSharedPreferences("anipull_onboarding", android.content.Context.MODE_PRIVATE)
                .getBoolean("done", false)
        )
    }
    // 1.9-dev4.6: loading screen EVERY launch, JS's call — it looks
    // nice, so it should not be skipped just because a cache exists.
    // Previously this was true only when there was no cache, so on
    // every relaunch the cached home appeared instantly and the
    // animation never showed.
    var showLoading by remember { mutableStateOf(true) }
    var fetchAttempt by remember { mutableStateOf(0) }
    // 1.9-dev4 dock state, Nuvio's NuvioNavBarScrollState verbatim in
    // spirit: a continuous labelVisibility fraction, 1f = expanded
    // (labels visible), 0f = collapsed (icons only), driven by ONE
    // accumulated nested-scroll delta — never by list indices.
    val dockFractionState = remember { mutableStateOf(1f) }
    var dockAccumulatedDelta by remember { mutableStateOf(0f) }
    var isSearchFocused by remember { mutableStateOf(false) }
    var lastLiveQuery by remember { mutableStateOf("") }
    // 1.9-dev4.8: Explore + hero transition + dock/appearance prefs.
    var exploreTitles by remember { mutableStateOf<List<ApiTitle>>(emptyList()) }
    var explorePage by remember { mutableStateOf(0) }
    var exploreLoading by remember { mutableStateOf(false) }
    var exploreFinished by remember { mutableStateOf(false) }
    var exploreStarted by remember { mutableStateOf(false) }
    val exploreSeq = listOf(1 to 1, 14 to 1, 5 to 1, 4 to 1, 3 to 1, 2 to 1, 1 to 2, 14 to 2, 5 to 2, 4 to 2, 3 to 2, 2 to 2)
    // Poster window bounds by title id, updated by PosterCard layout
    // (a plain map, not state, so scrolling never recomposes the app).
    val posterBounds = remember { mutableMapOf<Long, Rect>() }
    // 4.8.9-4.8.14 manual pageAnim/overlay model SUPERSEDED by 4.8.15 below.
    // 4.8.15 SharedTransition, from scratch: NO manual flight at all.
    // No pageAnim, no rect capture, no overlay, no hiding, no pre-measure.
    // Page slide + poster flight are ONE framework transition:
    // SharedTransitionLayout + AnimatedContent below, with
    // slideIn from right + slideOut to left, 300ms easeInOutCubic,
    // and poster sharedElement with PosterBoundsTransform (arc, 300ms).
    // AnimatedContent renders BOTH pages during the slide, so
    // home + detail always cover the screen — no gap, no black.
    // Poster size/position come from real layout in both pages,
    // so it cannot balloon. Spam-taps just flip AnimatedContent
    // targetState — framework interrupts and continues from current
    // value, never jumps, never clears state mid-flight.
    // pageHiddenId / pageHiddenFade are kept as constants ONLY
    // because existing PosterCard/Row/Carousel call sites still
    // pass them — they are always null / 1 now, SharedTransition
    // handles hiding in place during transition, not manual alpha.
    val pageHiddenId: Long? = null
    val pageHiddenFade = 1f
    // Carousel auto-scroll freeze during page transition: during
    // reverse, screen becomes null immediately, so the old
    // "screen == null" guard would let the carousel advance
    // mid-flight and lose the tapped slot. This flag stays true
    // for 350ms after every screen change (tap or back), freezing
    // the carousel for the whole 300ms transition + buffer.
    var pageTransitioning by remember { mutableStateOf(false) }
    LaunchedEffect(screen) {
        pageTransitioning = true
        kotlinx.coroutines.delay(350)
        pageTransitioning = false
    }
    var carouselPage by remember { mutableStateOf(500) }
    fun openTitle(t: ApiTitle, slot: String? = null, isCarousel: Boolean = false) {
        // 4.8.15: simple. Set shared key BEFORE screen, so both
        // outgoing home poster and incoming detail poster render
        // with the SAME key during AnimatedContent transition.
        // If this title is already open, do nothing.
        if (screen?.id == t.id) return
        if (slot != null) sharedPosterKey = slot
        else if (isCarousel) sharedPosterKey = "carousel-${t.id}"
        screen = t
    }
    fun goBackFromDetail() {
        // 4.8.15: just clear screen. AnimatedContent reverses:
        // detail slides out right, home slides in left, poster
        // flies back to its home slot (same shared key, kept).
        screen = null
    }
    val dockModeState = remember {
        mutableStateOf(
            context.getSharedPreferences("anipull_settings", android.content.Context.MODE_PRIVATE)
                .getString("dock_mode", "adaptive") ?: "adaptive"
        )
    }
    val hazeState = remember { HazeState() }
    // Same @Composable-modifier rule as the dock below: hazeSource
    // cannot live inside a Modifier.then() argument, so build it
    // here in the composable body and .then() the result.
    // 1.9-dev4.8.4 REVERT: dev4.8.3 turned haze OFF while
    // scrolling to save blur cost, but JS spotted it as a bug —
    // translucency visibly disappeared mid-scroll and popped back
    // when scrolling stopped. Dock stays translucent always now;
    // smoothness comes from hardware bitmaps / scroll-wait updates,
    // not from dropping the blur.
    val listState = rememberLazyListState()
    val translucentPref = context.getSharedPreferences("anipull_settings", android.content.Context.MODE_PRIVATE).getBoolean("translucent_dock", true)
    val dockTranslucent = translucentPref
    val hazeSourceModifier: Modifier = if (dockTranslucent && screen == null)
        Modifier.hazeSource(hazeState) else Modifier
    // 1.9-dev4.8.1: Show Explore toggle, re-read on every composition
    // (tab switches recompose this), so turning it off in Settings
    // hides Explore and un-gates the footer immediately.
    val showExplore = context.getSharedPreferences("anipull_settings", android.content.Context.MODE_PRIVATE).getBoolean("show_explore", true)
    val scope = rememberCoroutineScope()
    val appFocusManager = LocalFocusManager.current
    // 1.9-dev4.8.2: scrolling while search is open hides the
    // KEYBOARD ONLY — no clearFocus, no closeSearch, so the results
    // / "From your catalog" list stays exactly where it was and the
    // user can actually scroll it. This is the dev4.8 fix kept
    // (scroll never closes search) plus the keyboard part JS asked
    // for, without reintroducing the collapse.
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && isSearchFocused) homeKeyboard?.hide()
    }
    // 1.9-dev4.8 search fix (JS): scrolling must NOT close search.
    // The old LaunchedEffect cleared focus on any scroll, so scrolling
    // the cached catalog below the pill collapsed the whole search
    // UI. Now only a true tap on empty space closes it: the
    // LazyColumn tap detector below only fires on a tap (a drag /
    // scroll cancels it), and a poster tap is consumed by the
    // poster's own clickable, so tapping a poster opens its detail
    // instead of closing search. Back still works in two steps
    // exactly as before: the IME eats the first back (keyboard
    // closes), the second back runs closeSearch() to Home.
    val notWired: () -> Unit = {
        Toast.makeText(context, "Not wired yet — downloads come after the AniLab stage", Toast.LENGTH_SHORT).show()
    }

    // System back: sheet first, then settings/downloads/library,
    // then DETAIL -> home, then SEARCH -> home (1.9-dev4.7: search
    // is a mode, not an exit — but detail outranks it, or back from
    // a detail opened via search would clear the invisible search
    // instead of leaving the detail). Never straight out of the app.
    fun closeSearch() {
        query = ""
        liveResults = null
        searching = false
        isChipQuery = false
        homeKeyboard?.hide()
        appFocusManager.clearFocus()
        scope.launch { listState.scrollToItem(0) }
    }
    BackHandler(enabled = screen != null || sheetFor != null || showSettings || showDownloads || showLibrary || isSearchFocused || query.isNotBlank()) {
        if (sheetFor != null) sheetFor = null
        else if (showSettings) showSettings = false
        else if (showDownloads) showDownloads = false
        else if (showLibrary) showLibrary = false
        else if (screen != null) goBackFromDetail()
        else if (isSearchFocused || query.isNotBlank()) closeSearch()
    }

    // 1.9-dev4 dock scroll, copied from Nuvio's
    // NuvioNavBarScrollState.nestedScrollConnection: one accumulator,
    // 60px threshold, direction-change reset (a reversed frame
    // restarts accumulation in the new direction instead of
    // cancelling the old one). Scrolls never get consumed, so the
    // list itself is untouched — the dock only listens.
    val dockScrollConnection = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                val deltaY = available.y
                if (deltaY == 0f) return androidx.compose.ui.geometry.Offset.Zero
                if (dockModeState.value != "adaptive") return androidx.compose.ui.geometry.Offset.Zero
                dockAccumulatedDelta += deltaY
                if (dockAccumulatedDelta < -60f && dockFractionState.value != 0f) {
                    dockFractionState.value = 0f
                    dockAccumulatedDelta = 0f
                } else if (dockAccumulatedDelta > 60f && dockFractionState.value != 1f) {
                    dockFractionState.value = 1f
                    dockAccumulatedDelta = 0f
                }
                if (deltaY < 0f && dockAccumulatedDelta > 0f) dockAccumulatedDelta = deltaY
                if (deltaY > 0f && dockAccumulatedDelta < 0f) dockAccumulatedDelta = deltaY
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    fun toggleSave(title: ApiTitle) {
        savedTitles = if (savedTitles.any { it.id == title.id }) {
            savedTitles.filter { it.id != title.id }
        } else {
            savedTitles + title
        }
        saveLibraryTitles(context, savedTitles)
    }

    fun goTab(tab: Tab) {
        // 1.9-dev4.8.6-hotfix: clear any reverse here, in the tap
        // handler, not in a LaunchedEffect(currentTab) that can fire
        // mid-composition and leave reverse state half-cleared.
        screen = null
        showSettings = tab == Tab.Settings
        showDownloads = tab == Tab.Downloads
        showLibrary = tab == Tab.Library
        // Home = all three off, which is the Home branch below.
    }
    val currentTab: Tab = when {
        showLibrary -> Tab.Library
        showDownloads -> Tab.Downloads
        showSettings -> Tab.Settings
        else -> Tab.Home
    }
    LaunchedEffect(currentTab) {
        dockModeState.value = context.getSharedPreferences("anipull_settings", android.content.Context.MODE_PRIVATE)
            .getString("dock_mode", "adaptive") ?: "adaptive"
        // 1.9-dev4.8.6-hotfix: reverse clearing moved to goTab()
        // (synchronous, in the tap handler) — see above.
    }
    fun loadExploreNext() {
        if (exploreLoading || exploreFinished) return
        if (explorePage >= exploreSeq.size) { exploreFinished = true; return }
        exploreLoading = true
        scope.launch {
            val (cat, pg) = exploreSeq[explorePage]
            val sec = fetchCategoryPaged(cat, pg)
            if (sec == null) {
                exploreFinished = true
            } else {
                // 4.8.10 scroll lag: same rule as home/extra
                // sections — never apply a batch mid-scroll.
                // Explore used to land mid-fling, recomposing
                // the list + starting new poster decodes while
                // the user was still scrolling, which is part of
                // why scroll felt laggy until everything loaded.
                while (listState.isScrollInProgress) kotlinx.coroutines.delay(100)
                exploreTitles = (exploreTitles + sec.titles.take(9)).distinctBy { it.id }
                explorePage += 1
                if (explorePage >= exploreSeq.size) exploreFinished = true
            }
            exploreLoading = false
        }
    }
    // 1.9-dev4.8.3: Explore is NO LONGER started the moment home
    // lands. It used to fire 12 category fetches + poster loads in
    // parallel with the home fetch, right when the user starts
    // their first fast scroll. Now it starts only when the Explore
    // item itself composes (i.e. the user actually scrolls near
    // it) — see the Explore item below.
    // Dynamic home: (1) cached live home is loaded into the rows
    // immediately if there is one, (2) a progressive refresh updates
    // each row the moment it lands, (3) demo titles appear ONLY if
    // the fetch actually fails and there is no cache — never as a
    // loading placeholder. 1.9-dev2: keyed on fetchAttempt so the
    // Retry button on a failed load actually re-runs this.
    // 1.9-dev4.7 loading rule (JS, 2026-11-03): HARD cap, now 1.75s
    // (dev4.8, JS's call — was 1.5s).
    // The old dev4.6 rule was max(fetch time, 1500ms) — a slow
    // AniLab fetch (51 calls, slowest section wins) stretched it to
    // 4.5-5s. Now the loading screen ends at 1500ms NO MATTER WHAT,
    // and the fetch keeps running in the background: cached rows show
    // instantly, unloaded rows show skeletons, fresh sections swap in
    // progressively as they land. First launch with no cache shows
    // skeletons, never demo titles. The timer only starts once
    // onboarding is done, so first-boot users still see the full
    // animation instead of it being eaten by onboarding time.
    LaunchedEffect(onboardingDone) {
        if (!onboardingDone) return@LaunchedEffect
        // 4.8.16 loading range: min 1.75s (animation plays), max 2.0s (hard cap, NOT 1.75). Between 1.75 and 2.0, wait up to 250ms more for at least one home poster to be on disk (preload below runs during loading), then show home anyway at 2.0s total, even if posters are still loading.
        kotlinx.coroutines.delay(1750)
        val deadline = System.currentTimeMillis() + 250
        while (System.currentTimeMillis() < deadline) {
            val anyPosterOnDisk = liveSections?.flatMap { it.titles }?.any { it.poster != null && isPosterOnDisk(it.poster!!, context) } == true
            if (anyPosterOnDisk) break
            kotlinx.coroutines.delay(50)
        }
        showLoading = false
    }
    // 4.8.16 poster preload during loading: home fetch runs regardless of showLoading, so sections land DURING the loading screen. The moment they land, start downloading the first posters (featured + first section + second section) to disk cache, so when home appears, PosterImage finds them on disk and loads instantly, instead of starting network fetches after home appears (which was the scroll-lag-while-loading cause). Preload is background, cancellable on next sections landing.
    LaunchedEffect(liveSections, featured) {
        val urls = (listOfNotNull(featured?.poster) + (liveSections?.firstOrNull()?.titles?.take(10)?.mapNotNull { it.poster } ?: emptyList()) + (liveSections?.getOrNull(1)?.titles?.take(5)?.mapNotNull { it.poster } ?: emptyList())).distinct()
        urls.forEach { url ->
            launch {
                try { loadPoster(url, context, 0) } catch (_: Exception) { }
            }
        }
    }
    LaunchedEffect(fetchAttempt) {
        homeFailed = false
        val wanted = listOf("Spotlight", "Trending", "Most Popular", "Top Airing", "Most Watched", "Most Favorite", "Top 10 Today", "Top 10 Week", "Top 10 Month")
        val cached = loadHomeCache(context)
        val localSections = cached?.sections?.toMutableList() ?: mutableListOf()
        var localFeatured = cached?.featured
        if (cached != null) {
            liveSections = localSections.sortedBy { wanted.indexOf(it.name) }
            featured = localFeatured
        }
        // Child of this effect (not scope.launch): it keeps running
        // after this block returns, but a Retry cancels the old fetch
        // instead of letting two fetches race on liveSections.
        // 1.9-dev4.8.4: sections landing mid-scroll were a real
        // initial-scroll jank source (20 updates in the first
        // seconds, each recomposing the list while it moves).
        // A landing section now waits (on its IO job, not the
        // main thread) until the scroll stops, then applies —
        // so updates never recompose the list mid-fling.
        launch {
            fetchHomeProgressive(
                onSection = { _, sec ->
                    while (listState.isScrollInProgress) kotlinx.coroutines.delay(100)
                    val existing = localSections.indexOfFirst { it.name == sec.name }
                    if (existing >= 0) localSections[existing] = sec else localSections += sec
                    liveSections = localSections.sortedBy { wanted.indexOf(it.name) }
                },
                onFeatured = { localFeatured = it; featured = it },
            )
            val ordered = localSections.sortedBy { wanted.indexOf(it.name) }
            if (ordered.isEmpty() && localFeatured == null) {
                homeFailed = true
            } else {
                withContext(Dispatchers.IO) { saveHomeCache(context, HomeData(ordered, localFeatured)) }
            }
        }
    }

    fun submitSearch(q: String) {
        appFocusManager.clearFocus()
        isChipQuery = false
        if (q.isBlank()) {
            liveResults = null
            searching = false
            return
        }
        searching = true
        scope.launch {
            val results = searchAniLab(q)
            liveResults = if (results.isNotEmpty()) results else null
            liveSearchDone = true
            searching = false
        }
    }

    // Extra genre catalogs: fetch once, in parallel, when live home lands.
    LaunchedEffect(liveSections) {
        if (liveSections != null && !extraStarted) {
            extraStarted = true
            scope.launch {
                kotlinx.coroutines.delay(1500)
                val genres = listOf(1 to "Action anime", 14 to "Romance anime", 5 to "Comedy anime", 4 to "Fantasy anime", 2 to "Drama anime", 3 to "Adventure anime", 6 to "Sci-Fi anime", 7 to "Mystery anime", 9 to "Slice of Life anime", 10 to "Supernatural anime", 11 to "Sports anime")
                val results = genres.map { (id, name) ->
                    async { fetchCategory(id, name) }
                }.mapNotNull { runCatching { it.await() }.getOrNull() }
                while (listState.isScrollInProgress) kotlinx.coroutines.delay(100)
                if (results.isNotEmpty()) extraSections = results
            }
        }
    }

    // Live search as you type — rate-limited, not per keystroke:
    // 500ms debounce (LaunchedEffect restarts + cancels the in-flight
    // wait on every keystroke, so a fast typer fires ONE call, not
    // one per letter), distinct-query guard (same query never
    // re-fires), and the network call itself is cancelled when the
    // query changes again. No searching=true on keystroke any more:
    // that flashed the skeleton on every letter. The skeleton only
    // shows when there are no results yet at all.
    LaunchedEffect(query, isChipQuery) {
        if (query.isBlank() || isChipQuery) return@LaunchedEffect
        if (query == lastLiveQuery) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        searching = true
        liveSearchDone = false
        val results = searchAniLab(query)
        lastLiveQuery = query
        liveResults = if (results.isNotEmpty()) results else null
        liveSearchDone = true
        searching = false
    }

    // 1.9-dev2 rendering: onboarding first (fetch already runs above),
    // then the loading screen, then the tab screens with the floating
    // dock overlaid, then Detail on top with no dock.
    // Chip queries now filter LIVE titles by type — no demo fallback
    // anywhere in search or chips any more.
    val liveChipResults = if (query.isBlank()) emptyList() else run {
        val allLive = (liveSections?.flatMap { it.titles } ?: emptyList()) + extraSections.flatMap { it.titles }
        val wantedTypes = when (query) {
            "Movies", "Movie" -> listOf("Movie")
            "Series" -> listOf("TV")
            "OVA" -> listOf("OVA", "ONA")
            "Specials" -> listOf("Special", "Specials")
            else -> null
        }
        if (wantedTypes == null) emptyList()
        else allLive.filter { it.type in wantedTypes }.distinctBy { it.id }
    }

    if (!onboardingDone) {
        OnboardingScreen(onFinish = {
            context.getSharedPreferences("anipull_onboarding", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("done", true).apply()
            onboardingDone = true
        })
    } else if (showLoading) {
        LoadingScreen()
    } else SharedTransitionLayout {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                // 4.8.16 directional: forward (target = detail, non-null) slides in from right / out to left, as before. Reverse (target = home, null) MIRRORS: slides in from left / out to right, NOT same direction as forward — that was the infinite-scroll bug (detail went left, home came from right, on back too).
                if (targetState == null) {
                    slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(300, easing = androidx.compose.animation.core.CubicBezierEasing(0.65f, 0f, 0.35f, 1f))) togetherWith slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300, easing = androidx.compose.animation.core.CubicBezierEasing(0.65f, 0f, 0.35f, 1f)))
                } else {
                    slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300, easing = androidx.compose.animation.core.CubicBezierEasing(0.65f, 0f, 0.35f, 1f))) togetherWith slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(300, easing = androidx.compose.animation.core.CubicBezierEasing(0.65f, 0f, 0.35f, 1f)))
                }
            },
            label = "pageSlide",
        ) { targetScreen ->
            CompositionLocalProvider(
                LocalSharedTransitionScope provides this@SharedTransitionLayout,
                LocalAnimatedVisibilityScope provides this@AnimatedContent,
                LocalSharedPosterKey provides sharedPosterKey,
            ) {
                if (targetScreen == null) {
                    Box(modifier = Modifier.fillMaxSize().background(Bg)) {
    // Haze source: the whole tab content behind the dock, so the
    // translucent dock (when enabled) blurs real posters, not a
    // solid colour. The dock itself sits outside this inner Box.
    Box(modifier = Modifier.fillMaxSize().then(hazeSourceModifier)) {
    if (showSettings) {
        SettingsScreen(onBack = { goTab(Tab.Home) })
    } else if (showDownloads) {
        DownloadsScreen(onBack = { goTab(Tab.Home) }, onBrowse = { goTab(Tab.Home) })
    } else if (showLibrary) {
        LibraryScreen(
            saved = savedTitles,
            onOpen = { t, slot -> openTitle(t, slot) },
            onBrowseHome = { goTab(Tab.Home) },
            onRemove = { toggleSave(it) },
        )
    } else {
        // 1.9-dev4.8.9: Home is ALWAYS mounted, even while a
        // detail page slides over it — that is how shonenx
        // reveals home underneath a sliding-out detail page,
        // instead of home popping in at the end.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Bg)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .nestedScroll(dockScrollConnection)
                .onGloballyPositioned { lazyColumnCoordsHolder[0] = it }
                .pointerInput(isSearchFocused) {
                    // 1.9-dev4.8.6 scroll fix: this handler used to be
                    // keyed on pillCoords + lazyColumnCoords, both of
                    // which change every frame while the header scrolls
                    // — so the tap detector RESTARTED on every scroll
                    // frame during a fling. Now it reads coords from
                    // plain holders (stable identity, current value),
                    // so it only restarts when search focus changes,
                    // and placement during scroll sets no state at all.
                    detectTapGestures(onTap = { offset ->
                        if (isSearchFocused) {
                            val lc = lazyColumnCoordsHolder[0]
                            val pc = pillCoordsHolder[0]
                            val b = try { lc?.let { l -> pc?.let { p -> l.localBoundingBoxOf(p) } } } catch (e: IllegalStateException) { null } catch (e: IllegalArgumentException) { null }
                            if (b == null || !b.contains(offset)) {
                                homeKeyboard?.hide()
                                appFocusManager.clearFocus()
                            }
                        }
                    })
                },
            verticalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            // 1.9-dev4.5: no separate header item at all — TopBar
            // lives inside HeaderSection (below), so the LazyColumn's
            // 36dp spacedBy never lands between header and hero.
            item(key = "home_header", contentType = "header") {
                HeaderSection(
                    query = query,
                    onQueryChange = { query = it; isChipQuery = false; if (it.isBlank()) { liveResults = null; searching = false } },
                    onSearchSubmit = { submitSearch(query) },
                    onChip = { chip ->
                        query = chip
                        isChipQuery = true
                        liveResults = null
                        searching = false
                        appFocusManager.clearFocus()
                    },
                    live = liveSections != null,
                    loadingHome = liveSections == null && !homeFailed,
                    onFocusChange = { isSearchFocused = it },
                    isSearching = isSearchFocused || query.isNotBlank(),
                    onPillPositioned = { pillCoordsHolder[0] = it },
                    onCloseSearch = { closeSearch() },
                    particlesOn = LocalContext.current.getSharedPreferences("anipull_settings", android.content.Context.MODE_PRIVATE).getBoolean("particles", true) && !listState.isScrollInProgress,
                )
            }
            if (query.isNotBlank()) {
                item(key = "home_search_results", contentType = "search") {
                    Text(
                        if (searching) "Searching AniLab…" else "Search results",
                        color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = HeadingFont,
                    )
                    Spacer(Modifier.height(10.dp))
                    // 1.9-dev2: live only. Chip queries filter live
                    // titles by type; a manual query with no live
                    // results is "Nothing found" — no demo fallback.
                    val results = when {
                        liveResults != null -> liveResults!!
                        isChipQuery -> liveChipResults
                        searching -> emptyList()
                        else -> emptyList()
                    }
                    if (results.isEmpty() && !searching) {
                        Text("Nothing found — try another title.", color = Muted, fontSize = 13.sp)
                    } else if (searching && results.isEmpty()) {
                        SearchSkeletonGrid()
                    } else {
                        // HTML results grid: two columns on a phone.
                        // Cards fill the full content width — the old fixed
                        // 164dp cards left an awkward empty strip on the
                        // right on wider phones (e.g. 411dp wide).
                        androidx.compose.foundation.layout.BoxWithConstraints {
                            val cardW = (maxWidth - 12.dp) / 2
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                results.chunked(2).forEach { pair ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        pair.forEach { t ->
                                            PosterCard(t, onClick = { openTitle(t, "search-${t.id}") }, notWired = notWired, cardWidth = cardW, onBounds = { rect -> posterBounds[t.id] = rect }, reverseHiddenId = pageHiddenId, reverseFade = pageHiddenFade, slot = "search-${t.id}")
                                        }
                                        if (pair.size == 1) Spacer(Modifier.width(cardW))
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (isSearchFocused) {
                // 1.9-dev4.7: focused with an empty query is not an
                // empty screen any more — show cached posters below
                // the pill instantly (they are already on the phone
                // from the home cache + poster disk cache, so this
                // costs zero network and zero wait). No footer here.
                item(key = "home_cached", contentType = "search") {
                    val cachedTitles = (liveSections?.flatMap { it.titles } ?: emptyList()).distinctBy { it.id }
                    if (cachedTitles.isEmpty()) {
                        Text("Type to search AniLab…", color = Faint, fontSize = 13.sp)
                        Spacer(Modifier.height(14.dp))
                        SearchSkeletonGrid()
                    } else {
                        Text("From your catalog", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = HeadingFont)
                        Spacer(Modifier.height(10.dp))
                        androidx.compose.foundation.layout.BoxWithConstraints {
                            val cardW = (maxWidth - 12.dp) / 2
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                cachedTitles.take(12).chunked(2).forEach { pair ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        pair.forEach { t ->
                                            PosterCard(t, onClick = { openTitle(t, "cached-${t.id}") }, notWired = notWired, cardWidth = cardW, onBounds = { rect -> posterBounds[t.id] = rect }, reverseHiddenId = pageHiddenId, reverseFade = pageHiddenFade, slot = "cached-${t.id}")
                                        }
                                        if (pair.size == 1) Spacer(Modifier.width(cardW))
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (liveSections != null) {
                val spotlight = liveSections!!.firstOrNull { it.name == "Spotlight" }
                if (spotlight != null) {
                    item(key = "home_weekend", contentType = "row") { PosterRow("Weekend marathon picks", emptyList(), spotlight.titles.take(5), { t, slot -> openTitle(t, slot) }, notWired, R.drawable.ic_calendar, onBounds = { t, rect -> posterBounds[t.id] = rect }, reverseHiddenId = pageHiddenId, reverseFade = pageHiddenFade) }
                }
                featured?.let { pick ->
                    item(key = "home_carousel", contentType = "carousel") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(R.drawable.ic_star),
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Pink),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Editor's picks", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = HeadingFont)
                        }
                        Spacer(Modifier.height(12.dp))
                        val picks = (listOfNotNull(featured) + (liveSections!!.firstOrNull { it.name == "Spotlight" }?.titles ?: emptyList()))
                            .distinctBy { it.id }.take(5)
                        EditorsPickCarousel(picks, { t, slot -> openTitle(t, slot, isCarousel = true) }, notWired, autoScroll = context.getSharedPreferences("anipull_settings", android.content.Context.MODE_PRIVATE).getBoolean("carousel_auto", true) && !listState.isScrollInProgress && screen == null && !pageTransitioning, onBounds = { t, rect -> posterBounds[t.id] = rect }, reverseHiddenId = pageHiddenId, reverseFade = pageHiddenFade, initialPage = carouselPage, onPageChange = { carouselPage = it })
                    }
                }
                liveSections!!.filter { it.name != "Spotlight" }.forEach { section ->
                    val icon = when (section.name) {
                        "Spotlight" -> R.drawable.ic_star
                        "Trending" -> R.drawable.ic_flame
                        "Most Popular" -> R.drawable.ic_chart
                        "Top Airing" -> R.drawable.ic_film
                        "Most Watched" -> R.drawable.ic_eye
                        "Most Favorite" -> R.drawable.ic_star
                        "Top 10 Today" -> R.drawable.ic_trophy
                        "Top 10 Week" -> R.drawable.ic_trophy
                        "Top 10 Month" -> R.drawable.ic_trophy
                        else -> null
                    }
                    item(key = "home_section_${section.name}", contentType = "row") { PosterRow(section.name, emptyList(), section.titles, { t, slot -> openTitle(t, slot) }, notWired, icon, onBounds = { t, rect -> posterBounds[t.id] = rect }, reverseHiddenId = pageHiddenId, reverseFade = pageHiddenFade) }
                }
            } else if (homeFailed) {
                // 1.9-dev2: failed load = skeletons + retry, never demo
                // titles pretending to be a catalog.
                item(key = "home_failed", contentType = "error") {
                    Text("Couldn't load catalogs.", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Check your connection and try again — no demo titles are shown in place of the live feed.", color = Muted, fontSize = 13.5.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Pink)
                            .clickable { fetchAttempt += 1 }.padding(horizontal = 20.dp, vertical = 11.dp),
                    ) { Text("Retry", color = OnAccent, fontSize = 13.5.sp, fontWeight = FontWeight.Bold) }
                }
                item(key = "home_skeleton_0", contentType = "skeleton") { SkeletonRow() }
                item(key = "home_skeleton_1", contentType = "skeleton") { SkeletonRow() }
            } else {
                // First load, no cache, fetch still running: skeletons, not demo.
                // 1.9-dev4.8.4: include the carousel skeleton first —
                // without it, the real carousel appearing pushed every
                // row below it down 292dp.
                item(key = "home_carousel_skeleton", contentType = "skeleton") { CarouselSkeleton() }
                item(key = "home_skeleton_0", contentType = "skeleton") { SkeletonRow() }
                item(key = "home_skeleton_1", contentType = "skeleton") { SkeletonRow() }
                item(key = "home_skeleton_2", contentType = "skeleton") { SkeletonRow() }
            }
            // Extra catalogs, below the AniLab home rows: genre rows from
            // /api/category (Action 1, Romance 14, Comedy 5, Fantasy 4).
            // They load progressively in the background, like home rows.
            if (!isSearchFocused && query.isBlank() && liveSections != null) {
                extraSections.forEach { section ->
                    item(key = "home_extra_${section.name}", contentType = "row") { PosterRow(section.name, emptyList(), section.titles, { t, slot -> openTitle(t, slot) }, notWired, null, onBounds = { t, rect -> posterBounds[t.id] = rect }, reverseHiddenId = pageHiddenId, reverseFade = pageHiddenFade) }
                }
            }
            // 1.9-dev4.8: Explore — after all catalogs, a grid (not a
            // horizontal row): 3 fixed posters per row, row after row.
            // It loads page by page as the user scrolls (12 pages of
            // AniLab categories, 10 titles each, distinct), and only
            // when it finally stops does the footer below appear.
            // This is what the Downloads "Browse anime" button points
            // at: home no longer has only a handful of picks.
            if (!isSearchFocused && query.isBlank() && liveSections != null && showExplore) {
                item(key = "home_explore", contentType = "explore") {
                    // First composition of Explore = user scrolled
                    // near it, so now (and only now) start loading.
                    LaunchedEffect(Unit) { if (!exploreStarted) { exploreStarted = true; loadExploreNext() } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(R.drawable.ic_grid),
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Pink),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Explore", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = HeadingFont)
                    }
                    Spacer(Modifier.height(12.dp))
                    // 1.9-dev4.8.5: Explore columns are a setting
                    // (2/3/4, default 3). The initial skeleton used
                    // to be SearchSkeletonGrid (2 per row) while the
                    // real grid is 3 per row — that was the jump JS
                    // screenshotted on a fast scroll. Now the initial
                    // skeleton uses the SAME column count as the grid.
                    val exploreCols = context.getSharedPreferences("anipull_settings", android.content.Context.MODE_PRIVATE).getInt("explore_columns", 3).coerceIn(2, 4)
                    if (exploreTitles.isEmpty() && exploreLoading) {
                        androidx.compose.foundation.layout.BoxWithConstraints {
                            val skelW = (maxWidth - 8.dp * (exploreCols - 1)) / exploreCols
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                repeat(3) { ExploreSkeletonRow(skelW, exploreCols) }
                            }
                        }
                    } else {
                        androidx.compose.foundation.layout.BoxWithConstraints {
                            val cardW = (maxWidth - 8.dp * (exploreCols - 1)) / exploreCols
                            Column(
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                // 1.9-dev4.8.2: only COMPLETE rows are
                                // shown while loading, so a row never
                                // appears with 1 card while the other
                                // 2 are still fetching. The remainder
                                // stays buffered until the next batch
                                // completes it, or until Explore
                                // finishes, when the last partial row
                                // is finally shown.
                                // 1.9-dev4.8.4: ALWAYS full rows, even when
                                // finished. The old code showed the last
                                // partial row once Explore finished, so
                                // it ended with a lone first card. Now
                                // that trailing 1-2 cards are dropped
                                // for good — the bottom line is always
                                // a full 3-card row.
                                val shownExplore = exploreTitles.dropLast(exploreTitles.size % exploreCols)
                                shownExplore.chunked(exploreCols).forEach { trio ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        trio.forEach { t ->
                                            PosterCard(t, onClick = { openTitle(t, "explore-${t.id}") }, notWired = notWired, cardWidth = cardW, onBounds = { rect -> posterBounds[t.id] = rect }, reverseHiddenId = pageHiddenId, reverseFade = pageHiddenFade, slot = "explore-${t.id}")
                                        }
                                        repeat(exploreCols - trio.size) { Spacer(Modifier.width(cardW)) }
                                    }
                                }
                                // 1.9-dev4.8.2: while the next batch
                                // (exactly 3 cards, one row) is loading,
                                // show 3 skeleton cards + title text
                                // in its place, so the user sees the
                                // row coming instead of a bare
                                // "Loading more…" line.
                                if (exploreLoading && !exploreFinished) {
                                    ExploreSkeletonRow(cardW, exploreCols)
                                }
                            }
                        }
                    }
                }
                if (!exploreFinished) {
                    item(key = "home_explore_more", contentType = "explore_more") {
                        // Composed only when the user reaches the bottom:
                        // that composition is the scroll trigger for the
                        // next page. The footer stays hidden until the
                        // last page, so it truly appears at the end.
                        LaunchedEffect(explorePage) {
                            kotlinx.coroutines.delay(300)
                            loadExploreNext()
                        }
                        if (exploreLoading) {
                            Text("Loading more…", color = Faint, fontSize = 13.sp)
                        } else {
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                } else {
                    item(key = "home_explore_done", contentType = "explore_done") {
                        Text("That's the whole Explore catalog for now — back to the top for the picks above, or pull something below.", color = Faint, fontSize = 13.sp)
                    }
                }
            }
            // 1.9-dev4.7: footer (the Anipull about section) is
            // HOME ONLY. It used to sit at the end of every list, so
            // it stayed visible when the search box was tapped
            // (short focused list) and while results were fetching
            // (short skeleton list) — both screenshots JS circled.
            if (!isSearchFocused && query.isBlank() && !searching && (liveSections == null || exploreFinished || !showExplore)) item(key = "home_footer", contentType = "footer") {
                Column {
                    LogoRow()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Download-only anime. Anipull indexes direct-download sources and resolves links — it doesn't host files, stream video, or track what you pull.",
                        color = Muted, fontSize = 13.sp, lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Sources: AnimePahe · AnimeTosho · AnimeOut", color = Faint, fontSize = 12.sp)
                    // Extra bottom space so the floating dock never
                    // covers the footer.
                    Spacer(Modifier.height(100.dp))
                }
            }
        }
    }
    } // close Haze source Box
    // Floating dock, always rendered now — the detail
    // page (sibling, on top, opaque) covers it while open,
    // so it is revealed during slide-out, not popped in after.
    if (true) {
        Box(
            modifier = Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding().padding(bottom = 12.dp, start = 8.dp, end = 8.dp),
        ) {
            // The morph state is GLOBAL, like Nuvio's: switching tabs
            // never resets it. Mini stays mini on the new tab, and
            // the selected pill travels there (see FloatingDock).
            // Dev3's bug was forcing fraction = 1f off Home.
            val dockFraction = when (dockModeState.value) {
                "expanded" -> 1f
                "compact" -> 0f
                else -> dockFractionState.value
            }
            FloatingDock(
                current = currentTab,
                fraction = dockFraction,
                onTab = { goTab(it) },
                translucent = dockTranslucent && screen == null,
                hazeState = hazeState,
            )
        }
    }
                    } // close Tab Box
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Bg)) {
                        DetailScreen(
                            title = targetScreen,
                            onBack = { goBackFromDetail() },
                            onPull = { sheetFor = it },
                            isSaved = savedTitles.any { it.id == targetScreen.id },
                            onSaveToggle = { toggleSave(targetScreen) },
                        )
                    }
                }
            }
        }
    }
    // 4.8.15: pre-measure DELETED. SharedTransition uses real layout
    // in both pages, no dest rect capture, no hidden DetailScreen,
    // no pendingTitle — that whole mechanism was the snap source.

    sheetFor?.let { title ->
        DownloadSheet(title = title, onDismiss = { sheetFor = null }, notWired = notWired)
    }
}

// ---- 1.9-dev2: floating dock, Library, loading, onboarding ------------

enum class Tab { Home, Library, Downloads, Settings }

@Composable
@OptIn(ExperimentalHazeApi::class, ExperimentalHazeMaterialsApi::class)
fun FloatingDock(current: Tab, fraction: Float, onTab: (Tab) -> Unit, translucent: Boolean = false, hazeState: HazeState? = null) {
    // 1.9-dev4: Nuvio's FloatingNavigationBar, adaptive style, glow
    // OFF — read in full from NuvioMobile (NavigationBar.kt +
    // FloatingNavigationBar.android.kt) and mirrored, not approximated:
    //
    // - Morph is ONE continuous tween, never stages. The pill does
    //   NOT animate its own width: the outer horizontal padding does
    //   (58dp collapsed → 28dp expanded, i.e. 58 - 30*fraction) and
    //   the track height does (48dp + 16dp*fraction, 48 → 64).
    //   Dev3's staging bug was the label: it was dropped entirely
    //   below fraction 0.02, so mid-morph the column height jumped
    //   by a full label height — a snap, then the rest of the morph.
    //   Nuvio instead scales the label's own height and spacer with
    //   the fraction (Box height = 16dp*fraction, spacer = 2dp*
    //   fraction, alpha = fraction, dropped only at exactly 0), so
    //   the label shrinks away continuously with the pill.
    // - Selected highlight is ONE pill that TRAVELS: Nuvio draws it
    //   with JellyMotion (a frame-driven jelly pill that slides and
    //   stretches between tabs). Ours is the same behaviour with a
    //   spring: a single Pink pill whose X offset animates to the
    //   selected tab, never an instant per-item background swap.
    // - No glow: the jelly glow layer is simply not drawn, and the
    //   bar is solid #1A1A24 (Nuvio's no-haze bar is #1C1C1E at 0.82).
    val labelFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "dockFraction",
    )
    val horizontalPadding = 58.dp - (30.dp * labelFraction)
    val trackHeight = 48.dp + (16.dp * labelFraction)
    val items = listOf(
        Triple(Tab.Home, R.drawable.ic_home, "Home"),
        Triple(Tab.Library, R.drawable.ic_bookmark, "Library"),
        Triple(Tab.Downloads, R.drawable.ic_download, "Downloads"),
        Triple(Tab.Settings, R.drawable.ic_settings, "Settings"),
    )
    val selectedIndex = items.indexOfFirst { it.first == current }.coerceAtLeast(0)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // hazeEffect is itself a @Composable modifier, so it cannot be
    // called inside a Modifier.then() argument — build the branch
    // here, in the composable body, and .then() the result.
    val dockBgModifier = if (translucent && hazeState != null) {
        val dockStyle = HazeMaterials.ultraThin(if (ThemeState.lightMode) Color.White else Color(0xFF1A1A24))
        Modifier.hazeEffect(hazeState) {
            // Nuvio's large-blur trick: a fixed 0.5x input scale
            // halves the blur work for a dock-sized area, with no
            // visible difference at this size.
            // 1.9-dev4.8.5 scroll: 0.5f -> 0.33f halves blur work
            // again (area scales with the square), translucency
            // unchanged, blur just a touch softer at dock size.
            inputScale = dev.chrisbanes.haze.HazeInputScale.Fixed(0.33f)
            style = dockStyle
        }
    }
    else Modifier.background(if (ThemeState.lightMode) Color.White else Color(0xFF1A1A24))
    androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(999.dp))
                .then(dockBgModifier)
                .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // Travelling selected pill: one pill, spring X, so it
            // slides (and settles, jelly-style) to the new tab
            // instead of appearing there instantly. Its width tracks
            // the pill width, so it also morphs with the bar.
            val innerPad = 6.dp
            val itemWidth = (maxWidth - innerPad * 2) / items.size
            val pillOffset by animateDpAsState(
                targetValue = innerPad + itemWidth * selectedIndex,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
                label = "dockPillOffset",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = innerPad, vertical = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .offset(x = pillOffset - innerPad)
                        .width(itemWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(Pink),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = innerPad, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { (tab, icon, label) ->
                    val selected = tab == current
                    // 1.9-dev4.8.4: very subtle press enlarge on the
                    // icon itself (Nuvio-style): 1.0 -> 1.12 spring
                    // while pressed, graphicsLayer only, so the dock
                    // layout never shifts. Icon only, not the label.
                    val dockInteraction = remember { MutableInteractionSource() }
                    val dockPressed by dockInteraction.collectIsPressedAsState()
                    val dockIconScale by animateFloatAsState(
                        targetValue = if (dockPressed && !ThemeState.reduceMotion) 1.12f else 1f,
                        animationSpec = spring(stiffness = 400f, dampingRatio = 0.7f), label = "dockPress",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(interactionSource = dockInteraction, indication = null) { onTab(tab) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(icon),
                                contentDescription = label,
                                modifier = Modifier.size(22.dp).graphicsLayer(scaleX = dockIconScale, scaleY = dockIconScale),
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                                    if (selected) OnAccent else Muted
                                ),
                            )
                            // Nuvio NavItemLabel: height and spacer
                            // scale with the fraction, alpha too —
                            // continuous shrink, never a pop. Only
                            // fully gone at exactly 0.
                            if (labelFraction > 0f) {
                                Spacer(Modifier.height(2.dp * labelFraction))
                                Box(
                                    modifier = Modifier
                                        .height(16.dp * labelFraction)
                                        .alpha(labelFraction)
                                        .clipToBounds(),
                                ) {
                                    Text(
                                        label,
                                        color = if (selected) OnAccent else Muted,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(
    saved: List<ApiTitle>,
    onOpen: (ApiTitle, String) -> Unit,
    onBrowseHome: () -> Unit,
    onRemove: (ApiTitle) -> Unit,
) {
    // Library = manage what the user already touched. Saved = favourited
    // for later downloading. Downloaded = actually on the device — that
    // section only becomes real in 2.0, when downloads land officially,
    // so until then it is an honest empty state, not a fake list.
    var section by remember { mutableStateOf("Saved") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Library", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, fontFamily = HeadingFont)
        Spacer(Modifier.height(4.dp))
        Text("Your saved anime, and what is actually on your device.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Saved", "Downloaded").forEach { name ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .border(1.dp, if (section == name) Pink else Color.White.copy(alpha = 0.09f), RoundedCornerShape(999.dp))
                        .background(if (section == name) Pink.copy(alpha = 0.2f) else Card)
                        .clickable { section = name }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text(
                        if (name == "Saved") "Saved (${saved.size})" else name,
                        color = if (section == name) Color.White else Muted,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (section == "Saved") {
            if (saved.isEmpty()) {
                Text("Nothing saved yet.", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap the bookmark on a title's detail page to save it for later downloading. It stays saved even if you clear the home cache.",
                    color = Muted, fontSize = 13.5.sp, lineHeight = 19.sp,
                )
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Pink)
                        .clickable(onClick = onBrowseHome).padding(horizontal = 20.dp, vertical = 11.dp),
                ) { Text("Browse anime", color = OnAccent, fontSize = 13.5.sp, fontWeight = FontWeight.Bold) }
            } else {
                androidx.compose.foundation.layout.BoxWithConstraints {
                    val cardW = (maxWidth - 12.dp) / 2
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(saved.chunked(2)) { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pair.forEach { t ->
                                    Column(modifier = Modifier.width(cardW)) {
                                        PosterCard(t, onClick = { onOpen(t, "library-${t.id}") }, cardWidth = cardW, slot = "library-${t.id}")
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "Remove from Library",
                                            color = Faint, fontSize = 12.sp,
                                            modifier = Modifier.clickable { onRemove(t) },
                                        )
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.width(cardW))
                            }
                        }
                        item { Spacer(Modifier.height(100.dp)) }
                    }
                }
            }
        } else {
            Text("Nothing downloaded yet.", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Downloads land officially in 2.0. When they do, titles you pull will show up here with how many episodes are on your device — for example, “2 eps downloaded” — and a Download more action back to the detail page.",
                color = Muted, fontSize = 13.5.sp, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(100.dp))
        }
    }
}

@Composable
fun LoadingScreen() {
    // 1.9-dev2 loading screen, JS's spec: a soft pink / black / white
    // gradient moving very calmly, the Orbit logo appears and moves up,
    // then the "AniPull" name appears, with a small loading bar under
    // it — then Home. No demo titles, ever, on this path.
    val transition = rememberInfiniteTransition(label = "loadingGradient")
    val shift by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(4000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "gradientShift",
    )
    var nameVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(450)
        nameVisible = true
    }
    val logoOffset by animateDpAsState(
        targetValue = if (nameVisible) 0.dp else 28.dp,
        animationSpec = tween(800),
        label = "logoRise",
    )
    val logoAlpha by animateFloatAsState(
        targetValue = if (nameVisible) 1f else 0f,
        animationSpec = tween(800),
        label = "logoAlpha",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF2A1020),
                        Color(0xFF07070C),
                        Color(0xFF1A1A24),
                        Color(0xFF07070C),
                    ),
                    start = Offset(0f, shift * 1000f),
                    end = Offset(1000f, 1000f - shift * 1000f),
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .padding(top = logoOffset)
                    .background(Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = "Anipull",
                    modifier = Modifier.size(88.dp),
                    alpha = logoAlpha,
                )
            }
            Spacer(Modifier.height(16.dp))
            if (nameVisible) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Color.White)) { append("Ani") }
                        withStyle(SpanStyle(color = Pink)) { append("Pull") }
                    },
                    fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.height(20.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.width(120.dp),
                    color = Pink,
                    trackColor = Color.White.copy(alpha = 0.12f),
                )
                Spacer(Modifier.height(12.dp))
                Text("Loading catalogs…", color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    // First boot only. Welcome -> notification grant, sliding like
    // attached pages: welcome out left, notification in from the right.
    // SF Pro Display Semibold here only (JS-supplied font), a real
    // arrow vector drawable on Start, and a real bell vector drawable
    // — never a text arrow or an emoji, per JS's explicit rule.
    var page by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { _ -> onFinish() }
    fun requestNotif() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) onFinish()
        else permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    Box(modifier = Modifier.fillMaxSize().background(Bg)) {
        // Onboarding uses SF Pro Display Semibold for every text on
        // these two screens only — the provider below overrides the
        // app-wide Outfit style for this subtree alone.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalTextStyle provides androidx.compose.ui.text.TextStyle(fontFamily = SFProDisplaySemibold)
        ) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                slideInHorizontally(initialOffsetX = { it }) + fadeIn() togetherWith
                    slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
            },
            label = "onboardingPages",
        ) { current ->
            if (current == 0) {
                // Welcome: MHA wallpaper, fading out to solid #07070C
                // at the bottom, exactly like the demo preview.
                Box(modifier = Modifier.fillMaxSize()) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.onboarding_welcome),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    // 1.9-dev3 fade, Anime+ style: art is full at the
                    // top, then fades out early and is SOLID #07070C
                    // from 62% down — the whole text + button block
                    // sits on solid black with room to breathe, never
                    // on top of busy art (the dev2 readability bug).
                    Box(
                        Modifier.matchParentSize().background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color(0xFF07070C).copy(alpha = 0.45f),
                                    0.30f to Color.Transparent,
                                    0.50f to Color(0xFF07070C).copy(alpha = 0.60f),
                                    0.62f to Color(0xFF07070C),
                                    1.0f to Color(0xFF07070C),
                                ),
                                startY = 0f, endY = Float.POSITIVE_INFINITY,
                            )
                        )
                    )
                    // 1.9-dev4 Welcome, scaled up to FILL the solid
                    // bottom area JS circled in his dev3 screenshot
                    // (was ~267dp of text in a ~360dp area — tiny, not
                    // inviting). Everything is ~1.35x: Welcome 24sp,
                    // logo 60dp / wordmark 46sp, tagline 26sp, button
                    // 60dp, with wider gaps, so the block now spans
                    // the whole highlighted area instead of sitting
                    // small at the bottom of it.
                    Column(
                        modifier = Modifier.fillMaxSize().statusBarsPadding()
                            .navigationBarsPadding().padding(horizontal = 22.dp, vertical = 28.dp),
                    ) {
                        Spacer(Modifier.weight(1f))
                        Text("Welcome to", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = null, modifier = Modifier.size(60.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(SpanStyle(color = Color.White)) { append("Ani") }
                                    withStyle(SpanStyle(color = Pink)) { append("pull") }
                                },
                                fontSize = 46.sp, fontWeight = FontWeight.ExtraBold,
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "Download your favourite anime, episode by episode.",
                            color = Color.White, fontSize = 26.sp, lineHeight = 34.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Download-only · No streaming · No tracking",
                            color = Muted, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(34.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth().height(60.dp)
                                .clip(RoundedCornerShape(999.dp)).background(Pink)
                                .clickable { page = 1 },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Start", color = OnAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(10.dp))
                                androidx.compose.foundation.Image(
                                    painter = painterResource(R.drawable.ic_arrow_right),
                                    contentDescription = null, modifier = Modifier.size(24.dp),
                                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(OnAccent),
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("First boot only — never shown again", color = Faint, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
            } else {
                // Notification grant: AoT portrait wallpaper, same fade,
                // plus the bell. Skip goes straight home, no blocking.
                Box(modifier = Modifier.fillMaxSize()) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.onboarding_notif),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    // Same Anime+ fade as Welcome: solid from 62% down.
                    Box(
                        Modifier.matchParentSize().background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color(0xFF07070C).copy(alpha = 0.45f),
                                    0.30f to Color.Transparent,
                                    0.50f to Color(0xFF07070C).copy(alpha = 0.60f),
                                    0.62f to Color(0xFF07070C),
                                    1.0f to Color(0xFF07070C),
                                ),
                                startY = 0f, endY = Float.POSITIVE_INFINITY,
                            )
                        )
                    )
                    Column(
                        modifier = Modifier.fillMaxSize().statusBarsPadding()
                            .navigationBarsPadding().padding(horizontal = 22.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.weight(1f))
                        Box(
                            modifier = Modifier.size(100.dp).clip(RoundedCornerShape(30.dp))
                                .background(Color(0xFF1A1A24))
                                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(30.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(R.drawable.ic_bell),
                                contentDescription = null, modifier = Modifier.size(52.dp),
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Pink),
                            )
                        }
                        Spacer(Modifier.height(22.dp))
                        Text("Stay in the loop?", color = TextMain, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Allow notifications so we can tell you when a download finishes.",
                            color = Color(0xFFC9C9D6), fontSize = 20.sp, lineHeight = 28.sp, textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Notifications only for download progress.", color = Color(0xFFFFD7E7), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(32.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth().height(60.dp)
                                .clip(RoundedCornerShape(999.dp)).background(Pink)
                                .clickable { requestNotif() },
                            contentAlignment = Alignment.Center,
                        ) { Text("Allow notifications", color = OnAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth().height(60.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                                .clickable { onFinish() },
                            contentAlignment = Alignment.Center,
                        ) { Text("Skip for now", color = Muted, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(16.dp))
                        Text("You can turn this on later from Settings", color = Faint, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        }
    }
}

@Composable
fun DownloadsScreen(onBack: () -> Unit, onBrowse: () -> Unit) {
    // The download queue lives here once the resolver lands. Until then
    // this is an honest empty state, not a fake list.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            BackPill(onBack = onBack)
        }
        Spacer(Modifier.height(80.dp))
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Card)
                .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Pink),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text("Downloads is empty", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Nothing downloaded yet — pull an episode and it will show up here, with progress, pause and resume.",
            color = Muted, fontSize = 13.5.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Pink)
                .clickable(onClick = onBrowse)
                .padding(horizontal = 20.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Browse anime", color = OnAccent, fontSize = 13.5.sp, fontWeight = FontWeight.Bold) }
        // 1.9-dev4.8.1: download notification DESIGN, previewable now.
        // Real downloads land in 2.0, but this posts the exact
        // notification that design specifies (see Notifications.kt):
        // low-importance Downloads channel, per-episode progress +
        // MB/s + Pause/Cancel, then a completion ping to Downloads.
        // Pause/Cancel in the shade really pause/cancel this preview
        // via DownloadActionReceiver, they are not dead buttons.
        Spacer(Modifier.height(16.dp))
        val dlCtx = LocalContext.current
        val dlScope = rememberCoroutineScope()
        var previewMsg by remember { mutableStateOf<String?>(null) }
        fun startPreview() {
            val pr = dlCtx.getSharedPreferences("anipull_settings", android.content.Context.MODE_PRIVATE)
            if (!pr.getBoolean("notif_enabled", true)) { previewMsg = "Download notifications are off in Settings → Notifications."; return }
            DownloadNotifications.isPaused = false
            DownloadNotifications.isCancelled = false
            dlScope.launch {
                var progress = 0
                while (progress <= 100) {
                    if (DownloadNotifications.isCancelled) { previewMsg = "Preview cancelled from the notification."; return@launch }
                    if (DownloadNotifications.isPaused) { kotlinx.coroutines.delay(500); continue }
                    DownloadNotifications.showProgress(dlCtx, "Frieren — Episode 1", progress, 8.4, paused = false)
                    previewMsg = "Preview running: $progress% — check your notification shade."
                    kotlinx.coroutines.delay(1200)
                    progress += 10
                }
                if (pr.getBoolean("completion_enabled", true)) DownloadNotifications.showComplete(dlCtx, "Frieren — Episode 1")
                else DownloadNotifications.cancelProgress(dlCtx)
                previewMsg = "Preview complete — completion ping sent (if enabled in Settings)."
            }
        }
        val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startPreview() else previewMsg = "Notification permission denied — the preview can't show."
        }
        Box(
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, Pink, RoundedCornerShape(999.dp))
                .clickable {
                    val granted = if (android.os.Build.VERSION.SDK_INT >= 33)
                        androidx.core.content.ContextCompat.checkSelfPermission(dlCtx, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    else true
                    if (granted) startPreview() else notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                .padding(horizontal = 20.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Preview download notification", color = Pink, fontSize = 13.5.sp, fontWeight = FontWeight.Bold) }
        if (previewMsg != null) {
            Spacer(Modifier.height(12.dp))
            Text(previewMsg!!, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))
        Text("Sample only — real downloads land in 2.0.", color = Faint, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun SettingsToggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(16.dp))
        androidx.compose.material3.Switch(
            checked = checked, onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Pink,
                checkedBorderColor = Pink,
                uncheckedThumbColor = Color.White.copy(alpha = 0.9f),
            ),
        )
    }
}

@Composable
fun BackPill(onBack: () -> Unit) {
    // 1.9-dev4.8.4: the old 14sp "← Back" text was small, easy to
    // miss, and sat close to the status bar. Now a proper pill:
    // 40dp tall, Card background, Pink border, 15sp bold text.
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Card)
            .border(1.dp, Pink.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .clickable(onClick = onBack)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("←  Back", color = Pink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun DetailScreen(title: ApiTitle, onBack: () -> Unit, onPull: (ApiTitle) -> Unit, isSaved: Boolean = false, onSaveToggle: () -> Unit = {}, heroPosterHidden: Boolean = false, detailFade: Float = 1f, onPosterBounds: ((Rect) -> Unit)? = null, onPosterImageBounds: ((Rect) -> Unit)? = null) {
    var season by remember { mutableStateOf(1) }
    var synExpanded by remember { mutableStateOf(false) }
    // 1.9-dev4.8 extra smoothness: without a hero, the detail fades
    // in over 300ms instead of appearing instantly; with a hero, the
    // parent drives the fade from the hero spring (text arrives as
    // the poster lands, exactly as JS asked).
    var detailAppeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { detailAppeared = true }
    val detailAppearedFade by animateFloatAsState(
        targetValue = if (detailAppeared) 1f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "detailFadeIn",
    )
    val detailAlpha = if (heroPosterHidden) detailFade else detailAppearedFade
    // 4.8.16 detail info delayed fade: info Column (title/type/score/genres) fades in from below AFTER poster lands (300ms delay + 300ms fade), not instantly at landing. Poster itself is shared, so it is already visible at landing, only info delays. DetailScreen is only composed as incoming content, so LaunchedEffect(Unit) runs at transition start, info appears 300-600ms after tap.
    var detailInfoVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        detailInfoVisible = true
    }
    val detailInfoAlpha by animateFloatAsState(targetValue = if (detailInfoVisible) 1f else 0f, animationSpec = tween(300, easing = FastOutSlowInEasing), label = "detailInfoFade")
    val detailInfoTransY = (1f - detailInfoAlpha) * with(LocalDensity.current) { 20.dp.toPx() }
    val isFrierenDemo = title.title.startsWith("Frieren") && title.episodes != null
    val episodes = if (isFrierenDemo && season == 2) emptyList()
        else title.episodes?.let { (1..it).toList() }
    val watchOrder = if (isFrierenDemo) listOf("Season 1", "Season 2")
        else if (title.title == "Attack on Titan" && title.episodes != null) listOf("Attack on Titan (watch-order guide in the web demo)")
        else title.seasons.takeIf { it.isNotEmpty() }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .alpha(detailAlpha),
    ) {
        item {
            Spacer(Modifier.height(20.dp))
            BackPill(onBack = onBack)
            Spacer(Modifier.height(16.dp))
            Row {
                Box(modifier = Modifier.alpha(if (heroPosterHidden) 0f else 1f)) {
                    PosterCard(title, onClick = {}, onBounds = onPosterBounds, onPosterBounds = onPosterImageBounds, reportBoundsOnPlace = true, slot = LocalSharedPosterKey.current ?: "detail-${title.id}")
                }
                Spacer(Modifier.width(16.dp))
                // 1.9-dev4.8.8: shonenx-style — detail info flies
                // in from the RIGHT as the poster lands, not up
                // and not just fading in place. detailFade is
                // heroProgress (0 -> 1), so title/type/rating
                // travel 40dp leftwards while fading in, exactly
                // like shonenx's detail page sliding in from the
                // right while its Hero poster flies. When there
                // is no hero, detailFade is 1, offset 0.
                Column(modifier = Modifier.weight(1f).alpha(detailInfoAlpha).graphicsLayer { translationX = (1f - detailFade) * 40f; translationY = detailInfoTransY }) {
                    Text(title.title, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 26.sp, fontFamily = HeadingFont)
                    Spacer(Modifier.height(4.dp))
                    val meta = listOfNotNull(title.type, title.year).joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, color = TextMain, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    if (title.score != null) Text("★ ${title.score}", color = Pink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    if (title.genres != null) Text(title.genres, color = TextMain, fontSize = 13.sp)
                    if (title.status != null) Text(title.status, color = Teal, fontSize = 13.sp)
                    if (isFrierenDemo) Text("You are currently in Season $season", color = Lavender, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            // 1.9-dev2: save to Library. Real vector bookmark icon, not
            // an emoji — and it survives "Clear home cache" because
            // Library lives in its own prefs file, not the home cache.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.dp, if (isSaved) Pink else Color.White.copy(alpha = 0.09f), RoundedCornerShape(999.dp))
                    .background(if (isSaved) Pink.copy(alpha = 0.2f) else Card)
                    .clickable(onClick = onSaveToggle)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.ic_bookmark),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(if (isSaved) Pink else Muted),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isSaved) "Saved in Library" else "Save to Library",
                        color = if (isSaved) Color.White else Muted,
                        fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val syn = title.overview
                ?: "Synopsis placeholder (demo). Real synopsis comes with the metadata stage — Stage 1 shows the layout only."
            Text(
                syn, color = Muted, fontSize = 14.sp, lineHeight = 20.sp,
                maxLines = if (synExpanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (syn.length > 220) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (synExpanded) "Show less" else "Read more",
                    color = Pink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { synExpanded = !synExpanded },
                )
            }
        }
        if (isFrierenDemo) {
            item {
                Spacer(Modifier.height(16.dp))
                Text("Season", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2).forEach { s ->
                        OutlinedButton(
                            onClick = { season = s },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (season == s) Pink else Color.Transparent,
                                contentColor = Color.White,
                            ),
                        ) { Text("Season $s") }
                    }
                }
                if (season == 2) {
                    Spacer(Modifier.height(8.dp))
                    Text("Season 2 episode count: TBD (as in the web demo).", color = Teal, fontSize = 13.sp)
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Episodes", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("via AnimePahe · healthy", color = Teal, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            when {
                episodes == null -> Text(
                    "AniLab gives metadata (title, poster, synopsis) but no episode list for this title yet — episode pull is the next stage, after this AniLab step.",
                    color = Muted, fontSize = 13.sp, lineHeight = 19.sp,
                )
                episodes.isEmpty() -> Text("No episodes yet for this season.", color = Muted, fontSize = 13.sp)
                else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    episodes.forEach { ep ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Card)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Episode $ep", color = TextMain, fontSize = 14.sp)
                            Text("Pull ↓", color = Pink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { onPull(title) })
                        }
                    }
                }
            }
        }
        if (watchOrder != null) {
            item {
                Spacer(Modifier.height(16.dp))
                Text("Watch order", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                watchOrder.forEachIndexed { i, step ->
                    Text("${i + 1}. $step", color = Muted, fontSize = 14.sp)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onPull(title) },
                colors = ButtonDefaults.buttonColors(containerColor = Pink),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Download", color = OnAccent, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(title: ApiTitle, onDismiss: () -> Unit, notWired: () -> Unit) {
    var quality by remember { mutableStateOf("720") }
    var season by remember { mutableStateOf(1) }
    val isFrierenDemo = title.title.startsWith("Frieren") && title.episodes != null
    val episodes = if (isFrierenDemo && season == 2) emptyList()
        else title.episodes?.let { (1..it).toList() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title.title, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(title.overview ?: "Synopsis placeholder (demo).", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Text("Quality", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("360", "480", "720", "1080").forEach { q ->
                    OutlinedButton(
                        onClick = { quality = q },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (quality == q) Pink else Color.Transparent,
                            contentColor = Color.White,
                        ),
                    ) { Text("${q}p") }
                }
            }
            if (isFrierenDemo) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2).forEach { s ->
                        OutlinedButton(
                            onClick = { season = s },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (season == s) Pink else Color.Transparent,
                                contentColor = Color.White,
                            ),
                        ) { Text("Season $s") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = notWired,
                colors = ButtonDefaults.buttonColors(containerColor = Pink),
                modifier = Modifier.fillMaxWidth(),
                enabled = episodes?.isNotEmpty() == true,
            ) { Text("Download Season ($quality" + "p)", color = OnAccent, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = notWired, modifier = Modifier.fillMaxWidth()) {
                Text("Copy-all-links", color = Color.White)
            }
            Spacer(Modifier.height(12.dp))
            Text("Episodes", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            when {
                episodes == null -> Text("Episode pull is the next stage — AniLab metadata only for now.", color = Muted, fontSize = 13.sp)
                episodes.isEmpty() -> Text("Copy-all-links is blocked for Season 2 until its episode count is known (as in the web demo).", color = Muted, fontSize = 13.sp)
                else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    episodes.forEach { ep ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Episode $ep · $quality" + "p", color = TextMain, fontSize = 14.sp)
                            Text("Pull", color = Pink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable(onClick = notWired))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
