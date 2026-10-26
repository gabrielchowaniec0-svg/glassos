package com.glassos.launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RuntimeShader
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect

// =============================================================================================
// AGSL: clear liquid glass (API 33+)
// =============================================================================================

/**
 * Optical model of a clear glass slab with a rounded, convex bevel:
 *  - Signed distance field of the rounded rectangle gives the distance to the edge and the normal.
 *  - Inside the bevel the surface curves like a quarter circle. Light passing through it is bent
 *    towards the centre, so the sampling position is displaced inward by the bevel's sagitta
 *    (strongest right at the rim, zero on the flat top). This is the "lens" look of iOS 26 glass.
 *  - Red, green and blue are displaced by slightly different amounts (prismatic dispersion), which
 *    produces the thin rainbow fringe along curved edges.
 *  - A thin specular rim lit from the top-left (with a weaker reflection on the opposite side),
 *    a faint inner glow and a slight saturation boost finish the material.
 *
 * `background` is the content behind the glass (optionally pre-blurred for frosted glass).
 */
const val LIQUID_GLASS_AGSL: String = """
uniform shader background;
uniform float2 size;
uniform float cornerRadius;
uniform float bevel;
uniform float refraction;
uniform float dispersion;
uniform float rimWidth;
uniform float rimStrength;

float sdRoundRect(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - halfSize + float2(radius);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
}

float2 clampInside(float2 c) {
    return clamp(c, float2(0.5), size - float2(0.5));
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 p = coord - halfSize;
    float radius = min(cornerRadius, min(halfSize.x, halfSize.y));
    float sd = sdRoundRect(p, halfSize, radius);
    float d = -sd;
    if (d < 0.0) {
        return half4(0.0);
    }

    float2 grad = float2(
        sdRoundRect(p + float2(1.0, 0.0), halfSize, radius) - sdRoundRect(p - float2(1.0, 0.0), halfSize, radius),
        sdRoundRect(p + float2(0.0, 1.0), halfSize, radius) - sdRoundRect(p - float2(0.0, 1.0), halfSize, radius)
    );
    float gl = length(grad);
    float2 n = float2(0.0, 0.0);
    if (gl > 0.0001) {
        n = grad / gl;
    }

    // Quarter-circle bevel: k = 1 at the rim, 0 where the flat top begins.
    float k = 1.0 - clamp(d / bevel, 0.0, 1.0);
    float sag = 1.0 - sqrt(max(1.0 - k * k, 0.0));
    float disp = refraction * sag;

    float2 baseOffset = -n * disp;
    half4 sr = background.eval(clampInside(coord + baseOffset * (1.0 + dispersion)));
    half4 sg = background.eval(clampInside(coord + baseOffset));
    half4 sb = background.eval(clampInside(coord + baseOffset * (1.0 - dispersion)));
    float3 c = float3(sr.r, sg.g, sb.b);

    // Glass slightly lifts and saturates what it transmits.
    float lum = dot(c, float3(0.299, 0.587, 0.114));
    c = mix(float3(lum), c, 1.10);
    c = mix(c, float3(1.0), 0.035);

    // Bevel shading: light from the top-left, a hint of shade on the lower inner edge.
    float bevelBand = smoothstep(bevel, 0.0, d);
    float2 lightDir = normalize(float2(-0.6, -0.8));
    float facing = dot(n, lightDir);
    c += float3(0.10) * bevelBand * clamp(facing, 0.0, 1.0);
    c *= 1.0 - 0.07 * bevelBand * clamp(n.y, 0.0, 1.0);

    // Specular rim: bright on the lit side, a softer reflection on the opposite corner.
    float rim = smoothstep(rimWidth, 0.0, d);
    float key = pow(clamp(facing, 0.0, 1.0), 1.4);
    float back = pow(clamp(-facing, 0.0, 1.0), 1.8) * 0.55;
    c += float3(rim * rimStrength * (0.20 + 0.80 * (key + back)));

    // Faint inner glow just inside the rim.
    c += float3(smoothstep(rimWidth * 5.0, 0.0, d) * 0.045 * rimStrength);

    float alpha = smoothstep(0.0, 1.2, d);
    c = clamp(c, 0.0, 1.0);
    return half4(float4(c * alpha, alpha));
}
"""

// =============================================================================================
// Glass style (driven by settings, identical for every glass surface)
// =============================================================================================

@Immutable
data class GlassStyle(
    /** 0 = perfectly clear. 1 = frosted. */
    val frost: Float = 0f,
    /** Multiplier for displacement and dispersion. */
    val refraction: Float = 1f
) {
    val blurRadiusPx: Float get() = frost * 30f
}

