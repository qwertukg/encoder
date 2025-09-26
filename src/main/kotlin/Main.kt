import SlidingWindowAngleEncoder.Layer
import kotlin.math.PI

fun main() {
    val encoder = SlidingWindowAngleEncoder(listOf(
        Layer(arcLengthDegrees = 90.0,   detectorCount = 4,   overlapFraction = 0.4),
        Layer(arcLengthDegrees = 45.0,   detectorCount = 8,   overlapFraction = 0.4),
        Layer(arcLengthDegrees = 22.5,   detectorCount = 16,  overlapFraction = 0.4),
        Layer(arcLengthDegrees = 11.25,  detectorCount = 32,  overlapFraction = 0.4),
        Layer(arcLengthDegrees = 5.625,  detectorCount = 64,  overlapFraction = 0.4),
        Layer(arcLengthDegrees = 2.8125,  detectorCount = 128,  overlapFraction = 0.4),
    ), 256)

    (0..359).forEach {
        val angleRadians = it * PI / 180.0
        val code = encoder.encode(angleRadians)
        println(code.joinToString("", "[", "]") + ":$it")
    }

    encoder.drawDetectorsPdf("./detectors.pdf", markAngleRadians = 30 * PI / 180.0)


}