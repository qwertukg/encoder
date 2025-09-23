package kz.qwertukg

import SlidingWindowAngleEncoder
import kotlin.math.PI

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
fun main() {
    val encoder = SlidingWindowAngleEncoder(codeSize = 256)

    for (deg in 0 until 360 step 1) {
        val rad = deg * PI / 180.0
        val code = encoder.encode(rad)
    }

    encoder.drawDetectorsPdf("./detectors.pdf", markAngleRadians = PI)
}