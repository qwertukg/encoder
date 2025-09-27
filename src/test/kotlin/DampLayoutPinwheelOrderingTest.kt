import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertTrue

class DampLayoutPinwheelOrderingTest {
    @Test
    fun `углы располагаются по кругу`() {
        val encoder = SlidingWindowAngleEncoder(
            initialLayers = listOf(
                SlidingWindowAngleEncoder.Layer(90.0, 4, 0.4),
                SlidingWindowAngleEncoder.Layer(45.0, 8, 0.4),
                SlidingWindowAngleEncoder.Layer(22.5, 16, 0.4),
                SlidingWindowAngleEncoder.Layer(11.25, 32, 0.4),
                SlidingWindowAngleEncoder.Layer(5.625, 64, 0.4),
                SlidingWindowAngleEncoder.Layer(2.8125, 128, 0.4),
            ),
            initialCodeSizeInBits = 256
        )
        val samples = encoder.sampleFullCircle(stepDegrees = 1.0)
        val layout = buildDampLayoutVisualization(samples)

        val centerX = (layout.width - 1) / 2.0
        val centerY = (layout.height - 1) / 2.0
        val sorted = layout.points.sortedBy { it.angleDegrees }
        val polarAngles = sorted.map { point ->
            val dx = point.x - centerX
            val dy = centerY - point.y
            normalizeDegrees(Math.toDegrees(atan2(dy, dx)))
        }

        val offset = normalizeDegrees(polarAngles.first() - sorted.first().angleDegrees)
        var maxDeviation = 0.0
        var previousWrapped = normalizeDegrees(polarAngles.first() - offset)
        for (index in sorted.indices) {
            val targetAngle = sorted[index].angleDegrees
            val polarWrapped = normalizeDegrees(polarAngles[index] - offset)
            val deviation = angularDistance(polarWrapped, targetAngle)
            if (deviation > maxDeviation) {
                maxDeviation = deviation
            }
            if (index == 0) continue

            var delta = polarWrapped - previousWrapped
            while (delta < -1e-6) {
                delta += 360.0
            }
            assertTrue(delta >= -1e-6, "Полярный угол должен возрастать монотонно")
            previousWrapped = polarWrapped
        }

        assertTrue(maxDeviation < 15.0, "Максимальное отклонение $maxDeviation превышает 15°")
    }
}

private fun angularDistance(first: Double, second: Double): Double {
    val diff = kotlin.math.abs(normalizeDegrees(first - second))
    return kotlin.math.min(diff, 360.0 - diff)
}

private fun normalizeDegrees(angle: Double): Double {
    var result = angle % 360.0
    if (result < 0.0) result += 360.0
    return result
}
