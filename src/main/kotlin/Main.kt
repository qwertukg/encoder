import kotlin.math.PI
import SlidingWindowAngleEncoder.Layer
import SlidingWindowAngleEncoder.LinearLayer
import java.lang.Math.toRadians
import kotlin.time.measureTime

fun main() {
    println("os.arch=" + System.getProperty("os.arch"))

    val a0 = 0.0
    val aN = 360.0
    val x0 = 0.0
    val xN = 50.0
    val y0 = 0.0
    val yN = 50.0

    val encoder = SlidingWindowAngleEncoder(
        // ---- ANGLE конфигурация ----
        listOf(
            Layer(120.0),
            Layer(60.0),
            Layer(30.0),
            Layer(15.0),
            Layer(5.0),
        ),
        // ---- X конфигурации ----
        listOf(
            LinearLayer(baseWidthUnits = 1.0,  overlapFraction = 0.4, domainMin = x0, domainMax = xN),
            LinearLayer(baseWidthUnits = 2.0,  overlapFraction = 0.4, domainMin = x0, domainMax = xN),
            LinearLayer(baseWidthUnits = 4.0,  overlapFraction = 0.4, domainMin = x0, domainMax = xN),
            LinearLayer(baseWidthUnits = 8.0,  overlapFraction = 0.4, domainMin = x0, domainMax = xN),
            LinearLayer(baseWidthUnits = 16.0, overlapFraction = 0.4, domainMin = x0, domainMax = xN),
//            LinearLayer(baseWidthUnits = 32.0, overlapFraction = 0.4, domainMin = x0, domainMax = xN),
        ),
        // ---- Y конфигурации ----
        listOf(
            LinearLayer(baseWidthUnits = 1.0,  overlapFraction = 0.4, domainMin = y0, domainMax = yN),
            LinearLayer(baseWidthUnits = 2.0,  overlapFraction = 0.4, domainMin = y0, domainMax = yN),
            LinearLayer(baseWidthUnits = 4.0,  overlapFraction = 0.4, domainMin = y0, domainMax = yN),
            LinearLayer(baseWidthUnits = 8.0,  overlapFraction = 0.4, domainMin = y0, domainMax = yN),
            LinearLayer(baseWidthUnits = 16.0, overlapFraction = 0.4, domainMin = y0, domainMax = yN),
//            LinearLayer(baseWidthUnits = 32.0, overlapFraction = 0.4, domainMin = y0, domainMax = yN),
        ),
        315,
        true,

    )


    val codes = mutableListOf<Pair<Double, IntArray>>()
    var a = a0
    while (a < aN) {
        a += 10.0

        var x = x0
        while (x < xN) {
            x += 10.0

            var y = y0
            while (y < yN) {
                y += 10.0

                val angleRad = toRadians(a)
                val code = encoder.encode(angleRad, x, y)
                println("$a:$x:$y\t" + code.joinToString("", "[", "]"))
                codes += a to code
            }
        }

    }


//    val matrix = showAngleCodesCorrelationHeatmap(codes)
//    val matrix = buildCodeCorrelationMatrix(codes)
//    showSimilarityCurve(matrix, 0.0)


    val emptyCodes: List<Pair<Double?, IntArray>> = (0..300).map { null to IntArray(encoder.codeSizeInBits) }

    // CPU processing
    val cpuTime = measureTime {
        val c = (codes + emptyCodes).shuffled()
        val layout = DampLayout2D(angleCodes = codes + emptyCodes)

        val outCPU = layout.layoutLongRange(
            farRadius = 15,
            epochs = 100,
            minSim = 0.00,
            lambdaStart = 0.30,
            lambdaEnd = 0.90,
            eta = 0.0,
            maxBatchFrac = 0.30,
            log = true
        )
    }
    println("CPU Layout finished! Total time: $cpuTime")

}