import kotlin.math.sqrt

/**
 * BackgroundCorrelationAnalyzer — инструмент для оценки фонового шума кодов по канону DAML.
 *
 * В разделе 2.2.4.1 статьи предписано использовать дискретную косинусную меру сходства
 * для работы с разрежёнными бинарными кодами, поэтому именно она применяется при расчёте.
 * Для множества кодов определяется уровень фоновой корреляции: среднее и максимальное
 * значение паразитной схожести между парами кодов.
 */
class BackgroundCorrelationAnalyzer {

    /**
     * Результат оценки фонового шума.
     * @param meanCorrelation среднее значение дискретной косинусной корреляции
     * @param maxCorrelation максимальное значение корреляции (верхняя граница шума)
     * @param pairCount количество уникальных пар кодов, вошедших в расчёт
     */
    data class Stats(
        val meanCorrelation: Double,
        val maxCorrelation: Double,
        val pairCount: Long
    )

    /**
     * Рассчитывает фоновый уровень корреляции для заданной коллекции кодов.
     * @param codes набор разрежённых бинарных кодов фиксированной длины
     * @throws IllegalArgumentException если набор содержит меньше двух кодов или неодинаковую длину
     */
    fun analyze(codes: Collection<IntArray>): Stats {
        require(codes.size >= 2) {
            "Для оценки фоновой корреляции требуется минимум два кода"
        }

        val iterator = codes.iterator()
        val referenceLength = iterator.next().size
        require(referenceLength > 0) {
            "Коды не должны быть пустыми"
        }
        while (iterator.hasNext()) {
            val code = iterator.next()
            require(code.size == referenceLength) {
                "Все коды должны иметь одинаковую длину"
            }
        }

        val codesList = codes.toList()
        val activations = codesList.map { code -> countActiveBits(code) }

        var sum = 0.0
        var max = Double.NEGATIVE_INFINITY
        var pairs = 0L

        for (i in 0 until codesList.lastIndex) {
            val left = codesList[i]
            val leftActivation = activations[i]
            for (j in i + 1 until codesList.size) {
                val right = codesList[j]
                val rightActivation = activations[j]
                val intersection = countIntersection(left, right)
                val denominator = sqrt(leftActivation.toDouble() * rightActivation)
                val correlation = if (denominator == 0.0) 0.0 else intersection / denominator
                sum += correlation
                if (correlation > max) {
                    max = correlation
                }
                pairs++
            }
        }

        val mean = if (pairs == 0L) 0.0 else sum / pairs
        val safeMax = if (pairs == 0L) 0.0 else max

        return Stats(mean, safeMax, pairs)
    }

    private fun countActiveBits(code: IntArray): Int = code.count { it != 0 }

    private fun countIntersection(left: IntArray, right: IntArray): Int {
        var intersection = 0
        for (index in left.indices) {
            if (left[index] != 0 && right[index] != 0) {
                intersection++
            }
        }
        return intersection
    }
}
