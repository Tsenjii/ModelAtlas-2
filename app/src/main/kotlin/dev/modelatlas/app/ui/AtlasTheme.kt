package dev.modelatlas.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.modelatlas.app.data.AppearanceSettings
import dev.modelatlas.app.data.InterfaceStyle

/** 第一階段共用視覺契約；以系統字型、實色表面及原生 ripple 避免額外繪製成本。 */
object AtlasSpace {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val touch = 48.dp
    val action = 56.dp
    val icon = 24.dp
}

object AtlasRadius {
    val field = RoundedCornerShape(16.dp)
    val card = RoundedCornerShape(24.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val pill = RoundedCornerShape(50)
}

private val AtlasLightColors = lightColorScheme(
    primary = Color(0xFF006B5E), onPrimary = Color.White,
    primaryContainer = Color(0xFFD5F3EA), onPrimaryContainer = Color(0xFF004D43),
    secondary = Color(0xFF495C74), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4EAF4), onSecondaryContainer = Color(0xFF293C54),
    tertiary = Color(0xFF795500), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEDC5), onTertiaryContainer = Color(0xFF594000),
    background = Color(0xFFF3F6F5), onBackground = Color(0xFF182321),
    surface = Color(0xFFFCFDFC), onSurface = Color(0xFF182321),
    surfaceVariant = Color(0xFFE8EEEB), onSurfaceVariant = Color(0xFF4B5C55),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F8F6),
    surfaceContainer = Color(0xFFEDF2EF), surfaceContainerHigh = Color(0xFFE7EEEA),
    surfaceContainerHighest = Color(0xFFDEE7E2),
    outline = Color(0xFF6B7C74), outlineVariant = Color(0xFFCFDAD4),
    error = Color(0xFFAD302C), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF731C18),
    inverseSurface = Color(0xFF29332F), inverseOnSurface = Color(0xFFEDF3EF),
    inversePrimary = Color(0xFF83DCC7), surfaceTint = Color(0xFF006B5E),
)

private val AtlasDarkColors = darkColorScheme(
    primary = Color(0xFF83DCC7), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF173F36), onPrimaryContainer = Color(0xFFAAF0DC),
    secondary = Color(0xFFB2C6E3), onSecondary = Color(0xFF1C314B),
    secondaryContainer = Color(0xFF29394F), onSecondaryContainer = Color(0xFFD9E6FA),
    tertiary = Color(0xFFF0CB7C), onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF48391B), onTertiaryContainer = Color(0xFFFFE2A3),
    background = Color(0xFF0B100E), onBackground = Color(0xFFE5ECE8),
    surface = Color(0xFF111915), onSurface = Color(0xFFE5ECE8),
    surfaceVariant = Color(0xFF25332C), onSurfaceVariant = Color(0xFFB6C6BC),
    surfaceContainerLowest = Color(0xFF080D0A), surfaceContainerLow = Color(0xFF141D18),
    surfaceContainer = Color(0xFF19241D), surfaceContainerHigh = Color(0xFF202D25),
    surfaceContainerHighest = Color(0xFF29372F),
    outline = Color(0xFF81978A), outlineVariant = Color(0xFF34473B),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF512421), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE0E9E3), inverseOnSurface = Color(0xFF26342B),
    inversePrimary = Color(0xFF006B5E), surfaceTint = Color(0xFF83DCC7),
)

private val AquaLightColors = AtlasLightColors.copy(
    primary = Color(0xFF14698D), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EFFA), onPrimaryContainer = Color(0xFF0D445D),
    secondary = Color(0xFF496B80), secondaryContainer = Color(0xFFE0EFF8),
    onSecondaryContainer = Color(0xFF24465C),
    background = Color(0xFFEAF5FC), onBackground = Color(0xFF152F45),
    surface = Color(0xFFF7FCFF), onSurface = Color(0xFF152F45),
    surfaceVariant = Color(0xFFE0EDF6), onSurfaceVariant = Color(0xFF425D70),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEFF8FE),
    surfaceContainer = Color(0xFFE6F1F8), surfaceContainerHigh = Color(0xFFDDECF6),
    surfaceContainerHighest = Color(0xFFD3E6F2),
    outline = Color(0xFF637D8F), outlineVariant = Color(0xFFC4DBE9),
    surfaceTint = Color(0xFF14698D),
)

private val AquaDarkColors = AtlasDarkColors.copy(
    primary = Color(0xFF9CDEF5), onPrimary = Color(0xFF093B4F),
    primaryContainer = Color(0xFF1F4B63), onPrimaryContainer = Color(0xFFC9EFFF),
    secondary = Color(0xFFB1D3EB), secondaryContainer = Color(0xFF29465C),
    onSecondaryContainer = Color(0xFFD9EFFC),
    background = Color(0xFF0D1F2F), onBackground = Color(0xFFE8F5FF),
    surface = Color(0xFF152B3E), onSurface = Color(0xFFE8F5FF),
    surfaceVariant = Color(0xFF294256), onSurfaceVariant = Color(0xFFBDD3E3),
    surfaceContainerLowest = Color(0xFF091927), surfaceContainerLow = Color(0xFF12273A),
    surfaceContainer = Color(0xFF172E43), surfaceContainerHigh = Color(0xFF213E55),
    surfaceContainerHighest = Color(0xFF2B4B63),
    outline = Color(0xFF8EAABB), outlineVariant = Color(0xFF3B596E),
    surfaceTint = Color(0xFF9CDEF5),
)

private fun atlasText(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = 0.sp,
)

