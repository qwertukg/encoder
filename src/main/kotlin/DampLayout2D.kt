import kotlin.math.abs

/**
 * DampLayout2D — вспомогательный расчётчик энергий размещения по канону DAML (см. DAML.pdf).
 *
 * При подборе взаимного расположения кодов на двумерной сетке используются два вклада:
 * 1. Дальнодействующая (long-range) энергия — сумма штрафов сходства с любыми другими клетками,
 *    если в паре фигурирует хотя бы одна из переставляемых позиций.
 * 2. Ближнедействующая (short-range) энергия — суммируется только по окрестности 3×3 вокруг
 *    переставляемых клеток, что соответствует локальной стабилизации из статьи.
 *
 * Оба вклада считаются для текущей конфигурации и для гипотетического состояния после обмена
 * значениями в двух выбранных ячейках. Это позволяет быстро оценивать выгодность перестановки
 * без модификации исходной сетки.
 */
class DampLayout2D(
    private val longRangeSimilarity: Array<DoubleArray>,
    private val shortRangeSimilarity: Array<DoubleArray>
) {

    init {
        require(longRangeSimilarity.size == shortRangeSimilarity.size) {
            "Матрицы энергий должны иметь одинаковый размер"
        }
        longRangeSimilarity.forEachIndexed { index, row ->
            require(row.size == longRangeSimilarity.size) {
                "Матрица дальнодействующих энергий должна быть квадратной"
            }
            require(shortRangeSimilarity[index].size == shortRangeSimilarity.size) {
                "Матрица ближнедействующих энергий должна быть квадратной"
            }
        }
    }

    data class Energy(val current: Double, val swapped: Double) {
        val delta: Double get() = swapped - current
    }

    fun evaluateSwap(
        grid: List<List<Int?>>,
        y1: Int,
        x1: Int,
        y2: Int,
        x2: Int,
        firstIndexOverride: Int? = grid.getOrNull(y1)?.getOrNull(x1),
        secondIndexOverride: Int? = grid.getOrNull(y2)?.getOrNull(x2)
    ): Energy {
        require(y1 in grid.indices && y2 in grid.indices) {
            "Координаты должны попадать в сетку"
        }
        require(x1 in grid[y1].indices && x2 in grid[y2].indices) {
            "Координаты должны попадать в сетку"
        }

        val longRange = computeLongRangeEnergy(
            grid,
            y1,
            x1,
            y2,
            x2,
            firstIndexOverride,
            secondIndexOverride
        )
        val shortRange = computeShortRangeEnergy(
            grid,
            y1,
            x1,
            y2,
            x2,
            firstIndexOverride,
            secondIndexOverride
        )
        return Energy(
            current = longRange.current + shortRange.current,
            swapped = longRange.swapped + shortRange.swapped
        )
    }

    private fun computeLongRangeEnergy(
        grid: List<List<Int?>>,
        y1: Int,
        x1: Int,
        y2: Int,
        x2: Int,
        firstIndex: Int?,
        secondIndex: Int?
    ): Energy {
        var currentEnergy = 0.0
        var swappedEnergy = 0.0
        val height = grid.size

        for (y in 0 until height) {
            val row = grid[y]
            for (x in row.indices) {
                val currentIndex = resolveIndex(
                    grid,
                    y,
                    x,
                    y1,
                    x1,
                    y2,
                    x2,
                    firstIndex,
                    secondIndex,
                    swapped = false
                ) ?: continue
                val swappedIndex = resolveIndex(
                    grid,
                    y,
                    x,
                    y1,
                    x1,
                    y2,
                    x2,
                    firstIndex,
                    secondIndex,
                    swapped = true
                )

                for (yy in y until height) {
                    val secondRow = grid[yy]
                    val startX = if (yy == y) x + 1 else 0
                    for (xx in startX until secondRow.size) {
                        if (!involvesSwappedCell(y, x, y1, x1, y2, x2) &&
                            !involvesSwappedCell(yy, xx, y1, x1, y2, x2)
                        ) {
                            continue
                        }

                        val otherCurrent = resolveIndex(
                            grid,
                            yy,
                            xx,
                            y1,
                            x1,
                            y2,
                            x2,
                            firstIndex,
                            secondIndex,
                            swapped = false
                        ) ?: continue
                        val otherSwapped = resolveIndex(
                            grid,
                            yy,
                            xx,
                            y1,
                            x1,
                            y2,
                            x2,
                            firstIndex,
                            secondIndex,
                            swapped = true
                        )

                        currentEnergy += longRangeSimilarity[currentIndex][otherCurrent]
                        if (swappedIndex != null && otherSwapped != null) {
                            swappedEnergy += longRangeSimilarity[swappedIndex][otherSwapped]
                        }
                    }
                }
            }
        }

        return Energy(currentEnergy, swappedEnergy)
    }

    private fun computeShortRangeEnergy(
        grid: List<List<Int?>>,
        y1: Int,
        x1: Int,
        y2: Int,
        x2: Int,
        firstIndex: Int?,
        secondIndex: Int?
    ): Energy {
        var currentEnergy = 0.0
        var swappedEnergy = 0.0
        val height = grid.size

        for (y in 0 until height) {
            val row = grid[y]
            for (x in row.indices) {
                val currentIndex = resolveIndex(
                    grid,
                    y,
                    x,
                    y1,
                    x1,
                    y2,
                    x2,
                    firstIndex,
                    secondIndex,
                    swapped = false
                ) ?: continue
                val swappedIndex = resolveIndex(
                    grid,
                    y,
                    x,
                    y1,
                    x1,
                    y2,
                    x2,
                    firstIndex,
                    secondIndex,
                    swapped = true
                )

                for (yy in y until height) {
                    val secondRow = grid[yy]
                    val startX = if (yy == y) x + 1 else 0
                    for (xx in startX until secondRow.size) {
                        if (!involvesSwappedCell(y, x, y1, x1, y2, x2) &&
                            !involvesSwappedCell(yy, xx, y1, x1, y2, x2)
                        ) {
                            continue
                        }
                        if (!isShortRangeNeighbor(y, x, yy, xx)) continue

                        val otherCurrent = resolveIndex(
                            grid,
                            yy,
                            xx,
                            y1,
                            x1,
                            y2,
                            x2,
                            firstIndex,
                            secondIndex,
                            swapped = false
                        ) ?: continue
                        val otherSwapped = resolveIndex(
                            grid,
                            yy,
                            xx,
                            y1,
                            x1,
                            y2,
                            x2,
                            firstIndex,
                            secondIndex,
                            swapped = true
                        )

                        currentEnergy += shortRangeSimilarity[currentIndex][otherCurrent]
                        if (swappedIndex != null && otherSwapped != null) {
                            swappedEnergy += shortRangeSimilarity[swappedIndex][otherSwapped]
                        }
                    }
                }
            }
        }

        return Energy(currentEnergy, swappedEnergy)
    }

    private fun resolveIndex(
        grid: List<List<Int?>>,
        y: Int,
        x: Int,
        y1: Int,
        x1: Int,
        y2: Int,
        x2: Int,
        firstIndex: Int?,
        secondIndex: Int?,
        swapped: Boolean
    ): Int? {
        return when {
            y == y1 && x == x1 -> if (swapped) secondIndex else firstIndex
            y == y2 && x == x2 -> if (swapped) firstIndex else secondIndex
            else -> grid[y][x]
        }
    }

    private fun involvesSwappedCell(
        y: Int,
        x: Int,
        y1: Int,
        x1: Int,
        y2: Int,
        x2: Int
    ): Boolean = (y == y1 && x == x1) || (y == y2 && x == x2)

    private fun isShortRangeNeighbor(y: Int, x: Int, yy: Int, xx: Int): Boolean {
        return abs(y - yy) <= 1 && abs(x - xx) <= 1
    }
}