val LocalGlassStyle = compositionLocalOf { GlassStyle() }

// =============================================================================================
// Backdrops: live recordings of what is behind the glass
// =============================================================================================

/**
 * A live recording of some part of the UI (for example the wallpaper, or the whole home screen).
 * Glass surfaces draw this recording behind themselves, aligned to their on-screen position, and
 * refract it with the AGSL shader. Because it is a recording of real content, glass refracts
 * icons, widgets and text, not just the wallpaper.
 */
@Stable
class Backdrop internal constructor(val layer: GraphicsLayer) {
    internal var coordinates: LayoutCoordinates? = null
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/** Records everything this node draws into [backdrop] (and still draws it on screen). */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier =
    this
        .onGloballyPositioned { backdrop.coordinates = it }
        .drawWithContent {
            backdrop.layer.record {
                this@drawWithContent.drawContent()
            }
            drawLayer(backdrop.layer)
        }

/** The backdrop that glass surfaces in this subtree refract. */
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * Animated values (panel progress, pager offset, ...) that move glass on screen. Glass surfaces read
 * this while drawing so their refraction is re-aligned on every animation frame.
 */
val LocalGlassMotion = staticCompositionLocalOf<() -> Float> { { 0f } }

/** Corner radius that turns any glass surface into a capsule / circle. */
val GlassCapsule: Dp = 1000.dp

private class GlassPositionHolder {
    var coordinates: LayoutCoordinates? = null
}

/**
 * Draws [backdrop] so that what appears inside this node is exactly what sits behind it on screen,
 * including scale animations on any ancestor.
 */
private fun DrawScope.drawAlignedBackdrop(backdrop: Backdrop, self: LayoutCoordinates?) {
    val source = backdrop.coordinates
    if (source == null || self == null || !source.isAttached || !self.isAttached) return
    val origin = source.localPositionOf(self, Offset.Zero)
    val probeX = if (size.width > 0f) size.width else 1f
    val farX = source.localPositionOf(self, Offset(probeX, 0f))
    val layerScale = ((farX.x - origin.x) / probeX).let { if (abs(it) < 0.01f) 1f else it }
    val inverse = 1f / layerScale
    translate(-origin.x * inverse, -origin.y * inverse) {
        scale(inverse, inverse, pivot = Offset.Zero) {
            drawLayer(backdrop.layer)
        }
    }
}

// =============================================================================================
// Glass surface
// =============================================================================================

/**
 * The one glass material used everywhere in GlassOS.
 *
 * API 33+: live backdrop -> (optional frost blur) -> AGSL refraction, dispersion and specular rim.
 * API 31-32: blurred backdrop with a translucent white gradient and gradient border.
 *
 * [tint] is mixed into the glass before refraction (used for active toggles). [content] is drawn on
 * top, unrefracted.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
    tint: Color = Color.Transparent,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val backdrop = LocalBackdrop.current
    val style = LocalGlassStyle.current
    val motion = LocalGlassMotion.current
    val density = LocalDensity.current
    val shape = RoundedCornerShape(cornerRadius)
    val holder = remember { GlassPositionHolder() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Written whenever this surface moves; read during draw so movement re-draws the backdrop.
    var positionTick by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .clip(shape)
            .onSizeChanged { size = it }
            .onGloballyPositioned { coordinates ->
                holder.coordinates = coordinates
                val source = backdrop?.coordinates
                positionTick = if (source != null && source.isAttached) {
                    source.localPositionOf(coordinates, Offset.Zero)
                } else {
                    Offset.Zero
                }
            },
        contentAlignment = contentAlignment
    ) {
        if (size.width > 0 && size.height > 0) {
            val cornerPx = with(density) { cornerRadius.toPx() }
            val effect = remember(size, cornerPx, style, density.density) {
                buildGlassEffect(size, cornerPx, density.density, style)
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        renderEffect = effect
                        clip = true
                    }
                    .drawBehind {
                        // Subscribing to position and motion keeps the refraction aligned while moving.
                        if (positionTick.x.isNaN() || motion().isNaN()) return@drawBehind
                        if (backdrop != null) drawAlignedBackdrop(backdrop, holder.coordinates)
                        if (tint.alpha > 0f) drawRect(tint)
                    }
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                LegacyGlassOverlay(shape = shape)
            }
        }
        content()
    }
}

@Composable
private fun BoxScope.LegacyGlassOverlay(shape: RoundedCornerShape) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.05f))
                ),
                shape = shape
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.60f),
                        Color.White.copy(alpha = 0.08f),
                        Color.White.copy(alpha = 0.35f)
                    )
                ),
                shape = shape
            )
    )
}

private fun buildGlassEffect(
    size: IntSize,
    cornerPx: Float,
    density: Float,
    style: GlassStyle
): ComposeRenderEffect? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        buildLiquidGlassEffect(size, cornerPx, density, style)
    } else {
        val blur = max(style.blurRadiusPx, 10f)
        BlurEffect(blur, blur, TileMode.Clamp)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun buildLiquidGlassEffect(
    size: IntSize,
    cornerPx: Float,
    density: Float,
    style: GlassStyle
): ComposeRenderEffect {
    val width = size.width.toFloat()
    val height = size.height.toFloat()
    val minDim = min(width, height)
    // The lens lives in a narrow rim (like iOS); the flat centre transmits the backdrop undistorted.
    val bevel = min(13f * density, minDim * 0.24f).coerceAtLeast(2f)
    val refraction = min(10f * density * style.refraction, minDim * 0.16f)
    val dispersion = (0.14f * style.refraction).coerceIn(0f, 0.4f)
    val rimWidth = (1.6f * density).coerceAtLeast(1f)

    val shader = RuntimeShader(LIQUID_GLASS_AGSL)
    shader.setFloatUniform("size", width, height)
    shader.setFloatUniform("cornerRadius", cornerPx)
    shader.setFloatUniform("bevel", bevel)
    shader.setFloatUniform("refraction", refraction)
    shader.setFloatUniform("dispersion", dispersion)
    shader.setFloatUniform("rimWidth", rimWidth)
    shader.setFloatUniform("rimStrength", 1f)

    val refract = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "background")
    val blurRadius = style.blurRadiusPx
    val effect = if (blurRadius >= 0.5f) {
        val blur = android.graphics.RenderEffect.createBlurEffect(
            blurRadius,
            blurRadius,
            android.graphics.Shader.TileMode.CLAMP
        )
        android.graphics.RenderEffect.createChainEffect(refract, blur)
    } else {
        refract
    }
    return effect.asComposeRenderEffect()
}

// =============================================================================================
// Interaction
// =============================================================================================

/**
 * iOS-style liquid press: the element springs to [pressedScale] while touched and back on release,
 * with haptics. No ripple.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.glassClickable(
    enabled: Boolean = true,
    pressedScale: Float = 1.07f,
    haptics: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "glassPress"
    )
    val haptic = LocalHapticFeedback.current
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick?.let { longClick ->
                {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    longClick()
                }
            },
            onClick = {
                if (haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
        )
}

/** Blurs everything drawn by this layer. [radius] is read in the draw phase, so animating it is cheap. */
fun Modifier.layerBlur(radius: () -> Float): Modifier = graphicsLayer {
    val r = radius()
    renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
}

/** Soft shadow that keeps white text readable on clear glass. */
val GlassTextShadow = Shadow(
    color = Color.Black.copy(alpha = 0.32f),
    offset = Offset(0f, 1f),
    blurRadius = 6f
)

fun glassText(base: TextStyle = TextStyle.Default): TextStyle = base.copy(shadow = GlassTextShadow)

// =============================================================================================
// Wallpaper
// =============================================================================================

private const val WALLPAPER_FILE = "wallpaper.jpg"
private const val MAX_WALLPAPER_EDGE = 2800

/** Draws the selected preset, or the user's photo (center-cropped). */
@Composable
fun GlassWallpaper(
    preset: WallpaperPreset,
    photo: ImageBitmap?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (preset == WallpaperPreset.Photo && photo != null) {
            drawCroppedImage(photo)
        } else {
            drawPreset(if (preset == WallpaperPreset.Photo) WallpaperPreset.Aurora else preset)
        }
    }
}