private val AtlasTypography = Typography(
    displayLarge = atlasText(40, 48, FontWeight.SemiBold),
    displayMedium = atlasText(36, 44, FontWeight.SemiBold),
    displaySmall = atlasText(32, 40, FontWeight.SemiBold),
    headlineLarge = atlasText(30, 38, FontWeight.SemiBold),
    headlineMedium = atlasText(28, 36, FontWeight.SemiBold),
    headlineSmall = atlasText(24, 32, FontWeight.SemiBold),
    titleLarge = atlasText(22, 30, FontWeight.SemiBold),
    titleMedium = atlasText(18, 26, FontWeight.SemiBold),
    titleSmall = atlasText(16, 24, FontWeight.SemiBold),
    bodyLarge = atlasText(16, 26), bodyMedium = atlasText(14, 22), bodySmall = atlasText(13, 20),
    labelLarge = atlasText(14, 20, FontWeight.SemiBold),
    labelMedium = atlasText(12, 18, FontWeight.Medium),
    labelSmall = atlasText(12, 18, FontWeight.Medium),
)

private val LocalAtlasThemeApplied = staticCompositionLocalOf { false }
internal val LocalAtlasAppearance = staticCompositionLocalOf { AppearanceSettings() }

/** 元件可先獨立採用；日後外層掛載此 theme 時，內層沿用同一個 light/dark 選擇。 */
@Composable
fun AtlasTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    appearance: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit,
) {
    if (LocalAtlasThemeApplied.current) {
        content()
    } else {
        val colors = if (appearance.style == InterfaceStyle.AQUA) {
            if (darkTheme) AquaDarkColors else AquaLightColors
        } else if (darkTheme) AtlasDarkColors else AtlasLightColors
        CompositionLocalProvider(LocalAtlasThemeApplied provides true, LocalAtlasAppearance provides appearance) {
            MaterialTheme(
                colorScheme = colors,
                typography = AtlasTypography,
                shapes = Shapes(
                    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
                    medium = AtlasRadius.field, large = AtlasRadius.card,
                    extraLarge = RoundedCornerShape(28.dp),
                ),
                content = {
                    CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
                },
            )
        }
    }
}

internal enum class AtlasTone { NEUTRAL, ACCENT, INFO, CAUTION, ERROR }

@Composable
internal fun AtlasBadge(text: String, tone: AtlasTone = AtlasTone.NEUTRAL, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (container, foreground) = when (tone) {
        AtlasTone.NEUTRAL -> colors.surfaceVariant to colors.onSurfaceVariant
        AtlasTone.ACCENT -> colors.primaryContainer to colors.onPrimaryContainer
        AtlasTone.INFO -> colors.secondaryContainer to colors.onSecondaryContainer
        AtlasTone.CAUTION -> colors.tertiaryContainer to colors.onTertiaryContainer
        AtlasTone.ERROR -> colors.errorContainer to colors.onErrorContainer
    }
    Surface(modifier = modifier, shape = AtlasRadius.pill, color = container, contentColor = foreground) {
        Text(text, Modifier.padding(horizontal = AtlasSpace.md, vertical = AtlasSpace.xs), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun AtlasCard(
    modifier: Modifier = Modifier,
    subdued: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(
        containerColor = atlasChromeColor(if (subdued) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface),
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val elevation = CardDefaults.cardElevation(defaultElevation = if (subdued) 0.dp else 1.dp)
    val body: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(AtlasSpace.lg), verticalArrangement = Arrangement.spacedBy(AtlasSpace.md), content = content)
    }
    if (onClick == null) {
        Card(modifier.fillMaxWidth(), shape = AtlasRadius.card, colors = colors, border = border, elevation = elevation, content = body)
    } else {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = AtlasRadius.card, colors = colors, border = border, elevation = elevation, content = body)
    }
}

@Composable
internal fun atlasChromeColor(color: Color): Color =
    if (LocalAtlasAppearance.current.showsArtwork) color.copy(alpha = 0.94f) else color

@Composable
internal fun atlasOutlinedButtonColors(): ButtonColors =
    if (LocalAtlasAppearance.current.showsArtwork) ButtonDefaults.outlinedButtonColors(
        containerColor = atlasChromeColor(MaterialTheme.colorScheme.surface),
        disabledContainerColor = atlasChromeColor(MaterialTheme.colorScheme.surface),
    ) else ButtonDefaults.outlinedButtonColors()

@Composable
internal fun atlasOutlinedFieldColors(): TextFieldColors =
    if (LocalAtlasAppearance.current.showsArtwork) OutlinedTextFieldDefaults.colors(
        focusedContainerColor = atlasChromeColor(MaterialTheme.colorScheme.surface),
        unfocusedContainerColor = atlasChromeColor(MaterialTheme.colorScheme.surface),
        disabledContainerColor = atlasChromeColor(MaterialTheme.colorScheme.surface),
    ) else OutlinedTextFieldDefaults.colors()

@Composable
internal fun AtlasSectionHeading(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.xs)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AtlasFact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AtlasSpace.xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 兩欄在窄容器／大字模式退回單欄；數字與單位不裁切。 */
@Composable
internal fun AtlasMetrics(values: List<Pair<String, String>>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 240.dp || LocalDensity.current.fontScale > 1.3f) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
            values.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                    row.forEach { (label, value) ->
                        Surface(Modifier.weight(1f), shape = AtlasRadius.field, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            AtlasFact(label, value, Modifier.padding(AtlasSpace.md))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AtlasEmptyState(
    title: String,
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(
        modifier.fillMaxWidth().padding(if (compact) AtlasSpace.lg else AtlasSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AtlasSpace.md),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(if (compact) 48.dp else 64.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(AtlasSpace.icon), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
