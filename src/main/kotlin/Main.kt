import SlidingWindowAngleEncoder.LinearLayer
import SlidingWindowAngleEncoder.Layer
import java.lang.Math.toRadians
import kotlin.math.sqrt
import kotlin.time.measureTime

fun main() {
    println("os.arch=" + System.getProperty("os.arch"))

    val a0 = 0.0
    val aN = 360.0
    val x0 = 0.0
    val xN = 32.0
    val y0 = 0.0
    val yN = 32.0

    val encoder = SlidingWindowAngleEncoder(
        // ---- ANGLE конфигурация ----
        listOf(
            Layer(120.0),
            Layer(60.0),
            Layer(30.0),
            Layer(15.0),
            Layer(5.0),
//            Layer(2.5),
//            Layer(1.0),
        ),
        // ---- X конфигурации ----
        listOf(
            LinearLayer(baseWidthUnits = 0.5,  overlapFraction = 0.4, domainMin = x0, domainMax = xN),
            LinearLayer(baseWidthUnits = 1.0,  overlapFraction = 0.4, domainMin = x0, domainMax = xN),
            LinearLayer(baseWidthUnits = 2.0,  overlapFraction = 0.4, domainMin = x0, domainMax = xN),
            LinearLayer(baseWidthUnits = 4.0,  overlapFraction = 0.4, domainMin = x0, domainMax = xN),
            LinearLayer(baseWidthUnits = 8.0,  overlapFraction = 0.4, domainMin = x0, domainMax = xN),
            LinearLayer(baseWidthUnits = 16.0, overlapFraction = 0.4, domainMin = x0, domainMax = xN),
//            LinearLayer(baseWidthUnits = 32.0, overlapFraction = 0.4, domainMin = x0, domainMax = xN),
        ),
        // ---- Y конфигурации ----
        listOf(
            LinearLayer(baseWidthUnits = 0.5,  overlapFraction = 0.4, domainMin = y0, domainMax = yN),
            LinearLayer(baseWidthUnits = 1.0,  overlapFraction = 0.4, domainMin = y0, domainMax = yN),
            LinearLayer(baseWidthUnits = 2.0,  overlapFraction = 0.4, domainMin = y0, domainMax = yN),
            LinearLayer(baseWidthUnits = 4.0,  overlapFraction = 0.4, domainMin = y0, domainMax = yN),
            LinearLayer(baseWidthUnits = 8.0,  overlapFraction = 0.4, domainMin = y0, domainMax = yN),
            LinearLayer(baseWidthUnits = 16.0, overlapFraction = 0.4, domainMin = y0, domainMax = yN),
//            LinearLayer(baseWidthUnits = 32.0, overlapFraction = 0.4, domainMin = y0, domainMax = yN),
        ),
        useRandomBitMapping = true,
//        codeSizeInBits = 256

    )


    val codes = mutableListOf<Pair<Proto, IntArray>>()
    var a = a0
    while (a < aN) {
        a += 1.0

        var x = x0
        while (x < xN) {
            x += 16.0

            var y = y0
            while (y < yN) {
                y += 16.0

                val angleRad = toRadians(a)
                val code = encoder.encode(angleRad, x, y)
                println("$a:$x:$y\t" + code.joinToString("", "[", "]"))
                val proto = Proto(angle = a, x = x, y = y)
                codes += proto to code
            }
        }

    }


//    val matrix = showAngleCodesCorrelationHeatmap(codes)
//    val matrix = buildCodeCorrelationMatrix(codes)
//    showSimilarityCurve(matrix, 0.0)


    val emptyCodes: List<Pair<Proto?, IntArray>> = (0..500).map { null to IntArray(encoder.codeSizeInBits) }

    // CPU processing
    val layout = DampLayout2D(codes + emptyCodes)

    val cpuTime = measureTime {

        val outCPU = layout.layoutLongRange(
            farRadius = layout.gridSize / 2,
            epochs = 100,
            minSim = 0.0,
            lambdaStart = 0.30,
            lambdaEnd = 0.90,
            eta = 0.0,
            maxBatchFrac = 0.30,
            log = true
        )
    }
    val sim1 = layout.jaccardSimilarity(
        Proto(289.0, 16.0, 32.0),
        Proto(107.0, 16.0, 16.0)
    )
    println("sim1 = $sim1")

    val sim2 = layout.jaccardSimilarity(
        Proto(96.0, 16.0, 16.0),
        Proto(102.0, 16.0, 16.0)
    )
    println("sim2 = $sim2")

    println("CPU Layout finished! Total time: $cpuTime")

}