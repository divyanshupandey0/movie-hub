package com.euthopiar.ui.components

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.euthopiar.data.ota.OtaConfigManager
import com.euthopiar.ui.theme.AppFontFamily
import com.euthopiar.ui.theme.Dimens
import com.euthopiar.ui.theme.LocalAmoledMode
import com.euthopiar.ui.theme.Motion
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

@Composable
fun OtaAnnouncementBanner(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    val context = LocalContext.current
    val otaManager = remember(context) { OtaConfigManager.getInstance(context) }
    val config by otaManager.config.collectAsState()
    val dismissedIds by otaManager.dismissedAnnouncementIds.collectAsState()

    val isAmoled = LocalAmoledMode.current
    val bannerFill = if (isAmoled) Color.Black.copy(alpha = 0.88f) else Color(0xFF14151F).copy(alpha = 0.80f)
    val fallbackFill = if (isAmoled) Color.Black.copy(alpha = 0.94f) else Color(0xFF14151F).copy(alpha = 0.94f)

    val maintenance = config.maintenance
    val announcement = config.announcement

    // 1. Maintenance Notice takes highest priority if active
    val isMaintenanceActive = maintenance.enabled && maintenance.message.isNotBlank()
    // 2. Otherwise show Announcement if enabled and not dismissed
    val isAnnouncementActive = !isMaintenanceActive &&
            announcement.enabled &&
            announcement.message.isNotBlank() &&
            !dismissedIds.contains(announcement.id)

    val isVisible = isMaintenanceActive || isAnnouncementActive

    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically(animationSpec = tween(Motion.DurationStandard)) + fadeIn(animationSpec = tween(Motion.DurationStandard)),
        exit = shrinkVertically(animationSpec = tween(Motion.DurationStandard)) + fadeOut(animationSpec = tween(Motion.DurationStandard)),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.spacing16, vertical = Dimens.spacing4),
            contentAlignment = Alignment.Center
        ) {
            val shape = RoundedCornerShape(14.dp)
            val borderBrush = if (isMaintenanceActive) {
                Brush.horizontalGradient(
                    listOf(Color(0xFFFFB74D).copy(alpha = 0.70f), Color(0xFFFF9800).copy(alpha = 0.40f))
                )
            } else {
                Brush.horizontalGradient(
                    listOf(Color(0xFFFFD54F).copy(alpha = 0.60f), Color(0xFFFFB300).copy(alpha = 0.30f))
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .then(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hazeState != null) {
                            Modifier.hazeEffect(state = hazeState) {
                                blurRadius = 16.dp
                                tints = listOf(HazeTint(bannerFill))
                                fallbackTint = HazeTint(fallbackFill)
                            }
                        } else {
                            Modifier
                                .background(fallbackFill)
                                .background(Color.White.copy(alpha = 0.05f))
                        }
                    )
                    .border(width = 0.8.dp, brush = borderBrush, shape = shape)
                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (isMaintenanceActive) Icons.Outlined.WarningAmber else Icons.Outlined.Campaign,
                    contentDescription = null,
                    tint = if (isMaintenanceActive) Color(0xFFFFB74D) else Color(0xFFFFD54F),
                    modifier = Modifier.size(20.dp)
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val titleText = if (isMaintenanceActive) maintenance.title else announcement.title
                    val messageText = if (isMaintenanceActive) maintenance.message else announcement.message

                    if (titleText.isNotBlank()) {
                        Text(
                            text = titleText,
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    }

                    Text(
                        text = messageText,
                        fontFamily = AppFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!isMaintenanceActive && announcement.actionLabel.isNotBlank() && announcement.actionUrl.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFFFD54F).copy(alpha = 0.15f))
                            .border(0.5.dp, Color(0xFFFFD54F).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .clickable {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(announcement.actionUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = announcement.actionLabel,
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            color = Color(0xFFFFD54F)
                        )
                    }
                }

                if (!isMaintenanceActive && announcement.dismissible) {
                    IconButton(
                        onClick = { otaManager.dismissAnnouncement(announcement.id) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Dismiss",
                            tint = Color.White.copy(alpha = 0.60f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
