import kotlin.math.PI

fun main() {
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
    (0..359).forEach {
        val angleRadians = it * PI / 180.0
        val code = encoder.encode(angleRadians)
        codes.add(it.toDouble() to code)
        println("$it:" + code.joinToString("", "[", "]"))
//        encoder.drawDetectorsPdf("./detectors.pdf", markAngleRadians = angleRadians)
    }

    val layout = DampLayout2D(
        angleCodes = codes,
        randomizeStart = true,
        seed = 42
    )

    val posAfterLong = layout.layoutLongRange(
        farRadius = 20,
        epochs = 30,
        minSim = 0.00,
        lambdaStart = 0.10,
        lambdaEnd = 0.90,
        eta = 0.0,
        maxBatchFrac = 0.50,
        log = true
    )


    println("Done! Total swaps: ${layout.swapsLog.joinToString(",")}")



//    val backgroundCorrelationAnalyzer = BackgroundCorrelationAnalyzer()
//
//    embeddedServer(Netty, port = 8080) {
//        detectorsUiModule(
//            encoder = encoder,
//            backgroundAnalyzer = backgroundCorrelationAnalyzer
//        )
//    }.start(wait = true)
}