package com.js.anipull

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

// Colours taken from the web mockup's CSS variables.
private val OutfitFont = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_bold, FontWeight.Bold),
    Font(R.font.outfit_extrabold, FontWeight.ExtraBold),
)

// Onboarding only (JS, 2026-11-03): SF Pro Display Semibold, supplied
// by JS as sf_pro_display_semibold.otf. The rest of the app stays on
// Outfit. Note: this OTF uses CFF outlines — if it ever fails to
// render on a phone, Compose falls back to the system font, and the
// fix then is a TrueType conversion, not a different design.
private val SFProDisplaySemibold = FontFamily(
    Font(R.font.sf_pro_display_semibold, FontWeight.SemiBold),
)

val Bg = Color(0xFF07070C)
private val Bg2 = Color(0xFF0B0B13)
private val Card = Color(0xFF16161F)
private val TextMain = Color(0xFFF5F5FA)
private val Muted = Color(0xFFA2A2B5)
private val Faint = Color(0xFF6D6D82)
private val Pink = Color(0xFFFF4D8D)
private val Lavender = Color(0xFFA78BFA)
private val Teal = Color(0xFF2DD4BF)

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
fun PosterImage(poster: String?, modifier: Modifier) {
    var bitmap by remember(poster) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (poster != null) {
        LaunchedEffect(poster) {
            bitmap = PosterCache.get(poster) ?: loadPoster(poster, ctx)
        }
    }
    Box(modifier = modifier.background(Color(0xFF14141F))) {
        val bmp = bitmap
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
    }
}

@Composable
fun PosterCard(title: ApiTitle, onClick: () -> Unit, notWired: (() -> Unit)? = null, cardWidth: Dp = 118.dp) {
    val cardHeight = cardWidth * 3 / 2
    Column(
        modifier = Modifier
            .width(cardWidth)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(width = cardWidth, height = cardHeight)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp)),
        ) {
            PosterImage(title.poster, Modifier.fillMaxSize())
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
                Text("1080p", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
        Text(title.title, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, minLines = 2, lineHeight = 16.sp)
        Spacer(Modifier.height(2.dp))
        val meta = listOfNotNull(title.type, title.year).joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, color = Muted, fontSize = 11.sp)
    }
}

data class RowTag(val text: String, val solid: Boolean = false)

