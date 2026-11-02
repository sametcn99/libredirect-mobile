package dev.libredirect.mobile.ui.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val MAX_TAB_COUNT_LABEL = 99
private const val TWO_DIGIT_TAB_COUNT = 10
private const val ICON_LETTER_SCALE = 0.45f
private const val FAVICON_CORNER_DIVISOR = 4

/**
 * The page's favicon when one has been received, otherwise a monogram of the
 * site's host on a tinted circle.
 */
@Composable
fun SiteIcon(
    tab: BrowserTab?,
    size: Dp,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    val favicon = tab?.favicon
    if (favicon != null) {
        val image = remember(favicon) { favicon.asImageBitmap() }
        Image(
            bitmap = image,
            contentDescription = null,
            modifier =
                modifier
                    .size(size)
                    .clip(RoundedCornerShape(size / FAVICON_CORNER_DIVISOR)),
        )
    } else {
        val letter =
            tab
                ?.displayHost
                ?.firstOrNull { it.isLetterOrDigit() }
                ?.uppercaseChar()
                ?.toString()
                .orEmpty()
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(containerColor),
        ) {
            Text(
                text = letter,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * ICON_LETTER_SCALE).sp,
            )
        }
    }
}

/** Outlined rounded square with the number of open tabs, as in most mobile browsers. */
@Composable
fun TabCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(22.dp)
                .border(1.75.dp, color, shape)
                .clearAndSetSemantics {},
    ) {
        Text(
            text = if (count > MAX_TAB_COUNT_LABEL) ":D" else count.toString(),
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = if (count >= TWO_DIGIT_TAB_COUNT) 10.sp else 12.sp,
        )
    }
}
