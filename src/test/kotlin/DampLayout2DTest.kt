import kotlin.test.Test
import kotlin.test.assertTrue

class DampLayout2DTest {

    private val longRange = Array(4) { DoubleArray(4) }
    private val shortRange = Array(4) { DoubleArray(4) }

    init {
        longRange[0][1] = 0.1
        longRange[1][0] = 0.1
        shortRange[0][1] = 2.0
        shortRange[1][0] = 2.0
        shortRange[0][3] = 0.1
        shortRange[3][0] = 0.1
        shortRange[1][3] = 0.1
        shortRange[3][1] = 0.1
    }

    private val layout = DampLayout2D(longRange, shortRange)

    @Test
    fun `обмен корректно отражает энергию для схожих кодов`() {
        val neighboring = listOf(
            listOf<Int?>(0, 1, null),
            listOf<Int?>(2, 3, null),
            listOf<Int?>(null, null, null)
        )
        val moveSimilarApart = layout.evaluateSwap(neighboring, 0, 1, 2, 1)
        assertTrue(
            moveSimilarApart.swapped < moveSimilarApart.current,
            "Отдаление близких кодов должно уменьшать энергию"
        )

        val separated = listOf(
            listOf<Int?>(0, 2, null),
            listOf<Int?>(null, null, null),
            listOf<Int?>(3, 1, null)
        )
        val bringSimilarTogether = layout.evaluateSwap(separated, 0, 1, 2, 1)
        assertTrue(
            bringSimilarTogether.swapped > bringSimilarTogether.current,
            "Сближение близких кодов должно увеличивать энергию"
        )
    }
}
