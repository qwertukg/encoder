import kotlin.test.Test
import kotlin.test.assertTrue

class DampLayout2DEnergyTest {
    @Test
    fun `обмен схожих кодов меняет энергию шагов DAML`() {
        val codes = listOf(
            intArrayOf(1, 1, 0, 0),
            intArrayOf(1, 0, 1, 0),
            intArrayOf(1, 1, 0, 0),
        )
        val layout = DampLayout2D(codes)

        val heightField = layout.javaClass.getDeclaredField("height").apply { isAccessible = true }
        val widthField = layout.javaClass.getDeclaredField("width").apply { isAccessible = true }
        val gridField = layout.javaClass.getDeclaredField("grid").apply { isAccessible = true }
        val positionsField = layout.javaClass.getDeclaredField("positions").apply { isAccessible = true }

        heightField.setInt(layout, 2)
        widthField.setInt(layout, 2)

        val grid = arrayOf(
            arrayOf<Int?>(0, 2),
            arrayOf<Int?>(1, null),
        )
        gridField.set(layout, grid)

        val positions = arrayOf(
            intArrayOf(0, 0),
            intArrayOf(1, 0),
            intArrayOf(0, 1),
        )
        positionsField.set(layout, positions)

        val lambda = 0.5
        val longRange = layout.javaClass.getDeclaredMethod(
            "computeLongRangeEnergy",
            Int::class.javaObjectType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaObjectType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        val currentLong = longRange.invoke(layout, 0, 0, 0, 1, 1, 0, lambda) as Double
        val swappedLong = longRange.invoke(layout, 1, 0, 0, 0, 1, 0, lambda) as Double
        assertTrue(
            swappedLong > currentLong,
            "После обмена схожие коды оказываются дальше и энергия дальнего этапа растёт",
        )

        val radius = 1.5
        val shortRange = layout.javaClass.getDeclaredMethod(
            "computeShortRangeEnergy",
            Int::class.javaObjectType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaObjectType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        val currentShort = shortRange.invoke(layout, 0, 0, 0, 1, 1, 0, lambda, radius) as Double
        val swappedShort = shortRange.invoke(layout, 1, 0, 0, 0, 1, 0, lambda, radius) as Double
        assertTrue(
            swappedShort < currentShort,
            "Ближний этап стремится оставить схожие коды в более выгодной конфигурации",
        )
    }
}
