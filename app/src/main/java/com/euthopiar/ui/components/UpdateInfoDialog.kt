package com.euthopiar.ui.components

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.euthopiar.R
import com.euthopiar.data.update.ApkDownloadState
import com.euthopiar.data.update.AppUpdateManager
import com.euthopiar.data.update.ReleaseInfo
import com.euthopiar.data.update.isRemoteUpdateAvailable
import com.euthopiar.data.update.isRemoteVersionNewer
import com.euthopiar.ui.navigation.LocalNavbarVisible
import com.euthopiar.ui.settings.components.settingsPressable
import com.euthopiar.ui.theme.AppFontFamily
import com.euthopiar.ui.theme.LocalAmoledMode
import com.euthopiar.ui.theme.LocalReduceMotion
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Parsed node representation of a release changelog entry.
 */
sealed interface ChangelogNode {
    data class Header(val title: String) : ChangelogNode
    data class FeatureBullet(val title: String?, val description: String) : ChangelogNode
    data class Paragraph(val text: String) : ChangelogNode
}

/**
 * Parsed changelog containing extracted release photos and structured text nodes.
 */
data class ParsedChangelog(
    val photos: List<String>,
    val nodes: List<ChangelogNode>
)

/**
 * Intelligently parses raw GitHub release markdown:
 * 1. Extracts photos (markdown images, HTML img tags, standalone image URLs).
 * 2. Structures headers (## Top Features, ## What's New).
 * 3. Highlights bullet headlines (**Title:** Description) in bold with clean spacing.
 */
fun parseReleaseChangelog(rawText: String): ParsedChangelog {
    if (rawText.isBlank()) {
        return ParsedChangelog(
            photos = emptyList(),
            nodes = listOf(
                ChangelogNode.FeatureBullet(
                    title = "Bug Fixes & Improvements",
                    description = "General stability enhancements, playback optimization and performance updates."
                )
            )
        )
    }

    val extractedPhotos = mutableListOf<String>()
    val nodes = mutableListOf<ChangelogNode>()

    // Regex 1: Markdown image syntax ![alt](url)
    val mdImgRegex = Regex("""!\[.*?\]\((https?://\S+?\.(?:png|jpg|jpeg|webp|gif)(?:\?\S*)?)\)""", RegexOption.IGNORE_CASE)
    // Regex 2: HTML <img> tag with src
    val htmlImgRegex = Regex("""<img[^>]+src=["'](https?://\S+?\.(?:png|jpg|jpeg|webp|gif)(?:\?\S*)?)["'][^>]*>""", RegexOption.IGNORE_CASE)
    // Regex 3: Standalone image URL on its own line
    val urlImgRegex = Regex("""^\s*(https?://\S+?\.(?:png|jpg|jpeg|webp)(?:\?\S*)?)\s*$""", RegexOption.IGNORE_CASE)

    val lines = rawText.lines()
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue

        // Extract photos
        mdImgRegex.findAll(trimmed).forEach { match ->
            extractedPhotos.add(match.groupValues[1])
        }
        htmlImgRegex.findAll(trimmed).forEach { match ->
            extractedPhotos.add(match.groupValues[1])
        }
        val standaloneMatch = urlImgRegex.matchEntire(trimmed)
        if (standaloneMatch != null) {
            extractedPhotos.add(standaloneMatch.groupValues[1])
            continue
        }

        // Clean out images from text lines so they don't leave raw tags behind
        val cleanLine = trimmed
            .replace(mdImgRegex, "")
            .replace(htmlImgRegex, "")
            .trim()

        if (cleanLine.isBlank()) continue

        // Check for Headers (e.g. ## Features, ### Changelog)
        if (cleanLine.startsWith("#")) {
            val headerTitle = cleanLine.trimStart('#').trim()
            if (headerTitle.isNotBlank()) {
                nodes.add(ChangelogNode.Header(headerTitle))
                continue
            }
        }

        // Bullet points
        val isBullet = cleanLine.startsWith("* ") || cleanLine.startsWith("- ") || cleanLine.startsWith("• ")
        val content = if (isBullet) cleanLine.substring(2).trim() else cleanLine

        // Pattern 1: **Title:** Description or **Title**: Description
        val boldColonMatch = Regex("""^\*\*(.+?)\*\*:\s*(.*)$""").matchEntire(content)
            ?: Regex("""^\*\*(.+?):\*\*\s*(.*)$""").matchEntire(content)

        if (boldColonMatch != null) {
            val title = boldColonMatch.groupValues[1].trim()
            val desc = boldColonMatch.groupValues[2].trim()
            nodes.add(ChangelogNode.FeatureBullet(title, desc))
            continue
        }

        // Pattern 2: Title: Description
        val colonIdx = content.indexOf(": ")
        if (colonIdx in 2..40) {
            val potentialTitle = content.substring(0, colonIdx).trim().removePrefix("**").removeSuffix("**").trim()
            val potentialDesc = content.substring(colonIdx + 2).trim()
            if (!potentialTitle.contains("http", ignoreCase = true) && potentialDesc.isNotBlank()) {
                nodes.add(ChangelogNode.FeatureBullet(potentialTitle, potentialDesc))
                continue
            }
        }

        // Pattern 3: Title - Description
        val dashIdx = content.indexOf(" - ")
        if (dashIdx in 2..45) {
            val potentialTitle = content.substring(0, dashIdx).trim().removePrefix("**").removeSuffix("**").trim()
            val potentialDesc = content.substring(dashIdx + 3).trim()
            if (potentialDesc.isNotBlank()) {
                nodes.add(ChangelogNode.FeatureBullet(potentialTitle, potentialDesc))
                continue
            }
        }

        // Fallback: bullet or paragraph
        if (isBullet) {
            nodes.add(ChangelogNode.FeatureBullet(null, content))
        } else {
            nodes.add(ChangelogNode.Paragraph(content))
        }
    }

    if (nodes.isEmpty()) {
        nodes.add(
            ChangelogNode.FeatureBullet(
                title = "Bug Fixes & Improvements",
                description = "General stability enhancements, playback optimization and performance updates."
            )
        )
    }

    return ParsedChangelog(
        photos = extractedPhotos.distinct().take(2),
        nodes = nodes
    )
}

