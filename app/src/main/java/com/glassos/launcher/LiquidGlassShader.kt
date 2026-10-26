package com.glassos.launcher

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.min
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect

// ---------------------------------------------------------------------------------------------
// AGSL source (API 33+)
// ---------------------------------------------------------------------------------------------

/**
 * Liquid glass optics for a rounded rectangle:
 *  1. signed distance field of the tile -> distance to the nearest edge and the outward normal
 *  2. near the boundary the RGB sampling vectors are pushed inward along the normal, each channel
 *     with a slightly different strength (prismatic chromatic dispersion)
 *  3. a Fresnel style specular rim (smoothstep(borderWidth, 0, distFromEdge)) lit by a key light from
 *     the top-left and a weaker fill light from the opposite side
 *
 * `background` is the already-blurred backdrop supplied by RenderEffect.createRuntimeShaderEffect.
 */
const val LIQUID_GLASS_AGSL: String = """
uniform shader background;
uniform float2 size;
uniform float cornerRadius;
uniform float borderThreshold;
uniform float borderWidth;
uniform float refraction;
uniform float dispersion;

float roundedRectSdf(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - halfSize + float2(radius);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
}

float2 clampToSurface(float2 c) {
    return clamp(c, float2(0.5), size - float2(0.5));
}

half4 main(float2 fragCoord) {
    float2 halfSize = size * 0.5;
    float2 p = fragCoord - halfSize;
    float radius = min(cornerRadius, min(halfSize.x, halfSize.y));
    float sd = roundedRectSdf(p, halfSize, radius);
    float distFromEdge = -sd;
    if (distFromEdge < 0.0) {
        return half4(0.0);
    }

    float2 grad = float2(
        roundedRectSdf(p + float2(1.0, 0.0), halfSize, radius) - roundedRectSdf(p - float2(1.0, 0.0), halfSize, radius),
        roundedRectSdf(p + float2(0.0, 1.0), halfSize, radius) - roundedRectSdf(p - float2(0.0, 1.0), halfSize, radius)
    );
    float gradLen = length(grad);
    float2 normal = float2(0.0, 0.0);
    if (gradLen > 0.0001) {
        normal = grad / gradLen;
    }

    // Edge refraction: strongest at the boundary, fading to zero at borderThreshold.
    float edgeFactor = 1.0 - smoothstep(0.0, borderThreshold, distFromEdge);
    float bend = edgeFactor * edgeFactor;
    float2 inward = -normal;
    float2 baseOffset = inward * bend * refraction;

    // Chromatic dispersion: red bends the most, blue the least.
    float2 offsetR = baseOffset * (1.0 + dispersion);
    float2 offsetG = baseOffset;
    float2 offsetB = baseOffset * (1.0 - dispersion);

    half4 sampleR = background.eval(clampToSurface(fragCoord + offsetR));
    half4 sampleG = background.eval(clampToSurface(fragCoord + offsetG));
    half4 sampleB = background.eval(clampToSurface(fragCoord + offsetB));
    float3 color = float3(sampleR.r, sampleG.g, sampleB.b);

    // Body: faint frosted lift plus a soft top light.
    float sheen = 1.0 - clamp(fragCoord.y / size.y, 0.0, 1.0);
    color += float3(0.045) * sheen;
    color = mix(color, float3(1.0), 0.06);

    // Fresnel specular rim catching the bevel.
    float rim = smoothstep(borderWidth, 0.0, distFromEdge);
    float2 lightDir = normalize(float2(-0.55, -0.83));
    float facing = dot(normal, lightDir);
    float keyLight = pow(clamp(facing, 0.0, 1.0), 1.5);
    float fillLight = pow(clamp(-facing, 0.0, 1.0), 2.0) * 0.45;
    float fresnel = rim * (0.22 + 0.78 * (keyLight + fillLight));
    color += float3(fresnel);

    // Inner glow just inside the rim.
    float innerGlow = smoothstep(borderWidth * 6.0, 0.0, distFromEdge) * 0.07;
    color += float3(innerGlow);

    float alpha = smoothstep(0.0, 1.25, distFromEdge);
    color = clamp(color, 0.0, 1.0);
    return half4(float4(color * alpha, alpha));
}
"""

// ---------------------------------------------------------------------------------------------
// Parameters + shared environment
// ---------------------------------------------------------------------------------------------

@Immutable
data class LiquidGlassParams(
    /** Gaussian blur radius in px applied to the backdrop before refraction. */
    val blurRadius: Float = 40f,
    /** Maximum inward sampling displacement at the very edge. */
    val refractionDp: Float = 14f,
    /** Width of the band near the edge in which refraction happens. */
    val edgeThresholdDp: Float = 22f,
    /** Width of the specular rim. */
    val rimWidthDp: Float = 2.2f,
    /** Relative R/B split of the refraction vectors. */
    val dispersion: Float = 0.28f
)

/**
 * Describes the full-screen backdrop that every glass surface samples. Glass surfaces draw their own
 * aligned copy of the wallpaper so each one can blur and refract "what is behind it".
 */
@Immutable
data class GlassEnvironment(val rootSize: IntSize)

val LocalGlassEnvironment = compositionLocalOf { GlassEnvironment(IntSize.Zero) }

// ---------------------------------------------------------------------------------------------
// Wallpaper
// ---------------------------------------------------------------------------------------------

