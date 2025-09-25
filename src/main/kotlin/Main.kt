import SlidingWindowAngleEncoder.Layer
import kotlin.math.PI

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
//fun main2() {
//    val encoder = SlidingWindowAngleEncoder(codeSize = 256)
//
//    for (deg in 0 until 360 step 1) {
//        val rad = deg * PI / 180.0
//        val code = encoder.encode(rad)
//    }
//
//    encoder.drawDetectorsPdf("./detectors.pdf", markAngleRadians = PI)
//}

fun main() {
    val encoder = SlidingWindowAngleEncoder(listOf(
        Layer(arcLengthDegrees = 90.0,   detectorCount = 4,   overlapFraction = 0.4),
        Layer(arcLengthDegrees = 45.0,   detectorCount = 8,   overlapFraction = 0.4),
        Layer(arcLengthDegrees = 22.5,   detectorCount = 16,  overlapFraction = 0.4),
        Layer(arcLengthDegrees = 11.25,  detectorCount = 32,  overlapFraction = 0.4)
    ))

    (0..359).forEach {
        val angleRadians = it * PI / 180.0
        val code = encoder.encode(angleRadians)
        println(code.joinToString("", "[", "]"))
    }

    encoder.drawDetectorsPdf("./detectors.pdf", markAngleRadians = PI)


}