/**
 * Release Media Showcase with Gold Hairline Borders.
 */
@Composable
private fun ReleasePhotoShowcase(
    photos: List<String>,
    modifier: Modifier = Modifier
) {
    if (photos.isEmpty()) return

    val goldBorder = Brush.linearGradient(
        listOf(
            Color(0xFFFFE082).copy(alpha = 0.6f),
            Color(0xFFFFC107).copy(alpha = 0.3f),
            Color(0xFFFFE082).copy(alpha = 0.5f)
        )
    )

    if (photos.size == 1) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(175.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(0.75.dp, goldBorder, RoundedCornerShape(16.dp))
        ) {
            AppAsyncImage(
                model = photos.first(),
                contentDescription = "Release Preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    } else {
        val pagerState = rememberPagerState(pageCount = { photos.size })
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp)
            ) { page ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 2.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(0.75.dp, goldBorder, RoundedCornerShape(16.dp))
                ) {
                    AppAsyncImage(
                        model = photos[page],
                        contentDescription = "Release Preview ${page + 1}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Animated dot indicator pills in gold
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                photos.indices.forEach { index ->
                    val isSelected = pagerState.currentPage == index
                    val dotWidth by animateDpAsState(
                        targetValue = if (isSelected) 18.dp else 5.dp,
                        animationSpec = spring(dampingRatio = 0.76f, stiffness = 650f),
                        label = "dotWidth"
                    )
                    Box(
                        modifier = Modifier
                            .height(5.dp)
                            .width(dotWidth)
                            .clip(CircleShape)
                            .background(if (isSelected) Color(0xFFFFD54F) else Color(0xFFFFD54F).copy(alpha = 0.28f))
                    )
                }
            }
        }
    }
}

/**
 * MOVIE HUB — Premium Black & Gold Cinematic Software Update Popup.
 *
 * Designed with luxury aesthetics:
 * - Obsidian deep black background with soft gold glow halo.
 * - Rounded dialog with subtle multi-stop gold hairline border.
 * - MOVIE HUB official logo badge at the top with ambient gold backlighting.
 * - Heading: "A NEW EXPERIENCE AWAITS" in radiant gold typography.
 * - Side-by-side installed vs available version comparison card.
 * - Display release title and GitHub release notes.
 * - Stylish gold download progress bar with real-time percentage and byte counters.
 * - Clear cinematic buttons: DOWNLOAD UPDATE, LATER, INSTALL UPDATE.
 * - Official branding: MOVIE HUB · Developer: AYUSH.
 */
