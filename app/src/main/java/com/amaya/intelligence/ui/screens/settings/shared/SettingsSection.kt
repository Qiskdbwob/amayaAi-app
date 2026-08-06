package com.amaya.intelligence.ui.screens.settings.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import com.amaya.intelligence.ui.screens.amaya.AmayaGroupedSettingsTokens
import com.amaya.intelligence.ui.screens.amaya.iosAmayaColors

@Composable
fun SettingsSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = iosAmayaColors()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AmayaGroupedSettingsTokens.sectionHeaderSpacing)
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = colors.headerText,
            modifier = Modifier.padding(start = AmayaGroupedSettingsTokens.sectionHeaderStartPadding)
        )
        Surface(
            shape = RoundedCornerShape(AmayaGroupedSettingsTokens.sectionCornerRadius),
            color = colors.groupSurface,
            border = BorderStroke(AmayaGroupedSettingsTokens.sectionBorderWidth, colors.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = content)
        }
    }
}
