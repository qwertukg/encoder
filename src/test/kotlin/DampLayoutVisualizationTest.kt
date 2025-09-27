import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DampLayoutVisualizationTest {
    @Test
    fun `раскладка возвращает 360 точек при шаге 1 градус`() {
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

        assertEquals(360, layout.points.size, "Должно быть 360 кодов в раскладке")
        assertTrue(layout.width > 0 && layout.height > 0, "Размеры раскладки должны быть положительными")
    }
}