private fun DrawScope.drawCroppedImage(image: ImageBitmap) {
    val imageRatio = image.width.toFloat() / image.height.toFloat()
    val viewRatio = size.width / size.height
    val srcWidth: Int
    val srcHeight: Int
    if (imageRatio > viewRatio) {
        srcHeight = image.height
        srcWidth = (image.height * viewRatio).roundToInt().coerceIn(1, image.width)
    } else {
        srcWidth = image.width
        srcHeight = (image.width / viewRatio).roundToInt().coerceIn(1, image.height)
    }
    val srcX = (image.width - srcWidth) / 2
    val srcY = (image.height - srcHeight) / 2
    drawImage(
        image = image,
        srcOffset = IntOffset(srcX, srcY),
        srcSize = IntSize(srcWidth, srcHeight),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
    )
}

private fun DrawScope.drawPreset(preset: WallpaperPreset) {
    val w = size.width
    val h = size.height
    when (preset) {
        WallpaperPreset.Ocean -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF0B3A8C), Color(0xFF0A6FB0), Color(0xFF0E8C8A), Color(0xFF06325E))))
            drawBlob(Offset(w * 0.15f, h * 0.55f), w * 1.0f, Color(0xFF2FD3C0), 0.75f)
            drawBlob(Offset(w * 0.95f, h * 0.20f), w * 0.9f, Color(0xFF1C4FE0), 0.85f)
            drawBlob(Offset(w * 0.70f, h * 0.85f), w * 0.8f, Color(0xFF0A2A6E), 0.9f)
            drawBlob(Offset(w * 0.40f, h * 0.30f), w * 0.5f, Color(0xFF7FE8FF), 0.35f)
        }
        WallpaperPreset.Sunset -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF2A1B5C), Color(0xFF8E2C7A), Color(0xFFE8604C), Color(0xFFF7B267))))
            drawBlob(Offset(w * 0.80f, h * 0.15f), w * 0.9f, Color(0xFF6A3FD8), 0.7f)
            drawBlob(Offset(w * 0.20f, h * 0.55f), w * 0.9f, Color(0xFFFF5E87), 0.6f)
            drawBlob(Offset(w * 0.60f, h * 0.90f), w * 1.0f, Color(0xFFFFC27A), 0.7f)
        }
        WallpaperPreset.Graphite -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF8E9399), Color(0xFFB9BEC4), Color(0xFF9EA3AA), Color(0xFF6E737A))))
            drawBlob(Offset(w * 0.30f, h * 0.35f), w * 0.9f, Color(0xFFE9D3DC), 0.55f)
            drawBlob(Offset(w * 0.80f, h * 0.75f), w * 0.8f, Color(0xFF5B6168), 0.6f)
            drawBlob(Offset(w * 0.55f, h * 0.10f), w * 0.6f, Color(0xFFFFFFFF), 0.35f)
        }
        else -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF060A24), Color(0xFF14123F), Color(0xFF2B0F4A), Color(0xFF0A2347))))
            drawBlob(Offset(w * 0.12f, h * 0.10f), w * 0.95f, Color(0xFF00C2FF), 0.80f)
            drawBlob(Offset(w * 0.95f, h * 0.30f), w * 0.85f, Color(0xFFFF3FA4), 0.70f)
            drawBlob(Offset(w * 0.20f, h * 0.62f), w * 0.90f, Color(0xFFFF8A3D), 0.62f)
            drawBlob(Offset(w * 0.85f, h * 0.88f), w * 1.00f, Color(0xFF7C4DFF), 0.78f)
            drawBlob(Offset(w * 0.50f, h * 0.45f), w * 0.60f, Color(0xFF2EE6A6), 0.35f)
        }
    }
}

