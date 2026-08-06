package com.amaya.intelligence.ui.screens.amaya

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amaya.intelligence.ui.components.shared.SettingsBackButton
import com.amaya.intelligence.ui.screens.settings.shared.SettingsSectionCard
import com.amaya.intelligence.ui.theme.LocalAmayaGradients

data class IosAmayaColors(
    val groupedBackground: Color,
    val groupSurface: Color,
    val border: Color,
    val iconBackground: Color,
    val iconTint: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val separator: Color,
    val headerText: Color,
    val tagBackground: Color
)

object AmayaGroupedSettingsTokens {
    val contentHorizontalPadding = 20.dp
    val sectionSpacing = 22.dp
    val sectionHeaderStartPadding = 16.dp
    val sectionHeaderSpacing = 7.dp
    val sectionCornerRadius = 16.dp
    val sectionBorderWidth = 0.7.dp
    val rowHorizontalPadding = 16.dp
    val rowVerticalPadding = 10.dp
    val rowIconSize = 32.dp
    val rowIconGlyphSize = 17.dp
    val rowIconTextGap = 12.dp
    val rowChevronSize = 18.dp
    val rowDividerStartPadding = 58.dp
    val rowTextSpacing = 6.dp
    val inlineTextSpacing = 2.dp
    val screenContentTopSpacer = 52.dp
    val screenContentBottomSpacer = 100.dp
    val bottomTabBarContentClearance = 110.dp
    val floatingActionButtonContentClearance = 120.dp
    val topAppBarHeight = 64.dp
    val topBarContentSpacing = 22.dp
    val topBarHorizontalPadding = 12.dp
    val topBarTitleStartPadding = 12.dp
    val floatingActionButtonInset = 16.dp
    val floatingActionButtonAboveTabBarInset = 88.dp
    val emptyStateScreenTopSpacing = 80.dp
    val emptyStateListTopSpacing = 100.dp
    val emptyStateTabTopSpacing = 60.dp
    val emptyStateContentPadding = 40.dp
    val emptyStateIconSize = 72.dp
    val emptyStateIconGlyphSize = 36.dp
    val emptyStateIconGap = 24.dp
    val emptyStateTitleGap = 6.dp
    val emptyStateActionGap = 20.dp
    val topScrimHeight = 170.dp
}

@Composable
fun iosAmayaColors(): IosAmayaColors {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        IosAmayaColors(
            groupedBackground = Color(0xFF0B0B0F),
            groupSurface = Color(0xFF1C1C1E),
            border = Color.White.copy(alpha = 0.10f),
            iconBackground = Color(0xFF2C2C2E),
            iconTint = Color(0xFFC7C7CC),
            primaryText = Color(0xFFF2F2F7),
            secondaryText = Color(0xFFEBEBF5).copy(alpha = 0.60f),
            separator = Color.White.copy(alpha = 0.10f),
            headerText = Color(0xFFEBEBF5).copy(alpha = 0.48f),
            tagBackground = Color.White.copy(alpha = 0.08f)
        )
    } else {
        IosAmayaColors(
            groupedBackground = Color(0xFFF2F2F7),
            groupSurface = Color.White,
            border = Color.Black.copy(alpha = 0.08f),
            iconBackground = Color(0xFFE9E9EE),
            iconTint = Color(0xFF5F6368),
            primaryText = Color(0xFF1C1C1E),
            secondaryText = Color(0xFF3C3C43).copy(alpha = 0.62f),
            separator = Color(0xFF3C3C43).copy(alpha = 0.13f),
            headerText = Color(0xFF3C3C43).copy(alpha = 0.52f),
            tagBackground = Color.Black.copy(alpha = 0.08f)
        )
    }
}

@Composable
fun AmayaSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = iosAmayaColors()
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = colors.groupSurface,
            uncheckedTrackColor = colors.iconBackground,
            disabledCheckedThumbColor = colors.secondaryText.copy(alpha = 0.38f),
            disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            disabledUncheckedThumbColor = colors.groupSurface.copy(alpha = 0.38f),
            disabledUncheckedTrackColor = colors.iconBackground.copy(alpha = 0.12f)
        )
    )
}