@Composable
fun SkeletonRow() {
    // What loading looks like now: dark placeholder cards, NOT demo titles.
    // Demo titles as a placeholder were the confusing part — they looked
    // like real data, then swapped. Skeletons say "still loading" honestly.
    Column {
        Box(Modifier.width(140.dp).height(18.dp).clip(RoundedCornerShape(8.dp)).background(Card))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Box(Modifier.width(118.dp).height(177.dp).clip(RoundedCornerShape(12.dp)).background(Card))
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
                        Column {
                            Box(Modifier.width(cardW).height(cardH).clip(RoundedCornerShape(16.dp)).background(Card))
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.width(cardW * 0.8f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Card))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PosterRow(heading: String, tags: List<RowTag>, titles: List<ApiTitle>, onOpen: (ApiTitle) -> Unit, notWired: (() -> Unit)? = null, iconRes: Int? = null) {
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
            Text(heading, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
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
            items(titles) { PosterCard(it, { onOpen(it) }, notWired) }
        }
    }
}

@Composable
fun EditorsPick(pick: ApiTitle, onOpen: (ApiTitle) -> Unit, notWired: () -> Unit) {
    // Website pick: a wide card, image behind, content over a shade that
    // gets darker toward the bottom so both buttons stay readable.
    // matchParentSize (not fillMaxSize) is what makes the poster cover
    // the whole card, including the Details button area — fillMaxSize
    // in a scrolling column left that bottom strip poster-less.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(20.dp))
            .background(Color(0xFF14141F))
            .clickable { onOpen(pick) },
    ) {
        PosterImage(pick.poster, Modifier.matchParentSize())
        // Website shade is horizontal (left dark .88 -> right .08); stack a
        // vertical darkening underneath it so the bottom buttons stay readable.
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF05050A).copy(alpha = 0.88f),
                        Color(0xFF05050A).copy(alpha = 0.45f),
                        Color(0xFF05050A).copy(alpha = 0.08f),
                    )
                )
            )
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                )
            )
        )
        Column(modifier = Modifier.padding(18.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.dp, Pink.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                    .background(Pink.copy(alpha = 0.22f))
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            ) {
                val typeLabel = (pick.type ?: "ANIME").uppercase()
                // Website .pick-tag exactly: light pink #FFD7E7 text, not
                // plain pink — that's why the app pill looked washed out
                // over a bright poster.
                Text("$typeLabel · 1080P READY", color = Color(0xFFFFD7E7), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(pick.title, color = TextMain, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 28.sp)
            Spacer(Modifier.height(6.dp))
            val syn = pick.overview
                ?: "Kyoto Animation's film about a boy seeking redemption from the deaf girl he bullied. One file, no episodes to chase — pull it and keep it forever."
            Text(syn, color = Color.White.copy(alpha = 0.85f), fontSize = 13.5.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(15.dp))
            Button(
                onClick = notWired,
                colors = ButtonDefaults.buttonColors(containerColor = Pink),
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
                )
                Spacer(Modifier.width(8.dp))
                Text("Download · 1080p", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onOpen(pick) }, modifier = Modifier.fillMaxWidth()) {
                Text("Details & other qualities", color = Color.White)
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
    // 20fps ticker: drives particle drift + twinkle. 20fps, not 60,
    // so it stays cheap on low-spec phones.
    var animTime by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(50)
            animTime += 50
        }
    }
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
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val searchTop = searchTopPx ?: h
            val t = animTime / 1000f
            if (particlesOn) bgDots.forEachIndexed { i, dot ->
                // Slow drift + twinkle: each dot has its own phase/speed,
                // so they shimmer out of sync instead of pulsing together.
                val driftX = kotlin.math.sin(t * dot.speed + dot.phase) * dot.drift
                val driftY = kotlin.math.cos(t * dot.speed * 0.8f + dot.phase) * dot.drift * 0.6f
                val twinkle = 0.5f + 0.5f * kotlin.math.sin(t * dot.speed * 1.5f + dot.phase * 2f)
                var pos = Offset(dot.xFrac * w, dot.yFrac * h) + Offset(driftX, driftY) + bgOffsets[i]
                if (pos.y > searchTop - 8f) pos = Offset(pos.x, searchTop - 8f)
                // Glow halo first, then the core dot.
                drawCircle(color = dot.color, radius = dot.radius * 3f, center = pos, alpha = 0.10f * twinkle)
                drawCircle(color = dot.color, radius = dot.radius, center = pos, alpha = 0.25f + 0.45f * twinkle)
            }
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
                color = TextMain, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-1).sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(), lineHeight = 37.sp,
            )
            Text(
                "Keep it offline.",
                color = Pink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
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
                    .background(Color(0xFF10101A))
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
                    ) { Text("Search", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold) }
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
fun AnipullAppInner() {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<ApiTitle?>(null) }
    var query by remember { mutableStateOf("") }
    var sheetFor by remember { mutableStateOf<ApiTitle?>(null) }
    var liveSections by remember { mutableStateOf<List<HomeSection>?>(null) }
    var featured by remember { mutableStateOf<ApiTitle?>(null) }
    var homeFailed by remember { mutableStateOf(false) }
    var extraSections by remember { mutableStateOf<List<HomeSection>>(emptyList()) }
    var extraStarted by remember { mutableStateOf(false) }
    var isChipQuery by remember { mutableStateOf(false) }
    var liveSearchDone by remember { mutableStateOf(false) }
    var pillCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var lazyColumnCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
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
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val appFocusManager = LocalFocusManager.current
    // Tapping out = scrolling away: the cursor stops blinking, and the
    // header content comes back (unless a query is still typed).
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) appFocusManager.clearFocus()
    }
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
        else if (screen != null) screen = null
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

    // Dynamic home: (1) cached live home is loaded into the rows
    // immediately if there is one, (2) a progressive refresh updates
    // each row the moment it lands, (3) demo titles appear ONLY if
    // the fetch actually fails and there is no cache — never as a
    // loading placeholder. 1.9-dev2: keyed on fetchAttempt so the
    // Retry button on a failed load actually re-runs this.
    // 1.9-dev4.7 loading rule (JS, 2026-11-03): HARD 1.5s cap.
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
        kotlinx.coroutines.delay(1500)
        showLoading = false
    }
    LaunchedEffect(fetchAttempt) {
        homeFailed = false
        val wanted = listOf("Spotlight", "Trending", "Most Popular", "Top Airing", "Most Watched")
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
        launch {
            fetchHomeProgressive(
                onSection = { _, sec ->
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
                saveHomeCache(context, HomeData(ordered, localFeatured))
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
            val genres = listOf(1 to "Action anime", 14 to "Romance anime", 5 to "Comedy anime", 4 to "Fantasy anime")
            genres.forEach { (id, name) ->
                scope.launch {
                    fetchCategory(id, name)?.let { sec ->
                        extraSections = extraSections + sec
                    }
                }
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
    } else if (screen != null) {
        DetailScreen(
            title = screen!!,
            onBack = { screen = null },
            onPull = { sheetFor = it },
            isSaved = savedTitles.any { it.id == screen!!.id },
            onSaveToggle = { screen?.let { toggleSave(it) } },
        )
    } else Box(modifier = Modifier.fillMaxSize().background(Bg)) {
    if (showSettings) {
        SettingsScreen(onBack = { goTab(Tab.Home) })
    } else if (showDownloads) {
        DownloadsScreen(onBack = { goTab(Tab.Home) }, onBrowse = { goTab(Tab.Home) })
    } else if (showLibrary) {
        LibraryScreen(
            saved = savedTitles,
            onOpen = { screen = it },
            onBrowseHome = { goTab(Tab.Home) },
            onRemove = { toggleSave(it) },
        )
    } else if (screen == null) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Bg)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .nestedScroll(dockScrollConnection)
                .onGloballyPositioned { lazyColumnCoords = it }
                .pointerInput(pillCoords, isSearchFocused, lazyColumnCoords) {
                    detectTapGestures(onTap = { offset ->
                        if (isSearchFocused) {
                            // Pill bounds in LazyColumn coordinates, via
                            // localBoundingBoxOf (no root/window API needed).
                            val b = lazyColumnCoords?.let { lc -> pillCoords?.let { pc -> lc.localBoundingBoxOf(pc) } }
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
            item {
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
                    onPillPositioned = { pillCoords = it },
                    onCloseSearch = { closeSearch() },
                    particlesOn = LocalContext.current.getSharedPreferences("anipull_settings", android.content.Context.MODE_PRIVATE).getBoolean("particles", true),
                )
            }
            if (query.isNotBlank()) {
                item {
                    Text(
                        if (searching) "Searching AniLab…" else "Search results",
                        color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold,
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
                                            PosterCard(t, onClick = { screen = t }, notWired = notWired, cardWidth = cardW)
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
                item {
                    val cachedTitles = (liveSections?.flatMap { it.titles } ?: emptyList()).distinctBy { it.id }
                    if (cachedTitles.isEmpty()) {
                        Text("Type to search AniLab…", color = Faint, fontSize = 13.sp)
                        Spacer(Modifier.height(14.dp))
                        SearchSkeletonGrid()
                    } else {
                        Text("From your catalog", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        androidx.compose.foundation.layout.BoxWithConstraints {
                            val cardW = (maxWidth - 12.dp) / 2
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                cachedTitles.take(12).chunked(2).forEach { pair ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        pair.forEach { t ->
                                            PosterCard(t, onClick = { screen = t }, notWired = notWired, cardWidth = cardW)
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
                    item { PosterRow("Weekend marathon picks", listOf(RowTag("Sun", true), RowTag("Full seasons ready")), spotlight.titles.take(5), { screen = it }, notWired, R.drawable.ic_calendar) }
                }
                featured?.let { pick ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(R.drawable.ic_star),
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Pink),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Editor's pick", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(12.dp))
                        EditorsPick(pick, { screen = it }, notWired)
                    }
                }
                liveSections!!.filter { it.name != "Spotlight" }.forEach { section ->
                    val icon = when (section.name) {
                        "Spotlight" -> R.drawable.ic_star
                        "Trending" -> R.drawable.ic_flame
                        "Most Popular" -> R.drawable.ic_chart
                        "Top Airing" -> R.drawable.ic_film
                        "Most Watched" -> R.drawable.ic_eye
                        else -> null
                    }
                    item { PosterRow(section.name, emptyList(), section.titles, { screen = it }, notWired, icon) }
                }
            } else if (homeFailed) {
                // 1.9-dev2: failed load = skeletons + retry, never demo
                // titles pretending to be a catalog.
                item {
                    Text("Couldn't load catalogs.", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Check your connection and try again — no demo titles are shown in place of the live feed.", color = Muted, fontSize = 13.5.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Pink)
                            .clickable { fetchAttempt += 1 }.padding(horizontal = 20.dp, vertical = 11.dp),
                    ) { Text("Retry", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold) }
                }
                item { SkeletonRow() }
                item { SkeletonRow() }
            } else {
                // First load, no cache, fetch still running: skeletons, not demo.
                item { SkeletonRow() }
                item { SkeletonRow() }
            }
            // Extra catalogs, below the AniLab home rows: genre rows from
            // /api/category (Action 1, Romance 14, Comedy 5, Fantasy 4).
            // They load progressively in the background, like home rows.
            if (!isSearchFocused && query.isBlank() && liveSections != null) {
                extraSections.forEach { section ->
                    item { PosterRow(section.name, emptyList(), section.titles, { screen = it }, notWired, null) }
                }
            }
            // 1.9-dev4.7: footer (the Anipull about section) is
            // HOME ONLY. It used to sit at the end of every list, so
            // it stayed visible when the search box was tapped
            // (short focused list) and while results were fetching
            // (short skeleton list) — both screenshots JS circled.
            if (!isSearchFocused && query.isBlank() && !searching) item {
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
    // Floating dock, tab screens only (Detail is handled above, with
    // no dock). Aligned to the bottom, above the navigation bar.
    if (screen == null) {
        Box(
            modifier = Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding().padding(bottom = 12.dp, start = 8.dp, end = 8.dp),
        ) {
            // The morph state is GLOBAL, like Nuvio's: switching tabs
            // never resets it. Mini stays mini on the new tab, and
            // the selected pill travels there (see FloatingDock).
            // Dev3's bug was forcing fraction = 1f off Home.
            FloatingDock(
                current = currentTab,
                fraction = dockFractionState.value,
                onTab = { goTab(it) },
            )
        }
    }
    }

    sheetFor?.let { title ->
        DownloadSheet(title = title, onDismiss = { sheetFor = null }, notWired = notWired)
    }
}

// ---- 1.9-dev2: floating dock, Library, loading, onboarding ------------

enum class Tab { Home, Library, Downloads, Settings }

@Composable
fun FloatingDock(current: Tab, fraction: Float, onTab: (Tab) -> Unit) {
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
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xFF1A1A24))
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
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { onTab(tab) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(icon),
                                contentDescription = label,
                                modifier = Modifier.size(22.dp),
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                                    if (selected) Color.White else Muted
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
                                        color = if (selected) Color.White else Muted,
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
    onOpen: (ApiTitle) -> Unit,
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
        Text("Library", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
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
                ) { Text("Browse anime", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold) }
            } else {
                androidx.compose.foundation.layout.BoxWithConstraints {
                    val cardW = (maxWidth - 12.dp) / 2
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(saved.chunked(2)) { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pair.forEach { t ->
                                    Column(modifier = Modifier.width(cardW)) {
                                        PosterCard(t, onClick = { onOpen(t) }, cardWidth = cardW)
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
                                Text("Start", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(10.dp))
                                androidx.compose.foundation.Image(
                                    painter = painterResource(R.drawable.ic_arrow_right),
                                    contentDescription = null, modifier = Modifier.size(24.dp),
                                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
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
                        ) { Text("Allow notifications", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
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
            Text("← Back", color = Pink, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onBack))
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
        ) { Text("Browse anime", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("anipull_settings", android.content.Context.MODE_PRIVATE) }
    // Saved now, applied when the resolver lands — downloads are still
    // demo-only, so these do not download anything yet. Saying so here
    // beats silently pretending they work.
    var parallel by remember { mutableStateOf(prefs.getInt("parallel", 2)) }
    var speedLimit by remember { mutableStateOf(prefs.getInt("speed_mbps", 0)) }
    var folder by remember { mutableStateOf(prefs.getString("folder", "Downloads/Anipull") ?: "Downloads/Anipull") }
    var quality by remember { mutableStateOf(prefs.getString("quality", "1080p") ?: "1080p") }
    var language by remember { mutableStateOf(prefs.getString("language", "Sub") ?: "Sub") }
    var wifiOnly by remember { mutableStateOf(prefs.getBoolean("wifi_only", true)) }
    var askSeason by remember { mutableStateOf(prefs.getBoolean("ask_season", true)) }
    var subs by remember { mutableStateOf(prefs.getBoolean("subs", true)) }
    var particles by remember { mutableStateOf(prefs.getBoolean("particles", true)) }
    var cacheMsg by remember { mutableStateOf<String?>(null) }
    fun save() {
        prefs.edit()
            .putInt("parallel", parallel)
            .putInt("speed_mbps", speedLimit)
            .putString("folder", folder)
            .putString("quality", quality)
            .putString("language", language)
            .putBoolean("wifi_only", wifiOnly)
            .putBoolean("ask_season", askSeason)
            .putBoolean("subs", subs)
            .putBoolean("particles", particles)
            .apply()
    }
    // System folder picker — no hand-typing a path. The picked tree Uri
    // is stored too (folder_uri) so the download engine can later write
    // via DocumentFile; the human-readable path is what shows in the field.
    val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) { }
            val raw = java.net.URLDecoder.decode(uri.toString(), "UTF-8")
            val afterColon = raw.substringAfter(":", raw)
            val display = afterColon.removePrefix("/tree/").replace("primary:", "").trim()
            if (display.isNotBlank()) {
                folder = display
                prefs.edit().putString("folder", display).putString("folder_uri", uri.toString()).apply()
            }
        }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        item {
            Text("← Back", color = Pink, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onBack))
            Spacer(Modifier.height(16.dp))
            Text("Settings", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Saved on this phone. Download settings apply once the download engine lands — right now downloads are still demo-only.",
                color = Muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
        item {
            Text("DOWNLOADS", color = Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(12.dp))
            Text("Parallel downloads", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (parallel <= 1) "1 episode at a time" else "$parallel episodes at once",
                color = Pink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
            androidx.compose.material3.Slider(
                value = parallel.toFloat(), onValueChange = { parallel = it.toInt(); save() },
                valueRange = 1f..5f, steps = 3,
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("1", color = Faint, fontSize = 11.sp)
                Text("5", color = Faint, fontSize = 11.sp)
            }
            Spacer(Modifier.height(20.dp))
            Text("Download speed limit", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (speedLimit == 0) "Unlimited" else "$speedLimit MB/s",
                color = Pink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
            androidx.compose.material3.Slider(
                value = speedLimit.toFloat(), onValueChange = { speedLimit = it.toInt(); save() },
                valueRange = 0f..10f, steps = 9,
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Unlimited", color = Faint, fontSize = 11.sp)
                Text("10 MB/s", color = Faint, fontSize = 11.sp)
            }
            Spacer(Modifier.height(20.dp))
            Text("Download folder", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(12.dp))
                        .background(Color(0xFF10101A))
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                ) {
                    BasicTextField(
                        value = folder,
                        onValueChange = { folder = it; save() },
                        singleLine = true,
                        textStyle = TextStyle(color = TextMain, fontSize = 14.sp),
                        cursorBrush = SolidColor(Pink),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Pink)
                        .clickable { folderPicker.launch(null) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(R.drawable.ic_folder),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Browse", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text("Tap Browse to pick a folder from your phone — no need to type a path by hand.", color = Faint, fontSize = 12.sp)
            Spacer(Modifier.height(20.dp))
            Text("Preferred quality", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("360p", "480p", "720p", "1080p").forEach { q ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .border(1.dp, if (quality == q) Pink else Color.White.copy(alpha = 0.09f), RoundedCornerShape(999.dp))
                            .background(if (quality == q) Pink.copy(alpha = 0.2f) else Card)
                            .clickable { quality = q; save() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(q, color = if (quality == q) Color.White else Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Preferred language", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Sub", "Dub").forEach { lang ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .border(1.dp, if (language == lang) Pink else Color.White.copy(alpha = 0.09f), RoundedCornerShape(999.dp))
                            .background(if (language == lang) Pink.copy(alpha = 0.2f) else Card)
                            .clickable { language = lang; save() }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(lang, color = if (language == lang) Color.White else Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Text("Sub = Japanese audio with subtitles. Dub = English audio.", color = Faint, fontSize = 12.sp)
            Spacer(Modifier.height(24.dp))
        }
        item {
            Text("BEHAVIOUR", color = Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(8.dp))
        }
        item { SettingsToggle("Download only on Wi-Fi", "Pause downloads on mobile data.", wifiOnly) { wifiOnly = it; save() } }
        item { SettingsToggle("Ask before a whole season", "Confirm before pulling every episode at once.", askSeason) { askSeason = it; save() } }
        item { SettingsToggle("Include subtitles when available", "Soft subs are fine — hard subs are not required.", subs) { subs = it; save() } }
        item { SettingsToggle("Home particles", "Drifting, glowing, twinkling dots on the home page.", particles) { particles = it; save() } }
        item {
            Spacer(Modifier.height(24.dp))
            Text("STORAGE", color = Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(12.dp))
                    .background(Card)
                    .clickable {
                        ctx.cacheDir.resolve("posters").deleteRecursively()
                        ctx.getSharedPreferences("anipull_cache", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                        cacheMsg = "Home cache cleared — next open reloads live."
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) { Text("Clear home cache", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            if (cacheMsg != null) {
                Spacer(Modifier.height(8.dp))
                Text(cacheMsg!!, color = Teal, fontSize = 12.5.sp)
            }
            Spacer(Modifier.height(24.dp))
            Text("ABOUT", color = Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(8.dp))
            Text("Anipull 1.9-dev4.6 · com.js.anipull · Download-only, no streaming, no tracking.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            // Extra bottom space so the floating dock never covers this.
            Spacer(Modifier.height(100.dp))
        }
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
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun DetailScreen(title: ApiTitle, onBack: () -> Unit, onPull: (ApiTitle) -> Unit, isSaved: Boolean = false, onSaveToggle: () -> Unit = {}) {
    var season by remember { mutableStateOf(1) }
    var synExpanded by remember { mutableStateOf(false) }
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
            .padding(horizontal = 16.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Text("← Back", color = Pink, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onBack))
            Spacer(Modifier.height(16.dp))
            Row {
                PosterCard(title, onClick = {})
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title.title, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 26.sp)
                    Spacer(Modifier.height(4.dp))
                    val meta = listOfNotNull(title.type, title.year).joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    if (title.score != null) Text("★ ${title.score}", color = Pink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    if (title.genres != null) Text(title.genres, color = Muted, fontSize = 13.sp)
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
            ) { Text("Download", color = Color.White, fontWeight = FontWeight.Bold) }
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
            ) { Text("Download Season ($quality" + "p)", color = Color.White, fontWeight = FontWeight.Bold) }
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