private fun DrawScope.drawBlob(center: Offset, radius: Float, color: Color, alpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

/** Small swatch colours used to preview presets in Settings. */
fun WallpaperPreset.previewColors(): List<Color> = when (this) {
    WallpaperPreset.Aurora -> listOf(Color(0xFF00C2FF), Color(0xFFFF3FA4), Color(0xFF7C4DFF))
    WallpaperPreset.Ocean -> listOf(Color(0xFF2FD3C0), Color(0xFF1C4FE0), Color(0xFF0A2A6E))
    WallpaperPreset.Sunset -> listOf(Color(0xFF6A3FD8), Color(0xFFFF5E87), Color(0xFFFFC27A))
    WallpaperPreset.Graphite -> listOf(Color(0xFFE9D3DC), Color(0xFFB9BEC4), Color(0xFF5B6168))
    WallpaperPreset.Photo -> listOf(Color(0xFF444444), Color(0xFF888888), Color(0xFF222222))
}

/** Copies, downsamples and stores a gallery image as the wallpaper. Returns true on success. */
suspend fun saveWallpaperFromUri(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext false

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_WALLPAPER_EDGE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return@withContext false

        val target = File(context.filesDir, WALLPAPER_FILE)
        target.outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
        }
        bitmap.recycle()
        true
    } catch (e: Exception) {
        false
    }
}

/** Loads the stored photo wallpaper, or null if none has been saved. */
suspend fun loadWallpaperPhoto(context: Context): ImageBitmap? = withContext(Dispatchers.IO) {
    try {
        val file = File(context.filesDir, WALLPAPER_FILE)
        if (!file.exists()) return@withContext null
        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}
