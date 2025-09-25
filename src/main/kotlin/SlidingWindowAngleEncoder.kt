import kotlin.math.PI

/**
 * SlidingWindowAngleEncoder — версия с полностью развёрнутыми именами и комментариями.
 *
 * СОГЛАШЕНИЕ (contract) — внутри НЕТ проверок корректности входных данных:
 * 1) Сумма по слоям: Σ layers[k].detectorCount <= codeSizeInBits — иначе индекс выйдет за границы массива.
 * 2) Для каждого слоя: arcLengthDegrees > 0; detectorCount > 0; overlapFraction >= 0.
 * 3) Порядок слоёв значим (List сохраняет порядок конкатенации битов).
 * 4) Итоговая ширина окна слоя: windowWidthDegrees = arcLengthDegrees * (1 + overlapFraction).
 * 5) Шаг центров детекторов: centerStepRadians = 2π / detectorCount (равномерное покрытие круга).
 * 6) Фаза слоя k: layerPhaseRadians = (k / layerCount) * centerStepRadians; первый слой стартует с 0.
 */
class SlidingWindowAngleEncoder(
    /** Конфигурации слоёв (см. data class Layer ниже). */
    val layers: List<Layer> = listOf(
        Layer(arcLengthDegrees = 90.0,   detectorCount = 8,   overlapFraction = 0.4),
        Layer(arcLengthDegrees = 45.0,   detectorCount = 16,  overlapFraction = 0.4),
        Layer(arcLengthDegrees = 22.5,   detectorCount = 32,  overlapFraction = 0.4),
        Layer(arcLengthDegrees = 11.25,  detectorCount = 64,  overlapFraction = 0.4)
    ),
    /** Размер результирующего кода в битах. */
    val codeSizeInBits: Int = 256
) {
    /**
     * Параметры одного слоя детекторов.
     * @param arcLengthDegrees   базовая длина дуги (в градусах) без учёта перекрытия
     * @param detectorCount      количество детекторов (центров) по кругу; шаг центров = 360° / detectorCount
     * @param overlapFraction    доля перекрытия (например, 0.4 → итоговая ширина = arcLengthDegrees * 1.4)
     */
    data class Layer(val arcLengthDegrees: Double, val detectorCount: Int, val overlapFraction: Double)

    /** Полный круг в радианах (2π). */
    val fullCircleInRadians: Double = 2.0 * PI

    /**
     * Последний сгенерированный код (обновляется при каждом encode).
     * Сделано var + private set, чтобы извне читался, но не изменялся.
     */
    var lastEncodedCode: IntArray = IntArray(0)
        private set

    /**
     * Кодирует угол (в радианах) в разряжённый битовый вектор фиксированной длины [codeSizeInBits].
     *
     * Логика:
     *  - для каждого слоя с индексом layerIndex считаем:
     *      windowWidthRadians = toRad(arcLengthDegrees * (1 + overlapFraction))   // итоговая ширина окна (рад)
     *      centerStepRadians  = 2π / detectorCount                                // шаг центров (рад)
     *      layerPhaseRadians  = (layerIndex / layerCount) * centerStepRadians     // фазовый сдвиг слоя (рад)
     *  - для каждого детектора с индексом detectorIndex:
     *      detectorCenterRadians = detectorIndex * centerStepRadians + layerPhaseRadians
     *  - детектор активен, если:
     *      normalizedAngularDifference(angleInRadians - detectorCenterRadians) ∈ [-windowWidthRadians/2, windowWidthRadians/2)
     *  - активные детекторы «зажигают» биты по индексам:
     *      globalBitOffset + detectorIndex; globalBitOffset накапливается по слоям
     *
     * ВНИМАНИЕ: никаких защит от выхода за границы массива НЕТ (см. контракт сверху).
     *
     * @param angleInRadians угол в радианах
     * @return массив IntArray из 0/1 длиной [codeSizeInBits]
     */
    fun encode(angleInRadians: Double): IntArray {
        val encodedBits = IntArray(codeSizeInBits)
        var globalBitOffset = 0
        val layerCount = layers.size

        // нормализуем угол один раз в [0, 2π)
        val twoPi = 2.0 * PI
        var angleWrapped = angleInRadians % twoPi
        if (angleWrapped < 0.0) angleWrapped += twoPi

        layers.forEachIndexed { layerIndex, layer ->
            val windowWidthRadians = (layer.arcLengthDegrees * (1.0 + layer.overlapFraction)) * PI / 180.0
            val halfWindowWidthRadians = windowWidthRadians / 2.0
            val centerStepRadians = twoPi / layer.detectorCount
            val layerPhaseRadians = (layerIndex.toDouble() / layerCount) * centerStepRadians

            for (detectorIndex in 0 until layer.detectorCount) {
                val center = detectorIndex * centerStepRadians + layerPhaseRadians
                val startRaw = center - halfWindowWidthRadians
                val endRaw   = center + halfWindowWidthRadians

                // сворачиваем границы в [0, 2π)
                var start = startRaw % twoPi; if (start < 0.0) start += twoPi
                var end   = endRaw   % twoPi; if (end   < 0.0) end   += twoPi

                // правая граница ИСКЛЮЧИТСЯ (как и раньше), левая включается
                val hit = if (start <= end) {
                    angleWrapped >= start && angleWrapped < end
                } else {
                    // окно пересекает 0: два куска
                    angleWrapped >= start || angleWrapped < end
                }

                if (hit) {
                    encodedBits[globalBitOffset + detectorIndex] = 1
                }
            }
            globalBitOffset += layer.detectorCount
        }

        lastEncodedCode = encodedBits
        return encodedBits
    }
}

/* -----------------------------
   Пример использования (опционально):

fun main() {
    val encoder = SlidingWindowAngleEncoder()
    val angleRadians = 200.0 * PI / 180.0
    val code = encoder.encode(angleRadians)
    println(code.joinToString(""))
}
----------------------------- */
