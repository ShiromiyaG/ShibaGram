package com.shirou.shibagram.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.rounded.PlayCircleFilled
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shirou.shibagram.data.dto.MediaCardData

/**
 * Material Design 3 Media Card with hover effects and polished visuals.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaCard(
    mediaCardData: MediaCardData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailPainter: Painter? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.03f else 1f,
        animationSpec = tween(durationMillis = 200)
    )
    val elevation by animateFloatAsState(
        targetValue = if (isHovered) 8f else 2f,
        animationSpec = tween(durationMillis = 200)
    )
    
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .scale(scale)
            .shadow(elevation.dp, RoundedCornerShape(12.dp))
            .hoverable(interactionSource),
        interactionSource = interactionSource,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Thumbnail
            if (thumbnailPainter != null) {
                Image(
                    painter = thumbnailPainter,
                    contentDescription = mediaCardData.videoTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Moved up a bit so the title overlay doesn't cover it
                PlaceholderThumbnail(mediaCardData, Modifier.fillMaxSize().padding(bottom = 40.dp))
            }
            
            // Watched videos are dimmed so what's left to watch stands out
            if (mediaCardData.isWatched) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            }

            // Bottom gradient overlay — taller for better readability
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.55f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.45f),
                                Color.Black.copy(alpha = 0.80f)
                            )
                        )
                    )
            )
            
            // Hover play button overlay
            if (isHovered && thumbnailPainter != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayCircleFilled,
                        contentDescription = "Play",
                        modifier = Modifier.size(56.dp),
                        tint = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
            
            // Quality badge — pill shaped
            if (mediaCardData.qualityLabel.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
                ) {
                    Text(
                        text = mediaCardData.qualityLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            
            // Watched indicator, or a "NEW" badge for recent unwatched posts
            if (mediaCardData.isWatched) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Watched",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            } else if (mediaCardData.isNew && !mediaCardData.hasProgress) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.tertiary
                ) {
                    Text(
                        text = "NEW",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            
            // Duration badge — rounded pill
            if (mediaCardData.durationText.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.75f)
                ) {
                    Text(
                        text = mediaCardData.durationText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            
            // Title and info at bottom — better spacing
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 12.dp, bottom = 10.dp, end = 70.dp)
            ) {
                Text(
                    text = mediaCardData.videoTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                if (mediaCardData.formattedSize.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = mediaCardData.formattedSize,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                }
            }
            
            // Progress bar — slightly thicker, with rounded ends
            if (mediaCardData.hasProgress && mediaCardData.progressRatio > 0 && !mediaCardData.isWatched) {
                LinearProgressIndicator(
                    progress = { mediaCardData.progressRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .align(Alignment.BottomCenter)
                        .clip(RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.25f)
                )
            }
        }
    }
}

/**
 * Compact version of Media Card for list views — with hover and better info layout.
 */
@Composable
fun MediaCardCompact(
    mediaCardData: MediaCardData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailPainter: Painter? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val elevation by animateFloatAsState(
        targetValue = if (isHovered) 4f else 1f,
        animationSpec = tween(durationMillis = 150)
    )
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp)
            .shadow(elevation.dp, RoundedCornerShape(10.dp))
            .hoverable(interactionSource)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHovered) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize()
        ) {
            // Thumbnail — slightly wider
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
            ) {
                if (thumbnailPainter != null) {
                    Image(
                        painter = thumbnailPainter,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    PlaceholderThumbnail(mediaCardData, Modifier.fillMaxSize())
                }
                
                // Hover play overlay
                if (isHovered && thumbnailPainter != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayCircleFilled,
                            contentDescription = "Play",
                            modifier = Modifier.size(36.dp),
                            tint = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
                
                // Duration
                if (mediaCardData.durationText.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp),
                        shape = RoundedCornerShape(5.dp),
                        color = Color.Black.copy(alpha = 0.75f)
                    ) {
                        Text(
                            text = mediaCardData.durationText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                
                // Progress (watched videos show a check instead)
                if (mediaCardData.hasProgress && !mediaCardData.isWatched) {
                    LinearProgressIndicator(
                        progress = { mediaCardData.progressRatio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.BottomCenter),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Black.copy(alpha = 0.3f)
                    )
                }
            }
            
            // Info — improved spacing and hierarchy
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = mediaCardData.videoTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (mediaCardData.qualityLabel.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = mediaCardData.qualityLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        
                        if (mediaCardData.formattedSize.isNotEmpty()) {
                            Text(
                                text = mediaCardData.formattedSize,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                if (mediaCardData.isWatched) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Watched",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dense one-line row for the Compact viewing mode: small thumbnail, title, metadata, progress.
 */
@Composable
fun MediaRowDense(
    mediaCardData: MediaCardData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailPainter: Painter? = null,
    index: Int? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .hoverable(interactionSource)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        color = if (isHovered) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (index != null) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp)
                )
            }
            Box(
                modifier = Modifier
                    .width(92.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (thumbnailPainter != null) {
                    Image(thumbnailPainter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    PlaceholderThumbnail(mediaCardData, Modifier.fillMaxSize(), compact = true)
                }
                if (mediaCardData.hasProgress && !mediaCardData.isWatched) {
                    LinearProgressIndicator(
                        progress = { mediaCardData.progressRatio },
                        modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Black.copy(alpha = 0.3f)
                    )
                }
            }
            Text(
                text = mediaCardData.videoTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (mediaCardData.isWatched) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (mediaCardData.qualityLabel.isNotEmpty()) {
                Text(mediaCardData.qualityLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                mediaCardData.formattedSize,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(64.dp)
            )
            Text(
                mediaCardData.durationText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(56.dp)
            )
            Box(modifier = Modifier.width(20.dp)) {
                if (mediaCardData.isWatched) {
                    Icon(Icons.Default.CheckCircle, "Watched", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * Placeholder for videos that have no thumbnail on Telegram (common for MKV/document uploads):
 * a gradient whose color comes from the series name (digits removed, so every episode of a show
 * shares the same color) plus the file format.
 */
@Composable
fun PlaceholderThumbnail(mediaCardData: MediaCardData, modifier: Modifier = Modifier, compact: Boolean = false) {
    val message = mediaCardData.mediaMessage
    val seriesKey = remember(message.id) { mediaCardData.videoTitle.filter { !it.isDigit() }.lowercase() }
    val hue = remember(seriesKey) { ((seriesKey.hashCode().toLong() and 0xffffffffL) % 360).toFloat() }
    val extension = remember(message.id) {
        message.filename?.substringAfterLast('.', "")?.uppercase()?.takeIf { it.length in 2..4 }
            ?: message.mimeType.substringAfter('/', "").uppercase().takeIf { it.length in 2..4 }
            ?: "VIDEO"
    }
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(Color.hsl(hue, 0.45f, 0.30f), Color.hsl((hue + 40f) % 360f, 0.40f, 0.16f))
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        if (compact) {
            Text(extension, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.PlayCircleFilled,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = Color.White.copy(alpha = 0.55f)
                )
                Spacer(Modifier.height(4.dp))
                Text(extension, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}
