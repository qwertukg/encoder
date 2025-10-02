import gpu.GpuDamlLayout2D_GL430
import kotlin.math.PI
import kotlin.system.exitProcess

fun main() {
    println("os.arch=" + System.getProperty("os.arch"))

    val encoder = SlidingWindowAngleEncoder(
        initialLayers = listOf(
            SlidingWindowAngleEncoder.Layer(arcLengthDegrees = 90.0,   detectorCount = 4,   overlapFraction = 0.4),
            SlidingWindowAngleEncoder.Layer(arcLengthDegrees = 45.0,   detectorCount = 8,   overlapFraction = 0.4),
            SlidingWindowAngleEncoder.Layer(arcLengthDegrees = 22.5,   detectorCount = 16,  overlapFraction = 0.4),
            SlidingWindowAngleEncoder.Layer(arcLengthDegrees = 11.25,  detectorCount = 32,  overlapFraction = 0.4),
            SlidingWindowAngleEncoder.Layer(arcLengthDegrees = 5.625,  detectorCount = 64,  overlapFraction = 0.4),
            SlidingWindowAngleEncoder.Layer(arcLengthDegrees = 2.8125, detectorCount = 128, overlapFraction = 0.4),
        ),
        initialCodeSizeInBits = 256
    )

    val codes = mutableListOf<Pair<Double, IntArray>>()
    (0..359 step 5).forEach {
        val angleRadians = it * PI / 180.0
        val code = encoder.encode(angleRadians)
        codes.add(it.toDouble() to code)
        println("$it:" + code.joinToString("", "[", "]"))
//        encoder.drawDetectorsPdf("./detectors.pdf", markAngleRadians = angleRadians)
    }

    val emptyCodes = (0..100).map { -100.0 to IntArray(256) }

    // GPU processing

    val gpu = GpuDamlLayout2D_GL430(codes + emptyCodes)
    gpu.layoutLongRange(
        farRadius = 8,
        epochs = 60,
        minSim = 0.12,
        lambdaStart = 0.46,
        lambdaEnd   = 0.74,
        eta = 10.0,
        maxBatchFrac = 0.35
    )

    gpu.dispose()

    println("GPU Layout finished!")

    exitProcess(0)

    // CPU processing

    val layout = DampLayout2D(
        angleCodes = codes + emptyCodes,
        randomizeStart = true,
        seed = 42
    )

    layout.layoutLongRange(
        farRadius = 20,
        epochs = 100,
        minSim = 0.00,
        lambdaStart = 0.30,
        lambdaEnd = 0.90,
        eta = 0.0,
        maxBatchFrac = 0.30,
        log = true
    )


    println("CPU Layout finished! Total swaps: ${layout.swapsLog.joinToString(",")}")



//    val backgroundCorrelationAnalyzer = BackgroundCorrelationAnalyzer()
//
//    embeddedServer(Netty, port = 8080) {
//        detectorsUiModule(
//            encoder = encoder,
//            backgroundAnalyzer = backgroundCorrelationAnalyzer
//        )
//    }.start(wait = true)
}