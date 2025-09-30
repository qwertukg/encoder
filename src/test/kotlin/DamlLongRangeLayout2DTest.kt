import kotlin.test.Test
import kotlin.test.assertEquals

class DamlLongRangeLayout2DTest {
    @Test
    fun `сетка сохраняет множество индексов после одной эпохи`() {
        val angleCodes = listOf(
            0.0 to intArrayOf(1, 0, 1),
            45.0 to intArrayOf(0, 1, 0),
            90.0 to intArrayOf(1, 1, 1),
            135.0 to intArrayOf(0, 0, 0)
        )
        val layout = DamlLongRangeLayout2D(angleCodes)

        layout.layout(farRadius = 2, epochs = 1)

        val gridField = DamlLongRangeLayout2D::class.java.getDeclaredField("grid")
        gridField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val grid = gridField.get(layout) as MutableList<Int?>

        val actualIndices = grid.filterNotNull().toSet()
        val expectedIndices = angleCodes.indices.toSet()

        assertEquals(expectedIndices, actualIndices, "После обменов должны сохраниться все исходные индексы")
    }
}
