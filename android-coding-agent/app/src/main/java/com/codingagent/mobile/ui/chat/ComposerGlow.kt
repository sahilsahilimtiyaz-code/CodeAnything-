package com.codingagent.mobile.ui.chat

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Premium "living" gradient border for the chat composer.
 *
 * Border-only enhancement: draws within the composer's existing outline
 * with the same rounded geometry. Adds no layout, padding, or size —
 * [Modifier.drawWithContent] never affects measurement, and when [active]
 * is false (alpha faded to 0) this returns the receiver untouched so the
 * composer renders pixel-identically to before.
 *
 * Look (matches spec: energy, not a spinner):
 * - a calm, dim indigo base light around the whole perimeter (continuity),
 * - ONE rich color mass traveling slowly, its hues morphing over time so
 *   different families (ocean blues → greens/golds → magentas/violets)
 *   take turns dominating instead of a static rainbow,
 * - an occasional brighter spark gliding along the edge (fades in and out),
 * - a very subtle static halo for soft diffusion.
 *
 * Driven by real agent state ([active] = busy OR approvals pending).
 */
@Composable
fun Modifier.composerGlow(
    active: Boolean,
    cornerRadius: Dp = 20.dp
): Modifier {
    val alpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "composerGlowAlpha"
    )
    if (alpha <= 0.01f) return this

    val context = LocalContext.current
    val motionReduced = remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }

    val transition = rememberInfiniteTransition(label = "composerGlow")
    // Color mass orbits the perimeter (~8s per lap — unhurried).
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "composerGlowOrbit"
    )
    // Hue family morphs slowly (~26s full cycle: ocean → meadow/gold → ember/violet).
    val hueShift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 26000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "composerGlowHue"
    )
    // Spark position (~6s lap) and its occasional appearance (~13s swell).
    val sparkPos by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "composerGlowSparkPos"
    )
    val sparkSwell by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 13000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "composerGlowSparkSwell"
    )

    val o = if (motionReduced) 0.3f else orbit
    val h = if (motionReduced) 0.15f else hueShift
    val sp = if (motionReduced) 0.6f else sparkPos
    val swell = if (motionReduced) 0f else {
        // Occasional: bright only during part of the swell, smooth in/out.
        val s = sin(sp * 0f + sparkSwell * Math.PI * 2).toFloat()
        ((s - 0.45f) / 0.55f).coerceIn(0f, 1f).let { it * it }
    }

    val massColors = remember(o, h) { massStops(orbit = o, hueShift = h) }
    val sparkColors = remember(sp) { sparkStops(position = sp) }
    val massBrush = remember(massColors) { Brush.sweepGradient(massColors) }
    val sparkBrush = remember(sparkColors) { Brush.sweepGradient(sparkColors) }
    val baseBrush = remember {
        Brush.sweepGradient(listOf(BASE_LIGHT, BASE_LIGHT_DIM, BASE_LIGHT))
    }
    val radius = cornerRadius

    return this.drawWithContent {
        drawContent()
        val a = alpha
        // Soft static halo for diffusion (indigo, very faint).
        drawBorderPass(BASE_HALO, radius, width = 7.dp, alpha = 0.10f * a)
        drawBorderPass(BASE_HALO, radius, width = 4.dp, alpha = 0.14f * a)
        // Calm continuous base light around the whole perimeter.
        drawBorderPass(baseBrush, radius, width = 1.5.dp, alpha = 0.55f * a)
        // The traveling color mass.
        drawBorderPass(massBrush, radius, width = 1.5.dp, alpha = 0.95f * a)
        // Occasional brighter spark.
        if (swell > 0.01f) {
            drawBorderPass(sparkBrush, radius, width = 1.5.dp, alpha = 0.85f * a * swell)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBorderPass(
    brush: Brush,
    cornerRadius: Dp,
    width: Dp,
    alpha: Float
) {
    if (alpha <= 0f) return
    val w = width.toPx()
    val r = cornerRadius.toPx()
    drawRoundRect(
        brush = brush,
        topLeft = Offset(w / 2f, w / 2f),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = w),
        alpha = alpha
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBorderPass(
    color: Color,
    cornerRadius: Dp,
    width: Dp,
    alpha: Float
) {
    if (alpha <= 0f) return
    val w = width.toPx()
    val r = cornerRadius.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2f, w / 2f),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = w),
        alpha = alpha
    )
}

