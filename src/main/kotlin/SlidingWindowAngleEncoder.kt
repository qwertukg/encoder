import kotlin.jvm.Volatile
import kotlin.math.PI

/**
 * SlidingWindowAngleEncoder — реализация канонического «скользящего окна» из разд. 4.4.1 DAML.
 *
 * Алгоритм опирается на ключевые идеи статьи:
 * 1) Код строится из набора детекторов, которые «обходят» окружность и образуют топологически
 *    замкнутое пространство за счёт гранично-замкнутых окон (boundary closed windows).
 * 2) Слои детекторов имеют собственный радиальный шаг и фазовый сдвиг, что повторяет принцип
 *    многочастотного покрытия круга (аналог Figure 5 и Figure 7 из статьи).
 * 3) Плотность и перекрытие управляются параметрами arcLengthDegrees и overlapFraction, что
 *    соответствует требованию поддерживать схожую плотность активных битов на всём диапазоне
 *    углов.
 *
 * СОГЛАШЕНИЕ (contract) — корректность параметров проверяется при создании/переконфигурации:
 * 1) Сумма по слоям: Σ layers[k].detectorCount <= codeSizeInBits — иначе индекс выйдет за границы массива.
 * 2) Для каждого слоя: arcLengthDegrees > 0; detectorCount > 0; overlapFraction >= 0.
 * 3) Порядок слоёв значим (List сохраняет порядок конкатенации битов).
 * 4) Итоговая ширина окна слоя: windowWidthDegrees = arcLengthDegrees * (1 + overlapFraction).
 * 5) Шаг центров детекторов: centerStepRadians = arcLengthDegrees * π / 180 (поворот на длину дуги слоя).
 * 6) Фаза слоя k: layerPhaseRadians = (k / layerCount) * centerStepRadians; первый слой стартует с 0.
 */
