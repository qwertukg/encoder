import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
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
        val pairCount: Long,
        val correlationProfile: CorrelationProfile? = null
    )

    data class CorrelationProfile(
        val referenceAngleDegrees: Double,
        val points: List<Point>
    ) {
        data class Point(val angleDegrees: Double, val correlation: Double)
    }

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

    /**
     * Перегрузка анализа, работающая с парами (угол, код) и печатающая профиль корреляции
     * для нулевого угла. Визуализация повторяет подход из разд. 2.2.4.1 DAML, где подчёркнута
     * важность контроля паразитной схожести между кодами.
     */
    fun analyzeWithAngles(codes: Collection<Pair<Double, IntArray>>, angle: Double): Stats {
        val stats = analyze(codes.map { it.second })
        val profile = buildCorrelationProfile(referenceAngleDegrees = angle, codesWithAngles = codes)
        profile?.let { printCorrelationProfile(it) }
        return stats.copy(correlationProfile = profile)
    }

    private fun buildCorrelationProfile(
        referenceAngleDegrees: Double,
        codesWithAngles: Collection<Pair<Double, IntArray>>
    ): CorrelationProfile? {
        if (codesWithAngles.isEmpty()) {
            println("Нет кодов для построения профиля корреляции.")
            return null
        }

        data class AngleEntry(val angleDegrees: Double, val code: IntArray, val activation: Int)

        val entries = codesWithAngles
            .map { (angleRadians, code) ->
                val normalizedDegrees = normalizeDegrees(Math.toDegrees(angleRadians))
                AngleEntry(normalizedDegrees, code, countActiveBits(code))
            }
            .sortedBy { it.angleDegrees }

        val reference = entries.minByOrNull {
            angularDistanceDegrees(it.angleDegrees, referenceAngleDegrees)
        }

        if (reference == null) {
            println("Не найден код, соответствующий нулевому углу.")
            return null
        }

        val referenceCode = reference.code
        val referenceActivation = reference.activation

        val profile = entries.map { entry ->
            val intersection = countIntersection(referenceCode, entry.code)
            val denominator = sqrt(referenceActivation.toDouble() * entry.activation)
            val correlation = if (denominator == 0.0) 0.0 else intersection / denominator
            entry.angleDegrees to correlation
        }

        val points = profile.map { (angleDegrees, correlation) ->
            CorrelationProfile.Point(angleDegrees, correlation)
        }

        return CorrelationProfile(reference.angleDegrees, points)
    }

    private fun printCorrelationProfile(profile: CorrelationProfile) {
        val maxCorrelation = profile.points.maxOfOrNull { it.correlation } ?: 0.0
        val scale = if (maxCorrelation == 0.0) 0.0 else 40.0 / maxCorrelation

        println(
            "Профиль корреляции для угла ${formatAngle(profile.referenceAngleDegrees)}°:"
        )

        profile.points
            .sortedBy { it.angleDegrees }
            .forEach { point ->
                val angleDegrees = point.angleDegrees
                val correlation = point.correlation
            val barLength = if (scale == 0.0) 0 else (correlation * scale).roundToInt()
            val bar = "█".repeat(barLength)
            println(String.format(Locale.US, "%6.1f° | %.4f | %s", angleDegrees, correlation, bar))
        }
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

    private fun normalizeDegrees(angleDegrees: Double): Double {
        var normalized = angleDegrees % 360.0
        if (normalized < 0.0) normalized += 360.0
        return normalized
    }

    private fun angularDistanceDegrees(a: Double, b: Double): Double {
        val diff = abs(a - b) % 360.0
        return min(diff, 360.0 - diff)
    }

    private fun formatAngle(angleDegrees: Double): String =
        String.format(Locale.US, "%.2f", angleDegrees)
}