// ---------------------------------------------------------------------------
// Palette construction
// ---------------------------------------------------------------------------

private val DeepNavy = Color(0xFF060913)
private val BASE_LIGHT = Color(0xFF3D55C8)
private val BASE_LIGHT_DIM = Color(0xFF1B2450)
private val BASE_HALO = Color(0xFF2A3A9E)
private val Transparent = Color(0x00000000)

/** Three hue families the traveling mass morphs between. */
private val OCEAN = listOf(
    Color(0xFF1B2CC1), Color(0xFF2456E6), Color(0xFF22D3EE),
    Color(0xFF2DD4BF), Color(0xFF7DD3FC), Color(0xFFE2E8F0)
)
private val MEADOW_GOLD = listOf(
    Color(0xFF16A34A), Color(0xFFA3E635), Color(0xFFFACC15),
    Color(0xFFF59E0B), Color(0xFFEA580C), Color(0xFFFFF7D6)
)
private val EMBER_VIOLET = listOf(
    Color(0xFFEF4444), Color(0xFFD946EF), Color(0xFFF472B6),
    Color(0xFF8B5CF6), Color(0xFF7C3AED), Color(0xFFFFFFFF)
)

private val FAMILIES = listOf(OCEAN, MEADOW_GOLD, EMBER_VIOLET)

/**
 * Full-perimeter stops: mostly deep-navy darkness with ONE rich window of
 * the current hue family centered at [orbit] turns. Because the family
 * itself morphs with [hueShift], colors take turns dominating instead of
 * all shouting at once. Cyclic — no seam.
 */
private fun massStops(orbit: Float, hueShift: Float, count: Int = 72): List<Color> {
    val family = morphFamily(hueShift)
    val window = 0.30f // fraction of the perimeter carrying color
    return List(count) { i ->
        val t = i.toFloat() / count
        // Signed distance around the circle from the window center.
        var d = (t - orbit) % 1f
        if (d > 0.5f) d -= 1f
        if (d < -0.5f) d += 1f
        val ad = kotlin.math.abs(d)
        if (ad > window / 2f) {
            DeepNavy
        } else {
            // Map across the window → family index, with soft edge fade.
            val u = (d / (window / 2f) * 0.5f + 0.5f).coerceIn(0f, 1f)
            val edge = (1f - kotlin.math.abs(u - 0.5f) * 2f).coerceIn(0f, 1f)
            val glow = edge * edge
            val c = sampleList(family, u)
            lerp(DeepNavy, c, 0.25f + 0.75f * glow)
        }
    }
}

/** Blend the three hue families by [hueShift] turns (cyclic). */
private fun morphFamily(hueShift: Float): List<Color> {
    val turns = (((hueShift % 1f) + 1f) % 1f) * FAMILIES.size
    val i0 = turns.toInt() % FAMILIES.size
    val i1 = (i0 + 1) % FAMILIES.size
    val f = turns - floor(turns)
    // Smooth the handoff.
    val s = f * f * (3f - 2f * f)
    val a = FAMILIES[i0]
    val b = FAMILIES[i1]
    return List(a.size) { k -> lerp(a[k], b[k % b.size], s) }
}

private fun sampleList(colors: List<Color>, u: Float): Color {
    val n = colors.size
    val pos = u.coerceIn(0f, 1f) * (n - 1)
    val i0 = pos.toInt().coerceIn(0, n - 1)
    val i1 = (i0 + 1).coerceIn(0, n - 1)
    return lerp(colors[i0], colors[i1], pos - floor(pos))
}

/**
 * Narrow bright spark at [position] turns on an otherwise transparent ring.
 */
private fun sparkStops(position: Float, count: Int = 72): List<Color> {
    return List(count) { i ->
        val t = i.toFloat() / count
        var d = (t - position) % 1f
        if (d > 0.5f) d -= 1f
        if (d < -0.5f) d += 1f
        val width = 0.045f
        val ad = kotlin.math.abs(d)
        if (ad > width) {
            Transparent
        } else {
            val core = 1f - ad / width
            val c = lerp(Color(0xFF9DB4FF), Color.White, core)
            c.copy(alpha = core * core)
        }
    }
}
