package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Marks a panel or tab whose plugin is not running the released build.
 *
 * Renders nothing for a store build, so the tag's presence is the whole signal. Clicking it offers
 * to install the store version.
 *
 * Colours follow the design system's split: `signalWash` is the fill (an accent at home on the
 * panel background) and `signalText` is the glyph. Using `signal` itself as a text colour fails
 * contrast in the Blueprint theme, which is why it is not used here.
 */
@Composable
fun PluginBuildTag(
    info: PluginBuildInfo?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val label = info?.tagLabel ?: return
    Text(
        text = label,
        color = BossTheme.colors.signalText,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier =
            modifier
                .clip(RoundedCornerShape(3.dp))
                .background(BossTheme.colors.signalWash)
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 4.dp, vertical = 1.dp)
                // The pill itself is four characters; the version it stands for is the useful part,
                // so that is what a screen reader and a UI test get.
                .semantics { contentDescription = "${info.description}: ${info.displayVersion}" },
    )
}
