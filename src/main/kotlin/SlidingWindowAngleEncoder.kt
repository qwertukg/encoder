import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * SlidingWindowAngleEncoder — кодирует угол + ОБЯЗАТЕЛЬНЫЕ признаки x и y
 * через «широкие детекторы» (скользящие окна с перекрытием).
 *
 * Макет битов: [ANGLE-слои] ++ [X-слои] ++ [Y-слои]
 */
class SlidingWindowAngleEncoder(
    // ---- ANGLE конфигурация ----
    val layers: List<Layer> = listOf(
        Layer(arcLengthDegrees = 90.0,   overlapFraction = 0.4, offsetDegrees = 0.0),
        Layer(arcLengthDegrees = 45.0,   overlapFraction = 0.4, offsetDegrees = 0.0),
        Layer(arcLengthDegrees = 22.5,   overlapFraction = 0.4, offsetDegrees = 0.0),
        Layer(arcLengthDegrees = 11.25,  overlapFraction = 0.4, offsetDegrees = 0.0),
        Layer(arcLengthDegrees = 5.625,  overlapFraction = 0.4, offsetDegrees = 0.0),
        Layer(arcLengthDegrees = 2.8125, overlapFraction = 0.4, offsetDegrees = 0.0),
    ),

    // ---- X/Y конфигурации ----
    val xLayers: List<LinearLayer> = listOf(
        LinearLayer(baseWidthUnits = 0.25, overlapFraction = 0.5, domainMin = -10.0, domainMax = 10.0),
        LinearLayer(baseWidthUnits = 0.10, overlapFraction = 0.5, domainMin = -10.0, domainMax = 10.0)
    ),
    val yLayers: List<LinearLayer> = listOf(
        LinearLayer(baseWidthUnits = 0.25, overlapFraction = 0.5, domainMin = -10.0, domainMax = 10.0),
        LinearLayer(baseWidthUnits = 0.10, overlapFraction = 0.5, domainMin = -10.0, domainMax = 10.0)
    ),

    /** Размер кода: по умолчанию суммарное число детекторов ANGLE+X+Y. */
    val codeSizeInBits: Int =
        layers.sumOf { it.detectorCount } +
                xLayers.sumOf { it.detectorCount } +
                yLayers.sumOf { it.detectorCount },

    /** Включить случайное отображение детекторов в биты. */
    val useRandomBitMapping: Boolean = false,

    /** seed для случайного отображения детекторов в биты. */
    val randomSeed: Int = 12345
) {
    /** Угловой слой (периодический). */
    data class Layer(
        val arcLengthDegrees: Double,
        val offsetDegrees: Double = 0.0,
        val overlapFraction: Double = 0.4
    ) {
        // Число детекторов автоматически: 360° / длину окна
        val detectorCount: Int = (360.0 / arcLengthDegrees).roundToInt()
    }

    /** Линейный слой (непериодический). */
    data class LinearLayer(
        val baseWidthUnits: Double,
        val overlapFraction: Double,
        val domainMin: Double,
        val domainMax: Double
    ) {
        // Число детекторов автоматически из длины домена
        val detectorCount: Int =
            ((domainMax - domainMin) / baseWidthUnits).roundToInt().coerceAtLeast(1)
    }

    private data class PeriodicLayerGeometry(
        val windowHalfWidth: Double,
        val step: Double,
        val offset: Double,
        val detectorCount: Int,
        val logicalOffset: Int,
    )

    private data class LinearLayerGeometry(
        val domainMin: Double,
        val domainMax: Double,
        val windowHalfWidth: Double,
        val step: Double,
        val phaseShift: Double,
        val detectorCount: Int,
        val logicalOffset: Int,
    )

    // --------- константы ---------
    private val twoPi: Double = 2.0 * PI
    private val degreesToRadians: Double = PI / 180.0

    /** Общее число детекторов (ANG + X + Y) в логическом пространстве. */
    private val totalDetectorsCount: Int =
        layers.sumOf { it.detectorCount } +
                xLayers.sumOf { it.detectorCount } +
                yLayers.sumOf { it.detectorCount }

    /**
     * Отображение "логический индекс детектора" -> "фактический индекс бита в коде".
     * Размер = totalDetectorsCount, значения в диапазоне 0 until codeSizeInBits.
     */
    private val detectorToBitIndex: IntArray

    /** Подготовленные параметры геометрии для угловых слоёв. */
    private val angularGeometry: List<PeriodicLayerGeometry>

    /** Подготовленные параметры геометрии для линейных слоёв X/Y. */
    private val xGeometry: List<LinearLayerGeometry>
    private val yGeometry: List<LinearLayerGeometry>

    /** Последний код (для отладки). */
    var lastEncodedCode: IntArray = IntArray(0); private set

    init {
        validateAngleLayers(layers)
        validateLinearLayersNonEmpty(xLayers, "X")
        validateLinearLayersNonEmpty(yLayers, "Y")
        validateCodeSize(codeSizeInBits, layers, xLayers, yLayers)

        detectorToBitIndex = if (useRandomBitMapping) {
            buildRandomDetectorMapping(
                totalDetectors = totalDetectorsCount,
                codeBits = codeSizeInBits,
                seed = randomSeed
            )
        } else {
            IntArray(totalDetectorsCount) { it } // детектор d -> бит d
        }

        angularGeometry = buildAngularGeometry(layers)
        xGeometry = buildLinearGeometry(xLayers)
        yGeometry = buildLinearGeometry(yLayers)
    }

    // ----------------- Публичное API -----------------

    /** Единственный метод кодирования: угол + ОБЯЗАТЕЛЬНЫЕ x,y. */
    fun encode(angleInRadians: Double, x: Double, y: Double): IntArray {
        val totalBits = totalDetectors(layers, xLayers, yLayers)
        require(totalBits <= codeSizeInBits) {
            "codeSizeInBits=$codeSizeInBits меньше требуемых $totalBits бит"
        }
        val out = IntArray(codeSizeInBits)

        encodeAngle(angleInRadians, out)
        encodeLinear(x, xGeometry, out)
        encodeLinear(y, yGeometry, out)

        lastEncodedCode = out
        return out
    }

    // ----------------- внутренние утилиты -----------------

    /**
     * Устанавливает бит для детектора с логическим индексом detectorIndex.
     */
    private fun setDetectorBit(detectorIndex: Int, out: IntArray) {
        val bitIndex = detectorToBitIndex[detectorIndex]
        out[bitIndex] = 1
    }

    private fun encodeAngle(angleInRadians: Double, out: IntArray) {
        if (angularGeometry.isEmpty()) return

        var normalizedAngle = angleInRadians % twoPi
        if (normalizedAngle < 0.0) normalizedAngle += twoPi

        angularGeometry.forEach { geometry ->
            repeat(geometry.detectorCount) { detectorIndex ->
                val center = geometry.offset + detectorIndex * geometry.step
                val startRaw = center - geometry.windowHalfWidth
                var start = startRaw % twoPi; if (start < 0.0) start += twoPi
                val endRaw = center + geometry.windowHalfWidth
                var end = endRaw % twoPi; if (end < 0.0) end += twoPi

                val hit = if (start <= end) {
                    normalizedAngle >= start && normalizedAngle < end
                } else {
                    normalizedAngle >= start || normalizedAngle < end
                }

                if (hit) setDetectorBit(geometry.logicalOffset + detectorIndex, out)
            }
        }
    }

    private fun encodeLinear(value: Double, geometry: List<LinearLayerGeometry>, out: IntArray) {
        if (geometry.isEmpty()) return

        geometry.forEach { layer ->
            val clamped = value.coerceIn(layer.domainMin, layer.domainMax)
            repeat(layer.detectorCount) { detectorIndex ->
                val center = layer.domainMin + detectorIndex * layer.step + layer.phaseShift
                val start = center - layer.windowHalfWidth
                val end = center + layer.windowHalfWidth

                if (clamped >= start && clamped < end) {
                    setDetectorBit(layer.logicalOffset + detectorIndex, out)
                }
            }
        }
    }

    private fun buildAngularGeometry(layers: List<Layer>): List<PeriodicLayerGeometry> {
        var logicalOffset = 0
        val layerCount = layers.size.toDouble().takeIf { it > 0 } ?: return emptyList()

        return layers.mapIndexed { idx, layer ->
            val step = layer.arcLengthDegrees * degreesToRadians
            val win = step * (1.0 + layer.overlapFraction)
            val half = win / 2.0
            val phase = (idx.toDouble() / layerCount) * step
            val offset = phase + layer.offsetDegrees * degreesToRadians

            PeriodicLayerGeometry(
                windowHalfWidth = half,
                step = step,
                offset = offset,
                detectorCount = layer.detectorCount,
                logicalOffset = logicalOffset
            ).also { logicalOffset += layer.detectorCount }
        }
    }

    private fun buildLinearGeometry(layers: List<LinearLayer>): List<LinearLayerGeometry> {
        var logicalOffset = 0
        val layerCount = layers.size.toDouble().takeIf { it > 0 } ?: return emptyList()

        return layers.mapIndexed { idx, layer ->
            val winWidth = layer.baseWidthUnits * (1.0 + layer.overlapFraction)
            val half = winWidth / 2.0
            val domainLen = layer.domainMax - layer.domainMin
            val step = domainLen / layer.detectorCount
            val phase = (idx.toDouble() / layerCount) * step

            LinearLayerGeometry(
                domainMin = layer.domainMin,
                domainMax = layer.domainMax,
                windowHalfWidth = half,
                step = step,
                phaseShift = phase,
                detectorCount = layer.detectorCount,
                logicalOffset = logicalOffset
            ).also { logicalOffset += layer.detectorCount }
        }
    }

    private fun totalDetectors(
        a: List<Layer>,
        x: List<LinearLayer>,
        y: List<LinearLayer>
    ): Int =
        a.sumOf { it.detectorCount } + x.sumOf { it.detectorCount } + y.sumOf { it.detectorCount }

    /**
     * Строит случайное отображение логических детекторов в биты:
     * детекторы 0..totalDetectors-1 -> уникальные битовые индексы в 0 until codeBits.
     */
    private fun buildRandomDetectorMapping(
        totalDetectors: Int,
        codeBits: Int,
        seed: Int
    ): IntArray {
        require(codeBits >= totalDetectors) {
            "codeBits ($codeBits) должен быть >= totalDetectors ($totalDetectors) для случайного отображения"
        }

        // Перемешиваем массив [0, 1, 2, ..., codeBits-1] и берём первые totalDetectors
        val arr = IntArray(codeBits) { it }
        val rnd = Random(seed)
        for (i in arr.indices.reversed()) {
            val j = rnd.nextInt(i + 1)
            val tmp = arr[i]
            arr[i] = arr[j]
            arr[j] = tmp
        }
        return arr.copyOf(totalDetectors)
    }
}

