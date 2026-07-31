package ch.nutrisnap.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.GlanceTheme
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import ch.nutrisnap.app.ui.theme.MacroColors
import kotlin.math.roundToInt

private val SMALL = DpSize(110.dp, 110.dp)
private val MEDIUM = DpSize(250.dp, 110.dp)

// Neutraler, theme-unabhängiger Grauton für die Ringspur (Track). GlanceTheme.colors
// liefert ColorProvider-Objekte ohne synchronen Zugriff auf den Rohwert außerhalb von
// Compose – fürs Bitmap-Rendering des Rings reicht ein fixer, dezenter Grauton.
private const val RING_TRACK_COLOR = 0x33808080 // ARGB: 20% grau

class NutriSnapWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = runCatching { WidgetDataProvider.load(context) }.getOrNull()
        provideContent {
            WidgetContent(snapshot)
        }
    }
}

@Composable
private fun WidgetContent(snapshot: WidgetSnapshot?) {
    val size = LocalSize.current
    val isCompact = size.width < 180.dp

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .clickable(actionRunCallback<QuickAddAction>())
            .padding(12.dp)
    ) {
        if (snapshot == null) {
            Text("…", style = TextStyle(color = GlanceTheme.colors.onBackground))
        } else if (isCompact) {
            CompactLayout(snapshot)
        } else {
            WideLayout(snapshot)
        }
    }
}

@Composable
private fun CompactLayout(s: WidgetSnapshot) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        CalorieRing(s, ringSizeDp = 72)
        Spacer(modifier = GlanceModifier.height(4.dp))
        StreakLine(s.streak)
    }
}

@Composable
private fun WideLayout(s: WidgetSnapshot) {
    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        CalorieRing(s, ringSizeDp = 76)
        Spacer(modifier = GlanceModifier.width(14.dp))
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                "${s.remaining.roundToInt()} kcal übrig",
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = GlanceTheme.colors.onBackground
                )
            )
            Spacer(modifier = GlanceModifier.height(6.dp))
            MacroDots(s)
            Spacer(modifier = GlanceModifier.height(6.dp))
            StreakLine(s.streak)
        }
    }
}

@Composable
private fun CalorieRing(s: WidgetSnapshot, ringSizeDp: Int) {
    // Grobe dpi-Skalierung (x3) reicht für ein RemoteViews-Bitmap; keine exakte
    // Density-Konvertierung nötig, da der Ring nur als Vektor-Grafik dient.
    val bitmap = RingRenderer.draw(
        progress = s.progress,
        sizePx = ringSizeDp * 3,
        trackColor = RING_TRACK_COLOR,
        progressColor = MacroColors.calories.toArgb()
    )
    Box(
        modifier = GlanceModifier.size(ringSizeDp.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = "Kalorien-Fortschritt",
            modifier = GlanceModifier.fillMaxSize()
        )
        Text(
            "${(s.progress * 100).roundToInt()}%",
            style = TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = GlanceTheme.colors.onBackground,
                textAlign = TextAlign.Center
            )
        )
    }
}

@Composable
private fun MacroDots(s: WidgetSnapshot) {
    Row {
        MacroDot(MacroColors.protein.toArgb(), s.protein)
        Spacer(modifier = GlanceModifier.width(8.dp))
        MacroDot(MacroColors.carbs.toArgb(), s.carbs)
        Spacer(modifier = GlanceModifier.width(8.dp))
        MacroDot(MacroColors.fat.toArgb(), s.fat)
    }
}

@Composable
private fun MacroDot(argbColor: Int, grams: Float) {
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        Box(
            modifier = GlanceModifier
                .size(8.dp)
                .background(ColorProvider(androidx.compose.ui.graphics.Color(argbColor)))
        ) {}
        Spacer(modifier = GlanceModifier.width(4.dp))
        Text(
            "${grams.roundToInt()}g",
            style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onBackground)
        )
    }
}

@Composable
private fun StreakLine(streak: Int) {
    Text(
        "🔥 $streak",
        style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onBackground)
    )
}
