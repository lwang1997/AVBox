package com.github.avbox.osc.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.SiteHealth

/** 三色状态：良好绿、偏慢黄、异常红；未采样不显示，避免出现第四种颜色。 */
@Composable
fun SiteHealthBadge(health: SiteHealth?, modifier: Modifier = Modifier) {
    val state = health?.state ?: return
    val label = when (state) {
        SiteHealth.State.GOOD -> stringResource(R.string.site_health_ok)
        SiteHealth.State.SLOW -> stringResource(R.string.site_health_warn)
        SiteHealth.State.BAD -> stringResource(R.string.site_health_bad)
        SiteHealth.State.UNKNOWN -> return
    }
    val colors = when (state) {
        SiteHealth.State.GOOD -> Color(0xFFE8F5E9) to Color(0xFF1B5E20)
        SiteHealth.State.SLOW -> Color(0xFFFFF8E1) to Color(0xFF7A5B00)
        SiteHealth.State.BAD -> Color(0xFFFFEBEE) to Color(0xFFB71C1C)
        SiteHealth.State.UNKNOWN -> return
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = colors.first,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.second,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