@Composable
fun UpdateInfoDialog(
    release: ReleaseInfo,
    currentVersion: String,
    downloadState: ApkDownloadState,
    onDismiss: () -> Unit,
    onDownloadAndInstall: (ReleaseInfo) -> Unit,
    onTriggerInstall: (File, String) -> Unit,
    onSkipVersion: (String) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    if (!com.euthopiar.data.update.AppUpdateConfig.IS_UPDATE_SYSTEM_ENABLED) {
        onDismiss()
        return
    }
    val context = LocalContext.current
    val updateManager = remember(context) { AppUpdateManager.getInstance(context) }
    val scrollState = rememberScrollState()
    val isNewerUpdate = remember(currentVersion, release.tagName, release.versionCode) {
        val curCode = updateManager.getCurrentVersionCode()
        isRemoteUpdateAvailable(
            currentVersionCode = curCode,
            remoteVersionCode = release.versionCode,
            currentVersionName = currentVersion,
            remoteVersionName = release.versionName
        )
    }

    val formattedSize = remember(release.apkSizeBytes) {
        if (release.apkSizeBytes > 0L) {
            String.format(Locale.US, "%.1f MB", release.apkSizeBytes / (1024.0 * 1024.0))
        } else null
    }

    val parsedChangelog = remember(release.changelog) {
        parseReleaseChangelog(release.changelog)
    }

    val coroutineScope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val isAmoled = LocalAmoledMode.current
    val navbarVisible = LocalNavbarVisible.current

    DisposableEffect(Unit) {
        navbarVisible.value = false
        onDispose {
            navbarVisible.value = true
        }
    }

    val dialogProgress = remember { Animatable(0f) }
    var isDismissing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (reduceMotion) {
            dialogProgress.snapTo(1f)
        } else {
            dialogProgress.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 550f)
            )
        }
    }

    val dismissWithAnim: () -> Unit = {
        if (!isDismissing) {
            isDismissing = true
            coroutineScope.launch {
                if (reduceMotion) {
                    onDismiss()
                } else {
                    dialogProgress.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 180)
                    )
                    onDismiss()
                }
            }
        }
    }

    BackHandler(enabled = !release.isMandatory) { dismissWithAnim() }

    val animValue = dialogProgress.value
    val scrimAlpha = if (reduceMotion) 0.80f else (0.80f * animValue).coerceIn(0f, 0.80f)
    val cardScale = if (reduceMotion) 1f else 0.94f + 0.06f * animValue
    val cardTranslationY = if (reduceMotion) 0f else (1f - animValue) * 30f
    val cardAlpha = if (reduceMotion) 1f else animValue.coerceIn(0f, 1f)

    val cardShape = RoundedCornerShape(26.dp)
    val cardFill = if (isAmoled) Color.Black.copy(alpha = 0.96f) else Color(0xFF090A0E).copy(alpha = 0.94f)
    val fallbackColor = if (isAmoled) Color.Black else Color(0xFF090A0E)

    // Cinematic Gold Border Brush
    val goldBorderBrush = Brush.verticalGradient(
        listOf(
            Color(0xFFFFDF7A).copy(alpha = 0.75f),
            Color(0xFFFFC107).copy(alpha = 0.40f),
            Color(0xFF8D6E12).copy(alpha = 0.20f),
            Color(0xFFFFD54F).copy(alpha = 0.60f)
        )
    )

    val isDownloading = downloadState is ApkDownloadState.Downloading
    val isReadyToInstall = downloadState is ApkDownloadState.ReadyToInstall

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(100f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    if (!release.isMandatory) {
                        dismissWithAnim()
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // Deep Cinema Scrim backdrop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
        )

        // Soft Gold Ambient Radial Glow behind the Card
        Box(
            modifier = Modifier
                .size(380.dp)
                .graphicsLayer {
                    alpha = cardAlpha * 0.7f
                    scaleX = cardScale
                    scaleY = cardScale
                }
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFFC107).copy(alpha = 0.16f),
                            Color(0xFFFFB300).copy(alpha = 0.06f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Modal Dialog Card
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = cardScale
                    scaleY = cardScale
                    translationY = cardTranslationY
                    alpha = cardAlpha
                }
                .widthIn(max = 410.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .padding(horizontal = 20.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* prevent modal dismiss on card click */ }
                )
                .clip(cardShape)
                .then(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hazeState != null) {
                        Modifier.hazeEffect(state = hazeState) {
                            blurRadius = 24.dp
                            tints = listOf(HazeTint(cardFill))
                            fallbackTint = HazeTint(fallbackColor)
                        }
                    } else {
                        Modifier.background(fallbackColor)
                    }
                )
                .border(
                    width = 1.dp,
                    brush = goldBorderBrush,
                    shape = cardShape
                )
        ) {
            // Main Content Area: Header + Scrollable Changelog
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header Block
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // MOVIE HUB Official Logo Emblem Tile with Gold Aura
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF282215),
                                        Color(0xFF13110C)
                                    )
                                )
                            )
                            .border(
                                width = 1.dp,
                                brush = Brush.linearGradient(
                                    listOf(
                                        Color(0xFFFFDF7A),
                                        Color(0xFFFFC107).copy(alpha = 0.5f),
                                        Color(0xFFFFE082)
                                    )
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_moviehub_logo),
                            contentDescription = "MOVIE HUB Logo",
                            modifier = Modifier.size(34.dp),
                            contentScale = ContentScale.Fit
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Heading: "A NEW EXPERIENCE AWAITS" in radiant gold gradient
                    Text(
                        text = "A NEW EXPERIENCE AWAITS",
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            lineHeight = 22.sp,
                            letterSpacing = 0.12.em,
                            brush = Brush.linearGradient(
                                listOf(
                                    Color(0xFFFFF7DB),
                                    Color(0xFFFFD54F),
                                    Color(0xFFFFB300)
                                )
                            )
                        ),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Subtitle / Developer Attribution
                    Text(
                        text = "MOVIE HUB OFFICIAL UPDATE · DEVELOPER: AYUSH",
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.5.sp,
                            letterSpacing = 0.10.em,
                            color = Color(0xFFFFD54F).copy(alpha = 0.75f)
                        ),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Version Comparison Module: Installed vs Available
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF12131A))
                            .border(
                                width = 0.5.dp,
                                color = Color(0xFFFFD54F).copy(alpha = 0.25f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Current Installed Version
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(
                                    text = "INSTALLED",
                                    style = TextStyle(
                                        fontFamily = AppFontFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 9.5.sp,
                                        letterSpacing = 0.08.em,
                                        color = Color.White.copy(alpha = 0.45f)
                                    )
                                )
                                Text(
                                    text = "v$currentVersion",
                                    style = TextStyle(
                                        fontFamily = AppFontFamily,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.5.sp,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                )
                            }

                            // Directional Golden Arrow
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF262012))
                                    .border(0.5.dp, Color(0xFFFFD54F).copy(alpha = 0.40f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "➔",
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        color = Color(0xFFFFD54F)
                                    )
                                )
                            }

                            // Available Remote Version
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "AVAILABLE",
                                    style = TextStyle(
                                        fontFamily = AppFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.5.sp,
                                        letterSpacing = 0.08.em,
                                        color = Color(0xFFFFD54F)
                                    )
                                )
                                Text(
                                    text = "v${release.versionName}",
                                    style = TextStyle(
                                        fontFamily = AppFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color(0xFFFFF2B2)
                                    )
                                )
                            }
                        }
                    }

                    // Metadata Subtext (Size & Date)
                    val metadataLine = buildString {
                        if (formattedSize != null) {
                            append(formattedSize)
                            append(" · ")
                        }
                        append("Released ")
                        append(release.publishedAt)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = metadataLine,
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 11.5.sp,
                            color = Color.White.copy(alpha = 0.45f)
                        ),
                        textAlign = TextAlign.Center
                    )
                }

                // Subtle Gold Divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color(0xFFFFD54F).copy(alpha = 0.30f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                // Scrollable Changelog Body
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                ) {
                    // Release Photos Showcase (if present)
                    if (parsedChangelog.photos.isNotEmpty()) {
                        ReleasePhotoShowcase(
                            photos = parsedChangelog.photos,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }

                    // Release Title Block
                    if (release.releaseTitle.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(14.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFFD54F))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = release.releaseTitle,
                                style = TextStyle(
                                    fontFamily = AppFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = Color.White
                                )
                            )
                        }
                    }

                    // "WHAT'S NEW" section header
                    Text(
                        text = "WHAT'S NEW",
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 0.12.em,
                            color = Color(0xFFFFD54F).copy(alpha = 0.85f)
                        ),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Structured Changelog Items
                    parsedChangelog.nodes.forEach { node ->
                        when (node) {
                            is ChangelogNode.Header -> {
                                Text(
                                    text = node.title,
                                    style = TextStyle(
                                        fontFamily = AppFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.5.sp,
                                        lineHeight = 19.sp,
                                        color = Color(0xFFFFF2B2)
                                    ),
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                                )
                            }
                            is ChangelogNode.FeatureBullet -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.5.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = "◆",
                                        style = TextStyle(
                                            fontFamily = AppFontFamily,
                                            fontSize = 8.sp,
                                            color = Color(0xFFFFD54F).copy(alpha = 0.85f)
                                        ),
                                        modifier = Modifier.padding(end = 8.dp, top = 4.dp)
                                    )
                                    val annotated = buildAnnotatedString {
                                        if (!node.title.isNullOrBlank()) {
                                            withStyle(
                                                SpanStyle(
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color.White
                                                )
                                            ) {
                                                append(node.title)
                                                append(": ")
                                            }
                                        }
                                        withStyle(
                                            SpanStyle(
                                                fontWeight = FontWeight.Normal,
                                                color = Color.White.copy(alpha = 0.72f)
                                            )
                                        ) {
                                            append(node.description)
                                        }
                                    }
                                    Text(
                                        text = annotated,
                                        style = TextStyle(
                                            fontFamily = AppFontFamily,
                                            fontSize = 13.sp,
                                            lineHeight = 18.5.sp
                                        )
                                    )
                                }
                            }
                            is ChangelogNode.Paragraph -> {
                                Text(
                                    text = node.text,
                                    style = TextStyle(
                                        fontFamily = AppFontFamily,
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 13.sp,
                                        lineHeight = 18.5.sp,
                                        color = Color.White.copy(alpha = 0.72f)
                                    ),
                                    modifier = Modifier.padding(vertical = 3.5.dp)
                                )
                            }
                        }
                    }

                    // Bottom padding spacer to clear the pinned action dock
                    Spacer(modifier = Modifier.height(130.dp))
                }
            }

            // Pinned Bottom Action Dock over Upward Black Gradient Mask
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                cardFill.copy(alpha = 0.75f),
                                cardFill.copy(alpha = 0.98f),
                                cardFill
                            )
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Stylish Download Progress Bar (when Downloading)
                    if (downloadState is ApkDownloadState.Downloading) {
                        val percent = (downloadState.progressFraction * 100).toInt()
                        val curMb = downloadState.downloadedBytes / (1024.0 * 1024.0)
                        val totMb = downloadState.totalBytes / (1024.0 * 1024.0)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF151410))
                                .border(
                                    width = 0.5.dp,
                                    color = Color(0xFFFFD54F).copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFFFD54F))
                                    )
                                    Text(
                                        text = "DOWNLOADING PATCH",
                                        style = TextStyle(
                                            fontFamily = AppFontFamily,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 11.sp,
                                            letterSpacing = 0.08.em,
                                            color = Color(0xFFFFD54F)
                                        )
                                    )
                                }
                                Text(
                                    text = "$percent%",
                                    style = TextStyle(
                                        fontFamily = AppFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color(0xFFFFF2B2)
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Custom Gold Gradient Progress Bar
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF262319))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction = downloadState.progressFraction.coerceIn(0.01f, 1f))
                                        .fillMaxHeight()
                                        .clip(CircleShape)
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    Color(0xFFFFF2B2),
                                                    Color(0xFFFFD54F),
                                                    Color(0xFFFF9800)
                                                )
                                            )
                                        )
                                )
                            }

                            if (downloadState.totalBytes > 0L) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = String.format(Locale.US, "%.1f MB / %.1f MB", curMb, totMb),
                                    style = TextStyle(
                                        fontFamily = AppFontFamily,
                                        fontSize = 10.5.sp,
                                        color = Color.White.copy(alpha = 0.50f)
                                    ),
                                    modifier = Modifier.align(Alignment.End)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Download Error Notice (when Error)
                    if (downloadState is ApkDownloadState.Error) {
                        Text(
                            text = "Download issue: ${downloadState.message}",
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 11.5.sp,
                                color = Color(0xFFFF6B6B)
                            ),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    // Primary Action Button: Metallic Gold Gradient Pill
                    val primaryButtonBrush = if (isDownloading) {
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFFFFD54F).copy(alpha = 0.45f),
                                Color(0xFFFFB300).copy(alpha = 0.45f)
                            )
                        )
                    } else if (!isNewerUpdate && !isReadyToInstall && downloadState !is ApkDownloadState.Error) {
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF262012),
                                Color(0xFF1E190E)
                            )
                        )
                    } else {
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFFFFE57F),
                                Color(0xFFFFC107),
                                Color(0xFFFFA000)
                            )
                        )
                    }

                    val isPrimaryActive = isNewerUpdate || isReadyToInstall || downloadState is ApkDownloadState.Error

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(CircleShape)
                            .background(primaryButtonBrush)
                            .border(
                                width = 0.5.dp,
                                color = if (isPrimaryActive) Color(0xFFFFF2B2).copy(alpha = 0.60f) else Color(0xFFFFD54F).copy(alpha = 0.35f),
                                shape = CircleShape
                            )
                            .settingsPressable(
                                enabled = !isDownloading,
                                onClick = {
                                    if (isReadyToInstall) {
                                        onTriggerInstall((downloadState as ApkDownloadState.ReadyToInstall).apkFile, release.htmlUrl)
                                    } else if (downloadState is ApkDownloadState.Error) {
                                        onDownloadAndInstall(release)
                                    } else if (isNewerUpdate && release.apkDownloadUrl != null) {
                                        onDownloadAndInstall(release)
                                    } else if (!isNewerUpdate) {
                                        dismissWithAnim()
                                    } else {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))
                                            context.startActivity(intent)
                                        } catch (_: Exception) {}
                                    }
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (isDownloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color(0xFF141005),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "DOWNLOADING UPDATE...",
                                    style = TextStyle(
                                        fontFamily = AppFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        letterSpacing = 0.08.em,
                                        color = Color(0xFF141005)
                                    )
                                )
                            } else {
                                val primaryText = when {
                                    isReadyToInstall -> "INSTALL UPDATE"
                                    downloadState is ApkDownloadState.Error -> "RETRY DOWNLOAD"
                                    isNewerUpdate && release.apkDownloadUrl != null -> "DOWNLOAD UPDATE"
                                    !isNewerUpdate -> "ALREADY UP TO DATE"
                                    else -> "VIEW RELEASE PAGE"
                                }
                                val primaryTextColor = if (isPrimaryActive) Color(0xFF141005) else Color(0xFFFFD54F)
                                Text(
                                    text = primaryText,
                                    style = TextStyle(
                                        fontFamily = AppFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        letterSpacing = 0.08.em,
                                        color = primaryTextColor
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Secondary Action Button: "LATER" (Translucent Gold Pill)
                    if (!release.isMandatory) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .border(
                                        width = 0.5.dp,
                                        color = Color(0xFFFFD54F).copy(alpha = 0.35f),
                                        shape = CircleShape
                                    )
                                    .background(Color(0xFF1A1710))
                                    .settingsPressable(onClick = dismissWithAnim)
                                    .padding(horizontal = 24.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "LATER",
                                    style = TextStyle(
                                        fontFamily = AppFontFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.5.sp,
                                        letterSpacing = 0.08.em,
                                        color = Color(0xFFFFD54F)
                                    )
                                )
                            }

                            if (isNewerUpdate) {
                                Spacer(modifier = Modifier.width(12.dp))

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .settingsPressable(
                                            onClick = {
                                                onSkipVersion(release.versionName)
                                                dismissWithAnim()
                                            }
                                        )
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Skip version",
                                        style = TextStyle(
                                            fontFamily = AppFontFamily,
                                            fontWeight = FontWeight.Normal,
                                            fontSize = 11.5.sp,
                                            color = Color.White.copy(alpha = 0.40f)
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        // Notice for Mandatory Updates
                        Text(
                            text = "This update contains essential security or platform fixes and is required.",
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 11.sp,
                                color = Color(0xFFFFD54F).copy(alpha = 0.65f)
                            ),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
