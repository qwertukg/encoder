import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * DampLayout2D — дискретная 2D-раскладка кодов по канону главы 5 DAML.
 *
 * Алгоритм реализует этапы, описанные в разд. 5.5–5.9 и п. 7.1.7:
 * 1) формирование матрицы 𝐕 с запасом ≈15 % свободных ячеек (d² ≈ n ⋅ 1.15);
 * 2) случайная начальная раскладка кодов по ячейкам (рис. 17а);
 * 3) итеративный дальний (long-range) этап: минимизация энергии 𝜑 через обмен пар
 *    точек, выбранных в радиусе r, с пороговой метрикой sim𝜆 (формулы 5.5.1);
 * 4) последовательное ужесточение порога 𝜆 и сжатие радиуса r, как рекомендовалось
 *    для морфологического эксперимента (п. 7.1.7);
 * 5) ближний (short-range) этап: максимизация локальной энергии по (5.5.2) внутри
 *    окрестности 𝐑 пары точек.
 *
 * Входные коды трактуются как разрежённые битовые векторы, для меры близости
 * применяется дискретный косинус (разд. 2.2.4.1) с пороговой функцией 𝜏(x).
 */
class DampLayout2D(
    private val codes: List<IntArray>,
    private val parameters: Parameters = Parameters(),
    random: Random? = null,
) {
    /** Параметры раскладки, повторяющие канон статьи. */
    data class Parameters(
        val marginFraction: Double = 0.15, // запас пустых ячеек ≈15 % (п. 7.1.7)
        val lambdaStart: Double = 0.65,    // начальное 𝜆 (п. 7.1.7)
        val lambdaEnd: Double = 0.8,       // конечное 𝜆 (п. 7.1.7)
        val lambdaStep: Double = 0.05,     // шаг ужесточения порога
        val eta: Double = Double.POSITIVE_INFINITY, // 𝜂 = ∞ — жёсткий отсев (п. 7.1.7)
        val initialRadiusFraction: Double = 0.5,    // стартовый радиус r = d / 2
        val radiusDecayFactor: Double = 0.5,        // уменьшение радиуса при стагнации
        val minRadius: Double = 1.0,                // минимальный радиус отбора пар
        val pairCountPerStep: Int? = null,          // 𝑝 — число тестовых пар за шаг
        val maxLongRangeSteps: Int = 50,
        val maxShortRangeSteps: Int = 30,
        val swapRatioThreshold: Double = 0.01,      // порог 1 % от начального обмена (п. 7.1.7)
        val shortRangeBaseRadius: Double = 2.0,     // базовый радиус для ближнего этапа
    )

    private val rnd: Random = random ?: Random.Default
    private val vectorLength: Int
    private val norms: DoubleArray

    private lateinit var grid: Array<Array<Int?>> // матрица 𝐕 (индексы кодов или null)
    private lateinit var positions: Array<IntArray> // координаты кодов в матрице
    private var height: Int = 0
    private var width: Int = 0

    init {
        require(codes.isNotEmpty()) { "Список кодов не может быть пустым" }
        vectorLength = codes.first().size
        require(codes.all { it.size == vectorLength }) {
            "Все коды должны иметь одинаковую длину"
        }
        require(vectorLength > 0) { "Код не может иметь нулевую длину" }
        norms = DoubleArray(codes.size) { index ->
            val ones = codes[index].count { it != 0 }
            if (ones == 0) 0.0 else sqrt(ones.toDouble())
        }
    }

    /**
     * Запускает полный цикл раскладки и возвращает финальную матрицу 𝐕.
     * Ячейки без кода представлены значением null.
     */
    fun layout(): List<List<IntArray?>> {
        initialiseMatrix()
        performLongRangePhase()
        performShortRangePhase()
        return buildResult()
    }

    private fun initialiseMatrix() {
        val codeCount = codes.size
        val totalSlots = ceil(codeCount * (1.0 + parameters.marginFraction)).toInt().coerceAtLeast(codeCount)
        val side = ceil(sqrt(totalSlots.toDouble())).toInt().coerceAtLeast(1)
        height = side
        width = side
        grid = Array(height) { arrayOfNulls<Int?>(width) }
        positions = Array(codes.size) { IntArray(2) }

        // Случайно распределяем коды по ячейкам (рис. 17а).
        val slots = MutableList(height * width) { it }
        slots.shuffle(rnd)
        codes.indices.forEach { codeIndex ->
            val slot = slots[codeIndex]
            val y = slot / width
            val x = slot % width
            grid[y][x] = codeIndex
            positions[codeIndex][0] = y
            positions[codeIndex][1] = x
        }
    }

    private fun performLongRangePhase() {
        var lambda = parameters.lambdaStart
        var radius = max(parameters.minRadius, max(height, width) * parameters.initialRadiusFraction)
        val pairCount = max(1, parameters.pairCountPerStep ?: codes.size)
        var baselineSwaps: Double? = null

        for (step in 0 until parameters.maxLongRangeSteps) {
            var swaps = 0
            repeat(pairCount) {
                val pair = pickPair(radius) ?: return@repeat
                val (first, second) = pair
                val firstIndex = grid[first.first][first.second]
                val secondIndex = grid[second.first][second.second]
                val currentEnergy = computeLongRangeEnergy(firstIndex, first.first, first.second, secondIndex, second.first, second.second, lambda)
                val swappedEnergy = computeLongRangeEnergy(secondIndex, first.first, first.second, firstIndex, second.first, second.second, lambda)
                if (swappedEnergy < currentEnergy) {
                    swapCells(first, second)
                    swaps++
                }
            }
            if (baselineSwaps == null) {
                baselineSwaps = swaps.toDouble().coerceAtLeast(1.0)
            }
            if (swaps == 0) {
                val lambdaChanged = if (lambda < parameters.lambdaEnd) {
                    lambda = min(lambda + parameters.lambdaStep, parameters.lambdaEnd)
                    true
                } else false
                val radiusChanged = if (radius > parameters.minRadius) {
                    radius = max(radius * parameters.radiusDecayFactor, parameters.minRadius)
                    true
                } else false
                if (!lambdaChanged && !radiusChanged) break
                continue
            }
            val target = baselineSwaps!! * parameters.swapRatioThreshold
            if (swaps <= target) {
                if (lambda < parameters.lambdaEnd) {
                    lambda = min(lambda + parameters.lambdaStep, parameters.lambdaEnd)
                }
                if (radius > parameters.minRadius) {
                    radius = max(radius * parameters.radiusDecayFactor, parameters.minRadius)
                }
            }
            if (lambda >= parameters.lambdaEnd && radius <= parameters.minRadius) break
        }
    }

    private fun performShortRangePhase() {
        val pairCount = max(1, parameters.pairCountPerStep ?: codes.size)
        val lambda = parameters.lambdaEnd
        val baseRadius = parameters.shortRangeBaseRadius

        for (step in 0 until parameters.maxShortRangeSteps) {
            var swaps = 0
            repeat(pairCount) {
                val pair = pickPair(max(baseRadius, parameters.minRadius)) ?: return@repeat
                val (first, second) = pair
                val firstIndex = grid[first.first][first.second]
                val secondIndex = grid[second.first][second.second]
                val distance = sqrt(distanceSquared(first.first, first.second, second.first, second.second))
                val radius = max(baseRadius, distance)
                val currentEnergy = computeShortRangeEnergy(firstIndex, first.first, first.second, secondIndex, second.first, second.second, lambda, radius)
                val swappedEnergy = computeShortRangeEnergy(secondIndex, first.first, first.second, firstIndex, second.first, second.second, lambda, radius)
                if (swappedEnergy > currentEnergy) {
                    swapCells(first, second)
                    swaps++
                }
            }
            if (swaps == 0) break
        }
    }

    private fun computeLongRangeEnergy(
        firstIndex: Int?,
        y1: Int,
        x1: Int,
        secondIndex: Int?,
        y2: Int,
        x2: Int,
        lambda: Double,
    ): Double {
        var total = 0.0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = grid[y][x] ?: continue
                val s1 = similarityWithThreshold(firstIndex, idx, lambda)
                val s2 = similarityWithThreshold(secondIndex, idx, lambda)
                if (s1 == 0.0 && s2 == 0.0) continue
                val d1 = distanceSquared(y1, x1, y, x)
                val d2 = distanceSquared(y2, x2, y, x)
                total += s1 * d1 + s2 * d2
            }
        }
        return total
    }

    private fun computeShortRangeEnergy(
        firstIndex: Int?,
        y1: Int,
        x1: Int,
        secondIndex: Int?,
        y2: Int,
        x2: Int,
        lambda: Double,
        radius: Double,
    ): Double {
        val cy = (y1 + y2) / 2.0
        val cx = (x1 + x2) / 2.0
        val radiusSquared = radius * radius
        val minY = max(0, kotlin.math.floor(cy - radius).toInt())
        val maxY = min(height - 1, kotlin.math.ceil(cy + radius).toInt())
        val minX = max(0, kotlin.math.floor(cx - radius).toInt())
        val maxX = min(width - 1, kotlin.math.ceil(cx + radius).toInt())
        var total = 0.0
        for (y in minY..maxY) {
            val dyCenter = y - cy
            val dyCenterSquared = dyCenter * dyCenter
            for (x in minX..maxX) {
                val dxCenter = x - cx
                val centerDistanceSquared = dyCenterSquared + dxCenter * dxCenter
                if (centerDistanceSquared > radiusSquared) continue
                val idx = grid[y][x]
                val s1 = similarityWithThreshold(firstIndex, idx, lambda)
                val s2 = similarityWithThreshold(secondIndex, idx, lambda)
                if (s1 == 0.0 && s2 == 0.0) continue
                val d1 = max(distanceSquared(y1, x1, y, x), 1e-9)
                val d2 = max(distanceSquared(y2, x2, y, x), 1e-9)
                total += s1 / d1 + s2 / d2
            }
        }
        return total
    }

    private fun similarityWithThreshold(firstIndex: Int?, secondIndex: Int?, lambda: Double): Double {
        if (firstIndex == null || secondIndex == null) return 0.0
        val raw = rawSimilarity(firstIndex, secondIndex)
        if (raw <= 0.0) return 0.0
        return applyThreshold(raw, lambda)
    }

    private fun rawSimilarity(aIndex: Int, bIndex: Int): Double {
        if (aIndex == bIndex) return 1.0
        val normA = norms[aIndex]
        val normB = norms[bIndex]
        if (normA == 0.0 || normB == 0.0) return 0.0
        val a = codes[aIndex]
        val b = codes[bIndex]
        var intersection = 0
        for (i in 0 until vectorLength) {
            if (a[i] != 0 && b[i] != 0) {
                intersection++
            }
        }
        return intersection / (normA * normB)
    }

    private fun applyThreshold(raw: Double, lambda: Double): Double {
        return if (parameters.eta.isInfinite()) {
            if (raw >= lambda) raw else 0.0
        } else {
            val sigma = 1.0 / (1.0 + exp(-parameters.eta * (raw - lambda)))
            raw * sigma
        }
    }

    private fun pickPair(radius: Double): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val firstIndex = rnd.nextInt(codes.size)
        val y1 = positions[firstIndex][0]
        val x1 = positions[firstIndex][1]
        val second = pickNeighbour(y1, x1, radius) ?: return null
        return (y1 to x1) to second
    }

    private fun pickNeighbour(y1: Int, x1: Int, radius: Double): Pair<Int, Int>? {
        val radiusAdjusted = max(radius, 1.0)
        val radiusSquared = radiusAdjusted * radiusAdjusted
        val minY = max(0, kotlin.math.floor(y1 - radiusAdjusted).toInt())
        val maxY = min(height - 1, kotlin.math.ceil(y1 + radiusAdjusted).toInt())
        val minX = max(0, kotlin.math.floor(x1 - radiusAdjusted).toInt())
        val maxX = min(width - 1, kotlin.math.ceil(x1 + radiusAdjusted).toInt())
        var chosen: Pair<Int, Int>? = null
        var seen = 0
        for (y in minY..maxY) {
            val dy = (y - y1).toDouble()
            val dySquared = dy * dy
            for (x in minX..maxX) {
                if (y == y1 && x == x1) continue
                val dx = (x - x1).toDouble()
                val distanceSquared = dySquared + dx * dx
                if (distanceSquared > radiusSquared) continue
                seen++
                if (rnd.nextInt(seen) == 0) {
                    chosen = y to x
                }
            }
        }
        return chosen
    }

    private fun swapCells(first: Pair<Int, Int>, second: Pair<Int, Int>) {
        val (y1, x1) = first
        val (y2, x2) = second
        val firstIndex = grid[y1][x1]
        val secondIndex = grid[y2][x2]
        grid[y1][x1] = secondIndex
        grid[y2][x2] = firstIndex
        if (firstIndex != null) {
            positions[firstIndex][0] = y2
            positions[firstIndex][1] = x2
        }
        if (secondIndex != null) {
            positions[secondIndex][0] = y1
            positions[secondIndex][1] = x1
        }
    }

    private fun distanceSquared(y1: Int, x1: Int, y2: Int, x2: Int): Double {
        val dy = (y1 - y2).toDouble()
        val dx = (x1 - x2).toDouble()
        return dy * dy + dx * dx
    }

    private fun buildResult(): List<List<IntArray?>> {
        return List(height) { y ->
            List(width) { x ->
                val index = grid[y][x]
                index?.let { codes[it].copyOf() }
            }
        }
    }
}
