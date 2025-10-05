import SlidingWindowAngleEncoder.Layer
import SlidingWindowAngleEncoder.LinearLayer
import gpu.GpuDamlLayout2D_GL430
import viz.showAnglesGrid
import java.lang.Math.toRadians
import kotlin.collections.List
import kotlin.time.measureTime

fun main() {
    println("os.arch=" + System.getProperty("os.arch"))

    val encoder = SlidingWindowAngleEncoder(
        // ---- ANGLE конфигурация ----
        listOf(
            Layer(arcLengthDegrees = 90.0,   detectorCount = 4,   overlapFraction = 0.4),
            Layer(arcLengthDegrees = 45.0,   detectorCount = 8,   overlapFraction = 0.4),
            Layer(arcLengthDegrees = 22.5,   detectorCount = 16,  overlapFraction = 0.4),
            Layer(arcLengthDegrees = 11.25,  detectorCount = 32,  overlapFraction = 0.4),
            Layer(arcLengthDegrees = 5.625,  detectorCount = 64,  overlapFraction = 0.4),
            Layer(arcLengthDegrees = 2.8125, detectorCount = 128, overlapFraction = 0.4),
        ),
        // ---- X конфигурации ----
        listOf(
            LinearLayer(baseWidthUnits = 0.25, detectorCount = 16, overlapFraction = 0.5, domainMin = -1.0, domainMax = 1.0),
            LinearLayer(baseWidthUnits = 0.10, detectorCount = 32, overlapFraction = 0.5, domainMin = -1.0, domainMax = 1.0)
        ),
        // ---- Y конфигурации ----
        listOf(
            LinearLayer(baseWidthUnits = 0.25, detectorCount = 16, overlapFraction = 0.5, domainMin = -2.0, domainMax = 2.0),
            LinearLayer(baseWidthUnits = 0.10, detectorCount = 32, overlapFraction = 0.5, domainMin = -2.0, domainMax = 2.0)
        ),
        512
    )

    val codes = mutableListOf<Pair<Double, IntArray>>()
    var a = 0.0
    while (a < 360.0) {
        a += 1.0

        var x = 0.0
        while (x <= 0.0) {
            x += 1.0

            var y = 0.0
            while (y <= 1.0) {
                y += 1.0

                val angleRad = toRadians(a)
                val code = encoder.encode(angleRad, x, y)
                println("$a:$x:$y" + code.joinToString("", "[", "]"))
                codes += a to code
            }
        }

    }

    val emptyCodes: List<Pair<Double?, IntArray>> = (0..300).map { null to IntArray(encoder.initialCodeSizeInBits) }
    val c = (codes + emptyCodes).shuffled()

    // GPU processing
    val gpuTime = measureTime {
        showAnglesGrid(c.map { it.first })
//        showAnglesGridIso(c.map { it.first })

        val gpuLayout = GpuDamlLayout2D_GL430(c)
        val outGPU =  gpuLayout.layoutLongRange(
            farRadius = 20,
            epochs = 100,
            minSim = 0.0,
            lambdaStart = 0.30,
            lambdaEnd = 0.90,
            eta = 0.0,
            maxBatchFrac = 0.30,
        )
        gpuLayout.dispose()
        showAnglesGrid(outGPU.map { it.first })
//        showAnglesGridIso(outGPU.map { it.first })
    }
    println("GPU Layout finished! Total time: $gpuTime")


    // CPU processing
//    val cpuTime = measureTime {
//        val layout = DampLayout2D(angleCodes = codes + emptyCodes)
//        val outCPU = layout.layoutLongRange(
//            farRadius = 20,
//            epochs = 100,
//            minSim = 0.00,
//            lambdaStart = 0.30,
//            lambdaEnd = 0.90,
//            eta = 0.0,
//            maxBatchFrac = 0.30,
//            log = false
//        )
//        showAnglesGrid(outCPU.map { it.first })
//    }
//    println("CPU Layout finished! Total time: $cpuTime")



//    val backgroundCorrelationAnalyzer = BackgroundCorrelationAnalyzer()
//
//    embeddedServer(Netty, port = 8080) {
//        detectorsUiModule(
//            encoder = encoder,
//            backgroundAnalyzer = backgroundCorrelationAnalyzer
//        )
//    }.start(wait = true)
}