// ----------------- валидации -----------------

private fun validateAngleLayers(layers: List<SlidingWindowAngleEncoder.Layer>) {
    require(layers.isNotEmpty()) { "Должен быть хотя бы один угловой слой" }
    layers.forEachIndexed { i, L ->
        require(L.arcLengthDegrees > 0.0) { "Angle: arcLengthDegrees слоя ${i + 1} должен быть > 0" }
        require(L.detectorCount > 0)      { "Angle: detectorCount слоя ${i + 1} должен быть > 0" }
        require(L.overlapFraction >= 0.0) { "Angle: overlapFraction слоя ${i + 1} не может быть отрицательным" }
    }
}

private fun validateLinearLayersNonEmpty(
    layers: List<SlidingWindowAngleEncoder.LinearLayer>,
    tag: String
) {
    require(layers.isNotEmpty()) { "$tag: должен быть хотя бы один слой" }
    layers.forEachIndexed { i, L ->
        require(L.baseWidthUnits > 0.0)   { "$tag: baseWidthUnits слоя ${i + 1} должен быть > 0" }
        require(L.detectorCount > 0)      { "$tag: detectorCount слоя ${i + 1} должен быть > 0" }
        require(L.overlapFraction >= 0.0) { "$tag: overlapFraction слоя ${i + 1} не может быть отрицательным" }
        require(L.domainMax > L.domainMin){ "$tag: domainMax должен быть > domainMin (слой ${i + 1})" }
    }
}

private fun validateCodeSize(
    codeSizeInBits: Int,
    a: List<SlidingWindowAngleEncoder.Layer>,
    x: List<SlidingWindowAngleEncoder.LinearLayer>,
    y: List<SlidingWindowAngleEncoder.LinearLayer>
) {
    require(codeSizeInBits > 0) { "Размер кодового слова должен быть положительным" }
    val need = a.sumOf { it.detectorCount } + x.sumOf { it.detectorCount } + y.sumOf { it.detectorCount }
    require(codeSizeInBits >= need) { "codeSizeInBits ($codeSizeInBits) меньше числа детекторов ($need)" }
}
