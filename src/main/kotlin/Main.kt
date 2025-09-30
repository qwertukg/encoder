import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
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
    (0..150).forEach {
        val angleRadians = it * PI / 180.0
        val code = encoder.encode(angleRadians)
        codes.add(it.toDouble() to code)
        println(code.joinToString("", "[", "]") + ":$it")
//        encoder.drawDetectorsPdf("./detectors.pdf", markAngleRadians = angleRadians)
    }

    val layout = DamlLongRangeLayout2D(codes)
    val matrix = layout.layout(64, 150)


    println("Done!")



//    val backgroundCorrelationAnalyzer = BackgroundCorrelationAnalyzer()
//
//    embeddedServer(Netty, port = 8080) {
//        detectorsUiModule(
//            encoder = encoder,
//            backgroundAnalyzer = backgroundCorrelationAnalyzer
//        )
//    }.start(wait = true)
}