class SlidingWindowAngleEncoder(
    /** Конфигурации слоёв (см. data class Layer ниже). */
    initialLayers: List<Layer> = listOf(
        Layer(arcLengthDegrees = 90.0,   detectorCount = 4,   overlapFraction = 0.4),
        Layer(arcLengthDegrees = 45.0,   detectorCount = 8,   overlapFraction = 0.4),
        Layer(arcLengthDegrees = 22.5,   detectorCount = 16,  overlapFraction = 0.4),
        Layer(arcLengthDegrees = 11.25,  detectorCount = 32,  overlapFraction = 0.4),
        Layer(arcLengthDegrees = 5.625,  detectorCount = 64,  overlapFraction = 0.4),
        Layer(arcLengthDegrees = 2.8125,  detectorCount = 128,  overlapFraction = 0.4),
    ),
    /** Размер результирующего кода в битах. */
    initialCodeSizeInBits: Int = 256
) {
    /**
     * Параметры одного слоя детекторов.
     * @param arcLengthDegrees   базовая длина дуги (в градусах) без учёта перекрытия
     * @param detectorCount      количество детекторов (центров) по кругу; шаг центров = arcLengthDegrees
     * @param overlapFraction    доля перекрытия (например, 0.4 → итоговая ширина = arcLengthDegrees * 1.4)
     */
    data class Layer(val arcLengthDegrees: Double, val detectorCount: Int, val overlapFraction: Double)

    /** Полный круг в радианах (2π). */
    val twoPi: Double = 2.0 * PI

    /** Постоянный множитель перевода градусов в радианы (используется ниже много раз). */
    private val degreesToRadians: Double = PI / 180.0

    /** Текущая конфигурация слоёв (копия исходных значений из конструктора). */
    @Volatile
    var layers: List<Layer> = initialLayers.toList()
        private set

    /** Текущий размер кодового слова. */
    @Volatile
    var codeSizeInBits: Int = initialCodeSizeInBits
        private set

    /**
     * Последний сгенерированный код (обновляется при каждом encode).
     * Сделано var + private set, чтобы извне читался, но не изменялся.
     */
    var lastEncodedCode: IntArray = IntArray(0)
        private set

    init {
        validateLayers(layers)
        validateCodeSize(codeSizeInBits, layers)
    }

    /**
     * Кодирует угол (в радианах) в разряжённый битовый вектор фиксированной длины [codeSizeInBits].
     *
     * Основные шаги:
     * - для каждого слоя рассчитываются ширина окна, шаг центров и фазовый сдвиг;
     * - для каждого детектора слоя оценивается граница окна и проверяется принадлежность нормализованного угла;
     * - детекторы, в чьё окно попал угол, активируют соответствующие биты итогового кода.
     *
     * ВНИМАНИЕ: никаких защит от выхода за границы массива НЕТ (см. контракт сверху).
     *
     * @param angleInRadians угол в радианах
     * @return массив IntArray из 0/1 длиной [codeSizeInBits]
     */
    fun encode(angleInRadians: Double): IntArray {
        val encodedBits = IntArray(codeSizeInBits) // итоговый разрежённый код
        var globalBitOffset = 0 // смещение бита для текущего слоя
        val layerCount = layers.size // требуется для расчёта фазового сдвига слоя

        // Нормализуем угол в [0, 2π), чтобы гарантировать топологическую замкнутость по канону (разд. 4.4.1).
        var angleWrapped = angleInRadians % twoPi
        if (angleWrapped < 0.0) angleWrapped += twoPi

        layers.forEachIndexed { layerIndex, layer ->
            // Ширина окна = длина дуги * (1 + overlapFraction); половину храним для удобства.
            val windowWidthRadians = layer.arcLengthDegrees * (1.0 + layer.overlapFraction) * degreesToRadians
            val halfWindowWidthRadians = windowWidthRadians / 2.0
            // Шаг центров детекторов соответствует длине дуги слоя (см. DAML, Figure 5).
            val centerStepRadians = layer.arcLengthDegrees * degreesToRadians
            // Фазовый сдвиг слоя равномерно распределяет окна по каналу, предотвращая резонанс.
            val layerPhaseRadians = (layerIndex.toDouble() / layerCount) * centerStepRadians

            for (detectorIndex in 0 until layer.detectorCount) {
                // Центр детектора = индекс * шаг + фазовый сдвиг.
                val center = detectorIndex * centerStepRadians + layerPhaseRadians
                val startRaw = center - halfWindowWidthRadians
                val endRaw   = center + halfWindowWidthRadians

                // Сворачиваем границы в [0, 2π) — реализуем boundary closed окно (см. Figure 7).
                var start = startRaw % twoPi; if (start < 0.0) start += twoPi
                var end   = endRaw   % twoPi; if (end   < 0.0) end   += twoPi

                // Правая граница исключается, левая включается — один и тот же угол не активирует два окна.
                val hit = if (start <= end) {
                    angleWrapped >= start && angleWrapped < end
                } else {
                    // Окно пересекает 0 → проверяем оба сегмента (см. Figure 7 в статье).
                    angleWrapped >= start || angleWrapped < end
                }

                if (hit) {
                    encodedBits[globalBitOffset + detectorIndex] = 1 // активируем соответствующий бит
                }
            }
            globalBitOffset += layer.detectorCount
        }

        lastEncodedCode = encodedBits
        return encodedBits
    }

    /**
     * Переопределяет конфигурацию слоёв согласно канону DAML (разд. 4.4.1) и при необходимости
     * обновляет размер кодового слова. Используется UI, чтобы интерактивно менять покрытие.
     */
    fun reconfigure(newLayers: List<Layer>, requestedCodeSizeInBits: Int? = null) {
        validateLayers(newLayers)
        val sanitizedLayers = newLayers.toList()
        val effectiveCodeSize = requestedCodeSizeInBits ?: sanitizedLayers.sumOf { it.detectorCount }
        validateCodeSize(effectiveCodeSize, sanitizedLayers)

        layers = sanitizedLayers
        codeSizeInBits = effectiveCodeSize
        lastEncodedCode = IntArray(0)
    }

    /**
     * Генерирует канонический набор кодов для всей окружности с заданным шагом по градусам.
     * Используется для расчёта статистик и профилей корреляции согласно DAML.
     */
    fun sampleFullCircle(stepDegrees: Double = 1.0): List<Pair<Double, IntArray>> {
        require(stepDegrees > 0.0) {
            "Шаг дискретизации должен быть положительным"
        }
        val steps = (360.0 / stepDegrees).toInt()
        require(kotlin.math.abs(steps * stepDegrees - 360.0) < 1e-6) {
            "Шаг дискретизации должен делить полный круг без остатка"
        }

        return (0 until steps).map { index ->
            val angleDegrees = index * stepDegrees
            val angleRadians = angleDegrees * PI / 180.0
            angleRadians to encode(angleRadians).copyOf()
        }
    }
}

private fun validateLayers(layers: List<SlidingWindowAngleEncoder.Layer>) {
    require(layers.isNotEmpty()) {
        "Должен существовать хотя бы один слой детекторов"
    }
    layers.forEachIndexed { index, layer ->
        require(layer.arcLengthDegrees > 0.0) {
            "Длина дуги слоя ${index + 1} должна быть положительной"
        }
        require(layer.detectorCount > 0) {
            "Количество детекторов слоя ${index + 1} должно быть положительным"
        }
        require(layer.overlapFraction >= 0.0) {
            "Перекрытие слоя ${index + 1} не может быть отрицательным"
        }
    }
}

private fun validateCodeSize(codeSizeInBits: Int, layers: List<SlidingWindowAngleEncoder.Layer>) {
    require(codeSizeInBits > 0) {
        "Размер кодового слова должен быть положительным"
    }
    val detectorBudget = layers.sumOf { it.detectorCount }
    require(codeSizeInBits >= detectorBudget) {
        "Размер кодового слова ($codeSizeInBits) меньше числа детекторов ($detectorBudget)"
    }
}

/* -----------------------------
   Пример использования (опционально):

fun viz.main() {
    val encoder = SlidingWindowAngleEncoder()
    val angleRadians = 200.0 * PI / 180.0
    val code = encoder.encode(angleRadians)
    println(code.joinToString(""))
}
----------------------------- */
