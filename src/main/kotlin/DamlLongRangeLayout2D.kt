import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Минимальная реализация дальнего (long-range) алгоритма раскладки из DAML.pdf.
 * Класс принимает список пар «угол-код» и позволяет выполнять раскладку на двумерной решётке
 * только посредством обмена точек, если это уменьшает энергию пары.
 */
class DamlLongRangeLayout2D(private val angleCodes: List<Pair<Double, IntArray>>) {

    private val gridSize: Int = ceil(sqrt(angleCodes.size.toDouble())).toInt()
    private val grid: MutableList<Int?> = MutableList(gridSize * gridSize) { index ->
        if (index < angleCodes.size) index else null
    }

    /**
     * Выполняет раскладку на решётке.
     * @param farRadius Радиус для выбора кандидатов на обмен.
     * @param epochs Количество эпох перебора пар.
     * @return Тройка значений: исходный угол и координаты [y, x] для каждого кода в исходном списке.
     */
    suspend fun layout(farRadius: Int, epochs: Int): List<Triple<Double, Int, Int>> {
        if (angleCodes.isEmpty()) return emptyList()

        return coroutineScope {
            logGridState(-1)

            repeat(epochs.coerceAtLeast(0)) { epoch ->
                for (firstIndex in grid.indices) {
                    var currentFirstCodeIndex = grid[firstIndex] ?: continue
                    val secondCandidates = candidateIndices(firstIndex, farRadius)
                    for (secondIndex in secondCandidates) {
                        val secondCodeIndex = grid[secondIndex] ?: continue
                        if (currentFirstCodeIndex == secondCodeIndex) continue

                        val (currentEnergy, swappedEnergy) = computeSwapEnergies(firstIndex, secondIndex)
                        if (swappedEnergy < currentEnergy) {
                            val previousFirstCodeIndex = currentFirstCodeIndex
                            grid[firstIndex] = secondCodeIndex
                            currentFirstCodeIndex = secondCodeIndex
                            grid[secondIndex] = previousFirstCodeIndex
                        }
                    }
                }
                logGridState(epoch)
            }

            buildCoordinateMap()
        }
    }

    private suspend fun logGridState(epoch: Int) {
        withContext(Dispatchers.Default) {
            val builder = StringBuilder()
            for (y in 0 until gridSize) {
                val row = (0 until gridSize).joinToString(separator = "\t") { x ->
                    val codeIndex = grid[y * gridSize + x]
                    codeIndex?.let { angleCodes[it].first.toString() } ?: "·"
                }
                builder.appendLine(row)
            }
            println("Эпоха ${epoch + 1}:\n${builder.toString().trimEnd()}\n")
        }
    }

    private suspend fun CoroutineScope.computeSwapEnergies(firstIndex: Int, secondIndex: Int): Pair<Double, Double> {
        val current = async(Dispatchers.Default) { pairEnergy(firstIndex, secondIndex) }
        val swapped = async(Dispatchers.Default) { swappedPairEnergy(firstIndex, secondIndex) }
        return current.await() to swapped.await()
    }

    private fun candidateIndices(sourceIndex: Int, farRadius: Int): Sequence<Int> {
        val radiusSquared = farRadius.toDouble().pow(2.0)
        val sourceY = sourceIndex / gridSize
        val sourceX = sourceIndex % gridSize
        return grid.indices.asSequence().filter { targetIndex ->
            if (targetIndex == sourceIndex) return@filter false
            val targetY = targetIndex / gridSize
            val targetX = targetIndex % gridSize
            val dy = (targetY - sourceY).toDouble()
            val dx = (targetX - sourceX).toDouble()
            dy * dy + dx * dx <= radiusSquared
        }
    }

    private fun pairEnergy(firstIndex: Int, secondIndex: Int): Double {
        val firstCodeIndex = grid[firstIndex] ?: return 0.0
        val secondCodeIndex = grid[secondIndex] ?: return 0.0
        val firstCoord = toCoord(firstIndex)
        val secondCoord = toCoord(secondIndex)
        var energy = 0.0
        grid.forEachIndexed { otherIndex, otherCodeIndex ->
            val codeIndex = otherCodeIndex ?: return@forEachIndexed
            val otherCoord = toCoord(otherIndex)
            energy += similarity(firstCodeIndex, codeIndex) * distance(firstCoord, otherCoord)
            energy += similarity(secondCodeIndex, codeIndex) * distance(secondCoord, otherCoord)
        }
        return energy
    }

    private fun swappedPairEnergy(firstIndex: Int, secondIndex: Int): Double {
        val firstCodeIndex = grid[firstIndex] ?: return 0.0
        val secondCodeIndex = grid[secondIndex] ?: return 0.0
        val firstCoord = toCoord(firstIndex)
        val secondCoord = toCoord(secondIndex)
        var energy = 0.0
        grid.forEachIndexed { otherIndex, otherCodeIndex ->
            val codeIndex = otherCodeIndex ?: return@forEachIndexed
            val otherCoord = toCoord(otherIndex)
            energy += similarity(secondCodeIndex, codeIndex) * distance(firstCoord, otherCoord)
            energy += similarity(firstCodeIndex, codeIndex) * distance(secondCoord, otherCoord)
        }
        return energy
    }

    private fun similarity(firstCodeIndex: Int, secondCodeIndex: Int): Double {
        val first = angleCodes[firstCodeIndex].second
        val second = angleCodes[secondCodeIndex].second
        val length = minOf(first.size, second.size)
        if (length == 0) return 0.0
        var equalBits = 0
        for (i in 0 until length) {
            if (first[i] == second[i]) {
                equalBits += 1
            }
        }
        val baseSimilarity = equalBits.toDouble() / length.toDouble()
        return threshold(baseSimilarity)
    }

    private fun threshold(value: Double, lambda: Double = 0.5, eta: Double = 10.0): Double {
        val sigmoid = 1.0 / (1.0 + exp(-eta * (value - lambda)))
        return value * sigmoid
    }

    private fun distance(a: Pair<Int, Int>, b: Pair<Int, Int>): Double {
        val dy = (a.first - b.first).toDouble()
        val dx = (a.second - b.second).toDouble()
        return sqrt(dy * dy + dx * dx)
    }

    private fun toCoord(index: Int): Pair<Int, Int> = index / gridSize to index % gridSize

    private suspend fun buildCoordinateMap(): List<Triple<Double, Int, Int>> = withContext(Dispatchers.Default) {
        val result = MutableList(angleCodes.size) { Triple(0.0, 0, 0) }
        grid.forEachIndexed { index, codeIndex ->
            val actualIndex = codeIndex ?: return@forEachIndexed
            val (angle, _) = angleCodes[actualIndex]
            val coord = toCoord(index)
            result[actualIndex] = Triple(angle, coord.first, coord.second)
        }
        result
    }
}