/** Procedural aurora wallpaper. Cheap enough to be drawn once per glass surface. */
@Composable
fun GlassWallpaper(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF060A24),
                    Color(0xFF14123F),
                    Color(0xFF2B0F4A),
                    Color(0xFF0A2347)
                )
            )
        )
        drawBlob(Offset(size.width * 0.12f, size.height * 0.10f), size.width * 0.95f, Color(0xFF00C2FF), 0.80f)
        drawBlob(Offset(size.width * 0.95f, size.height * 0.30f), size.width * 0.85f, Color(0xFFFF3FA4), 0.70f)
        drawBlob(Offset(size.width * 0.20f, size.height * 0.62f), size.width * 0.90f, Color(0xFFFF8A3D), 0.62f)
        drawBlob(Offset(size.width * 0.85f, size.height * 0.88f), size.width * 1.00f, Color(0xFF7C4DFF), 0.78f)
        drawBlob(Offset(size.width * 0.50f, size.height * 0.45f), size.width * 0.60f, Color(0xFF2EE6A6), 0.35f)
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

// ---------------------------------------------------------------------------------------------
// Glass surface
// ---------------------------------------------------------------------------------------------

/**
 * A liquid glass pane.
 *
 * API 33+: backdrop -> RenderEffect blur (40px, CLAMP) -> AGSL refraction/dispersion/rim shader.
 * API 31-32: blur plus translucent white gradient and gradient border.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
    tint: Color = Color.Transparent,
    params: LiquidGlassParams = LiquidGlassParams(),
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val environment = LocalGlassEnvironment.current
    val shape = RoundedCornerShape(cornerRadius)
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var surfaceOrigin by remember { mutableStateOf(IntOffset.Zero) }

    Box(
        modifier = modifier
            .clip(shape)
            .onSizeChanged { surfaceSize = it }
            .onGloballyPositioned { surfaceOrigin = it.positionInWindow().round() },
        contentAlignment = contentAlignment
    ) {
        if (surfaceSize.width > 0 && surfaceSize.height > 0 && environment.rootSize.width > 0) {
            GlassBackdropLayer(
                surfaceSize = surfaceSize,
                rootSize = environment.rootSize,
                originProvider = { surfaceOrigin },
                cornerRadius = cornerRadius,
                tint = tint,
                params = params
            )
        }
        content()
    }
}

@Composable
private fun BoxScope.GlassBackdropLayer(
    surfaceSize: IntSize,
    rootSize: IntSize,
    originProvider: () -> IntOffset,
    cornerRadius: Dp,
    tint: Color,
    params: LiquidGlassParams
) {
    val density = LocalDensity.current
    val cornerPx = with(density) { cornerRadius.toPx() }
    val effect = remember(surfaceSize, cornerPx, params, density.density) {
        buildGlassEffect(
            widthPx = surfaceSize.width.toFloat(),
            heightPx = surfaceSize.height.toFloat(),
            cornerPx = cornerPx,
            density = density.density,
            params = params
        )
    }
    val rootWidth = with(density) { rootSize.width.toDp() }
    val rootHeight = with(density) { rootSize.height.toDp() }

    Box(
        modifier = Modifier
            .matchParentSize()
            .graphicsLayer {
                renderEffect = effect
                clip = true
            }
    ) {
        GlassWallpaper(
            modifier = Modifier
                .requiredSize(rootWidth, rootHeight)
                .offset {
                    val origin = originProvider()
                    IntOffset(-origin.x, -origin.y)
                }
        )
        if (tint.alpha > 0f) {
            Box(modifier = Modifier.matchParentSize().background(tint))
        }
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        // Fallback for API 31-32 where AGSL RuntimeShader is unavailable.
        val shape = RoundedCornerShape(cornerRadius)
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    ),
                    shape = shape
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.55f),
                            Color.White.copy(alpha = 0.10f),
                            Color.White.copy(alpha = 0.30f)
                        )
                    ),
                    shape = shape
                )
        )
    }
}

private fun buildGlassEffect(
    widthPx: Float,
    heightPx: Float,
    cornerPx: Float,
    density: Float,
    params: LiquidGlassParams
): ComposeRenderEffect {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        buildLiquidGlassEffect(widthPx, heightPx, cornerPx, density, params)
    } else {
        BlurEffect(params.blurRadius, params.blurRadius, TileMode.Clamp)
    }
}

/**
 * RenderEffect.createBlurEffect(40f, 40f, TileMode.CLAMP) feeds the AGSL refraction shader through a
 * chain effect (inner effect runs first), so the shader refracts an already-blurred backdrop.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun buildLiquidGlassEffect(
    widthPx: Float,
    heightPx: Float,
    cornerPx: Float,
    density: Float,
    params: LiquidGlassParams
): ComposeRenderEffect {
    val minDimension = min(widthPx, heightPx)
    val threshold = min(params.edgeThresholdDp * density, minDimension * 0.38f).coerceAtLeast(2f)
    val rimWidth = (params.rimWidthDp * density).coerceAtLeast(1f)
    val refraction = min(params.refractionDp * density, minDimension * 0.30f)

    val shader = RuntimeShader(LIQUID_GLASS_AGSL)
    shader.setFloatUniform("size", widthPx, heightPx)
    shader.setFloatUniform("cornerRadius", cornerPx)
    shader.setFloatUniform("borderThreshold", threshold)
    shader.setFloatUniform("borderWidth", rimWidth)
    shader.setFloatUniform("refraction", refraction)
    shader.setFloatUniform("dispersion", params.dispersion)

    val blur = android.graphics.RenderEffect.createBlurEffect(
        params.blurRadius,
        params.blurRadius,
        android.graphics.Shader.TileMode.CLAMP
    )
    val refract = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "background")
    return android.graphics.RenderEffect.createChainEffect(refract, blur).asComposeRenderEffect()
}

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

/** Blurs everything drawn by this layer. [radius] is read in the draw phase, so animating it is cheap. */
fun Modifier.layerBlur(radius: () -> Float): Modifier = graphicsLayer {
    val r = radius()
    renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
}
