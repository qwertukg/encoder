import SlidingWindowAngleEncoder.LinearLayer
import SlidingWindowAngleEncoder.Layer
import java.lang.Math.toRadians
import kotlin.time.Duration
import kotlin.time.measureTime

private data class SweepRange(val start: Double, val endInclusive: Double, val step: Double) {
    fun values(): Sequence<Double> = sequence {
        var current = start
        while (current <= endInclusive) {
            yield(current)
            current += step
        }
    }
}

private data class LayoutRunConfig(
    val farRadiusFraction: Double = 0.5,
    val epochs: Int = 100,
    val lambdaStart: Double = 0.30,
    val lambdaEnd: Double = 0.90,
    val minSim: Double = 0.0,
    val eta: Double = 0.0,
    val maxBatchFrac: Double = 0.30,
    val forceLocalEnergyRadius: Int? = null,
)

fun main() {
    println("os.arch=" + System.getProperty("os.arch"))

    val encoder = buildEncoder()

    val angleRange = SweepRange(start = 1.0, endInclusive = 360.0, step = 1.0)
    val xRange = SweepRange(start = 16.0, endInclusive = 32.0, step = 16.0)
    val yRange = SweepRange(start = 16.0, endInclusive = 32.0, step = 16.0)

    val codes = generateCodes(encoder, angleRange, xRange, yRange)
    logCodes(codes)

    val emptyCodes = createEmptyCodes(codeSize = encoder.codeSizeInBits, count = 100)
    val layout = DampLayout2D(codes + emptyCodes)

    val layoutConfig = LayoutRunConfig()
    val layoutDuration = runLayout(layout, layoutConfig)

    logSimilarities(layout)
    println("CPU Layout finished! Total time: $layoutDuration")
}

private fun buildEncoder(): SlidingWindowAngleEncoder {
    val angleLayers = listOf(
        Layer(120.0),
        Layer(60.0),
        Layer(30.0),
        Layer(15.0),
        Layer(5.0),
    )

    val xLayers = listOf(
        LinearLayer(baseWidthUnits = 0.5, overlapFraction = 0.4, domainMin = 0.0, domainMax = 32.0),
        LinearLayer(baseWidthUnits = 1.0, overlapFraction = 0.4, domainMin = 0.0, domainMax = 32.0),
        LinearLayer(baseWidthUnits = 2.0, overlapFraction = 0.4, domainMin = 0.0, domainMax = 32.0),
        LinearLayer(baseWidthUnits = 4.0, overlapFraction = 0.4, domainMin = 0.0, domainMax = 32.0),
        LinearLayer(baseWidthUnits = 8.0, overlapFraction = 0.4, domainMin = 0.0, domainMax = 32.0),
        LinearLayer(baseWidthUnits = 16.0, overlapFraction = 0.4, domainMin = 0.0, domainMax = 32.0),
    )

    val yLayers = listOf(
        LinearLayer(baseWidthUnits = 0.5, overlapFraction = 0.4, domainMin = 0.0, domainMax = 32.0),
        LinearLayer(baseWidthUnits = 1.0, overlapFraction = 0.4, domainMin = 0.0, domainMax = 32.0),
        LinearLayer(baseWidthUnits = 2.0, overlapFraction = 0.4, domainMin = 0.0, domainMax = 32.0),
        LinearLayer(baseWidthUnits = 4.0, overlapFraction = 0.4, domainMin = 0.0, domainMax = 32.0),
        LinearLayer(baseWidthUnits = 8.0, overlapFraction = 0.4, domainMin = 0.0, domainMax = 32.0),
        LinearLayer(baseWidthUnits = 16.0, overlapFraction = 0.4, domainMin = 0.0, domainMax = 32.0),
    )

    return SlidingWindowAngleEncoder(
        layers = angleLayers,
        xLayers = xLayers,
        yLayers = yLayers,
        useRandomBitMapping = true,
    )
}

private fun generateCodes(
    encoder: SlidingWindowAngleEncoder,
    angleRange: SweepRange,
    xRange: SweepRange,
    yRange: SweepRange,
): List<Pair<Proto, IntArray>> {
    val codes = mutableListOf<Pair<Proto, IntArray>>()
    angleRange.values().forEach { angleDegrees ->
        val angleRad = toRadians(angleDegrees)
        xRange.values().forEach { x ->
            yRange.values().forEach { y ->
                val code = encoder.encode(angleRad, x, y)
                codes += Proto(angleDegrees, x, y) to code
            }
        }
    }
    return codes
}

private fun logCodes(codes: List<Pair<Proto, IntArray>>) {
    codes.forEach { (proto, code) ->
        println("${proto.angle}:${proto.x}:${proto.y}\t" + code.joinToString("", "[", "]"))
    }
}

private fun createEmptyCodes(codeSize: Int, count: Int): List<Pair<Proto?, IntArray>> =
    (0 until count).map { null to IntArray(codeSize) }

private fun runLayout(layout: DampLayout2D, config: LayoutRunConfig): Duration = measureTime {
    val farRadius = (layout.gridSize * config.farRadiusFraction).toInt().coerceAtLeast(1)
    layout.layoutLongRange(
        farRadius = farRadius,
        epochs = config.epochs,
        minSim = config.minSim,
        lambdaStart = config.lambdaStart,
        lambdaEnd = config.lambdaEnd,
        eta = config.eta,
        maxBatchFrac = config.maxBatchFrac,
        log = true,
        forceLocalEnergyRadius = config.forceLocalEnergyRadius,
    )
}

private fun logSimilarities(layout: DampLayout2D) {
    val pairs = listOf(
        Proto(289.0, 16.0, 32.0) to Proto(107.0, 16.0, 16.0),
        Proto(96.0, 16.0, 16.0) to Proto(102.0, 16.0, 16.0),
    )
    pairs.forEachIndexed { idx, (first, second) ->
        val similarity = layout.jaccardSimilarity(first, second)
        println("sim${idx + 1} = $similarity")
    }
}
