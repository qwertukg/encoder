import SlidingWindowAngleEncoder.Layer
import SlidingWindowAngleEncoder.LinearLayer
import gpu.GpuDamlLayout2D_GL430
import viz.showAnglesGrid
import viz.showAnglesGridIso
import java.lang.Math.toRadians
import kotlin.collections.List
import kotlin.time.measureTime

fun main() {
    println("os.arch=" + System.getProperty("os.arch"))

    val a0 = 0.0
    val aN = 360.0
    val x0 = 0.0
    val xN = 1.0
    val y0 = 0.0
    val yN = 1.0

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
//            LinearLayer(baseWidthUnits = 0.5, overlapFraction = 0.4, domainMin = x0, domainMax = xN),
//            LinearLayer(baseWidthUnits = 1.5, overlapFraction = 0.4, domainMin = x0, domainMax = xN),
//            LinearLayer(baseWidthUnits = 2.5, overlapFraction = 0.4, domainMin = x0, domainMax = xN),
        ),
        // ---- Y конфигурации ----
        listOf(
//            LinearLayer(baseWidthUnits = 0.25, overlapFraction = 0.4, domainMin = -2.0, domainMax = 2.0),
//            LinearLayer(baseWidthUnits = 0.10, overlapFraction = 0.4, domainMin = -2.0, domainMax = 2.0)
        ),
//        512
    )


    val codes = mutableListOf<Pair<Double, IntArray>>()
    var a = a0
    while (a < aN) {
        a += 1.0

        var x = x0
        while (x < xN) {
            x += 1.0

            var y = y0
            while (y < yN) {
                y += 1.0

                val angleRad = toRadians(a)
                val code = encoder.encode(angleRad, x, y)
                println("$a:$x:$y" + code.joinToString("", "[", "]"))
                codes += a to code
            }
        }

    }



    val matrix = showAngleCodesCorrelationHeatmap(codes)
    showSimilarityCurve(matrix, 0.0)

    val emptyCodes: List<Pair<Double?, IntArray>> = (0..300).map { null to IntArray(encoder.codeSizeInBits) }

    // CPU processing
    val cpuTime = measureTime {
        val c = (codes + emptyCodes).shuffled()
        val layout = DampLayout2D(angleCodes = codes + emptyCodes)
        showAnglesGrid(c.map { it.first })

        val outCPU = layout.layoutLongRange(
            farRadius = 20,
            epochs = 100,
            minSim = 0.00,
            lambdaStart = 0.30,
            lambdaEnd = 0.90,
            eta = 0.0,
            maxBatchFrac = 0.30,
            log = false
        )
        showAnglesGrid(outCPU.map { it.first })
    }
    println("CPU Layout finished! Total time: $cpuTime")

    // GPU processing
//    val c = (codes + emptyCodes).shuffled()
//    val gpuTime = measureTime {
//        showAnglesGrid(c.map { it.first })
//
//        val gpuLayout = GpuDamlLayout2D_GL430(c)
//        val outGPU =  gpuLayout.layoutLongRange(
//            farRadius = 20,
//            epochs = 100,
//            minSim = 0.0,
//            lambdaStart = 0.30,
//            lambdaEnd = 0.90,
//            eta = 0.0,
//            maxBatchFrac = 0.30,
//        )
//        gpuLayout.dispose()
//        showAnglesGrid(outGPU.map { it.first })
//    }
//    println("GPU Layout finished! Total time: $gpuTime")






//    val backgroundCorrelationAnalyzer = BackgroundCorrelationAnalyzer()
//
//    embeddedServer(Netty, port = 8080) {
//        detectorsUiModule(
//            encoder = encoder,
//            backgroundAnalyzer = backgroundCorrelationAnalyzer
//        )
//    }.start(wait = true)
}