@Composable
fun AmayaTopScrim(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(AmayaGroupedSettingsTokens.topScrimHeight)
            .background(LocalAmayaGradients.current.topScrim)
    )
}

fun Modifier.amayaFloatingActionButtonBottomPadding(): Modifier =
    navigationBarsPadding().padding(bottom = AmayaGroupedSettingsTokens.floatingActionButtonInset)

fun Modifier.amayaFloatingActionButtonAboveTabBarPadding(): Modifier =
    navigationBarsPadding().padding(bottom = AmayaGroupedSettingsTokens.floatingActionButtonAboveTabBarInset)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmayaScaffold(
    title: String,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = iosAmayaColors()
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxSize().background(colors.groupedBackground)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AmayaGroupedSettingsTokens.contentHorizontalPadding),
                verticalArrangement = Arrangement.spacedBy(AmayaGroupedSettingsTokens.sectionSpacing)
            ) {
                Spacer(
                    Modifier
                        .statusBarsPadding()
                        .height(AmayaGroupedSettingsTokens.screenContentTopSpacer)
                )
                content()
                Spacer(Modifier.height(AmayaGroupedSettingsTokens.screenContentBottomSpacer))
            }

            AmayaTopScrim(Modifier.align(Alignment.TopCenter))

            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp)) },
                navigationIcon = { SettingsBackButton(onClick = onNavigateBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                modifier = Modifier.statusBarsPadding().padding(start = 12.dp, end = 12.dp),
                windowInsets = WindowInsets(0.dp)
            )
        }
    }
}

@Composable
fun AmayaNavigationRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = iosAmayaColors()
    Surface(onClick = onClick, color = Color.Transparent, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(
                horizontal = AmayaGroupedSettingsTokens.rowHorizontalPadding,
                vertical = AmayaGroupedSettingsTokens.rowVerticalPadding
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AmayaGroupedIcon(icon = icon, colors = colors)
            Spacer(Modifier.width(AmayaGroupedSettingsTokens.rowIconTextGap))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        lineHeight = 19.sp
                    ),
                    color = colors.primaryText,
                    maxLines = 1
                )
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 12.5.sp,
                            lineHeight = 16.sp
                        ),
                        color = colors.secondaryText,
                        maxLines = 2
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = colors.secondaryText.copy(alpha = 0.55f),
                modifier = Modifier.size(AmayaGroupedSettingsTokens.rowChevronSize)
            )
        }
    }
}

@Composable
fun AmayaSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val colors = iosAmayaColors()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AmayaGroupedSettingsTokens.rowHorizontalPadding,
                vertical = AmayaGroupedSettingsTokens.rowVerticalPadding
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    lineHeight = 19.sp
                ),
                color = if (enabled) colors.primaryText else colors.secondaryText.copy(alpha = 0.72f)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp
                ),
                color = if (enabled) colors.secondaryText else colors.secondaryText.copy(alpha = 0.72f)
            )
        }
        Spacer(Modifier.width(AmayaGroupedSettingsTokens.rowHorizontalPadding))
        AmayaSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
fun AmayaStatusRow(
    title: String,
    value: String,
    subtitle: String? = null
) {
    val colors = iosAmayaColors()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AmayaGroupedSettingsTokens.rowHorizontalPadding,
                vertical = AmayaGroupedSettingsTokens.rowVerticalPadding
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    lineHeight = 19.sp
                ),
                color = colors.primaryText
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp
                    ),
                    color = colors.secondaryText
                )
            }
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun AmayaDivider() {
    val colors = iosAmayaColors()
    HorizontalDivider(
        modifier = Modifier.padding(start = AmayaGroupedSettingsTokens.rowDividerStartPadding),
        color = colors.separator,
        thickness = AmayaGroupedSettingsTokens.sectionBorderWidth
    )
}

@Composable
private fun AmayaGroupedIcon(icon: ImageVector, colors: IosAmayaColors) {
    Box(
        modifier = Modifier
            .size(AmayaGroupedSettingsTokens.rowIconSize)
            .clip(CircleShape)
            .background(colors.iconBackground),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.iconTint,
            modifier = Modifier.size(AmayaGroupedSettingsTokens.rowIconGlyphSize)
        )
    }
}

@Composable
fun AmayaSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    SettingsSectionCard(title = title, content = content)
}
