import kotlin.jvm.Volatile
import kotlin.math.PI
import kotlin.math.abs

/**
 * SlidingWindowAngleEncoder — кодирует угол + ОБЯЗАТЕЛЬНЫЕ признаки x и y
 * через «широкие детекторы» (скользящие окна с перекрытием).
 *
 * Макет битов: [ANGLE-слои] ++ [X-слои] ++ [Y-слои]
 */
class SlidingWindowAngleEncoder(
    // ---- ANGLE конфигурация ----
    val initialLayers: List<Layer> = listOf(
        Layer(arcLengthDegrees = 90.0,   detectorCount = 4,   overlapFraction = 0.4),
        Layer(arcLengthDegrees = 45.0,   detectorCount = 8,   overlapFraction = 0.4),
        Layer(arcLengthDegrees = 22.5,   detectorCount = 16,  overlapFraction = 0.4),
        Layer(arcLengthDegrees = 11.25,  detectorCount = 32,  overlapFraction = 0.4),
        Layer(arcLengthDegrees = 5.625,  detectorCount = 64,  overlapFraction = 0.4),
        Layer(arcLengthDegrees = 2.8125, detectorCount = 128, overlapFraction = 0.4),
    ),

    // ---- X/Y конфигурации (теперь с ДЕФОЛТАМИ) ----
    val initialXLayers: List<LinearLayer> = listOf(
        LinearLayer(baseWidthUnits = 0.25, detectorCount = 16, overlapFraction = 0.5, domainMin = -10.0, domainMax = 10.0),
        LinearLayer(baseWidthUnits = 0.10, detectorCount = 32, overlapFraction = 0.5, domainMin = -10.0, domainMax = 10.0)
    ),
    val initialYLayers: List<LinearLayer> = listOf(
        LinearLayer(baseWidthUnits = 0.25, detectorCount = 16, overlapFraction = 0.5, domainMin = -10.0, domainMax = 10.0),
        LinearLayer(baseWidthUnits = 0.10, detectorCount = 32, overlapFraction = 0.5, domainMin = -10.0, domainMax = 10.0)
    ),

    /** Размер кода: по умолчанию суммарное число детекторов ANGLE+X+Y. */
    val initialCodeSizeInBits: Int =
        initialLayers.sumOf { it.detectorCount } +
                initialXLayers.sumOf { it.detectorCount } +
                initialYLayers.sumOf { it.detectorCount }
) {
    /** Угловой слой (периодический). */
    data class Layer(val arcLengthDegrees: Double, val detectorCount: Int, val overlapFraction: Double)

    /** Линейный слой (непериодический). */
    data class LinearLayer(
        val baseWidthUnits: Double,
        val detectorCount: Int,
        val overlapFraction: Double,
        val domainMin: Double,
        val domainMax: Double
    )

    // --------- константы ---------
    val twoPi: Double = 2.0 * PI
    private val degreesToRadians: Double = PI / 180.0

    // --------- текущая конфигурация ---------
    @Volatile var layers: List<Layer> = initialLayers.toList();         private set
    @Volatile var xLayers: List<LinearLayer> = initialXLayers.toList(); private set
    @Volatile var yLayers: List<LinearLayer> = initialYLayers.toList(); private set
    @Volatile var codeSizeInBits: Int = initialCodeSizeInBits;          private set

    /** Последний код (для отладки). */
    var lastEncodedCode: IntArray = IntArray(0); private set

    init {
        validateAngleLayers(layers)
        validateLinearLayersNonEmpty(xLayers, "X")
        validateLinearLayersNonEmpty(yLayers, "Y")
        validateCodeSize(codeSizeInBits, layers, xLayers, yLayers)
    }

    // ----------------- Публичное API -----------------

    /** Единственный метод кодирования: угол + ОБЯЗАТЕЛЬНЫЕ x,y. */
    fun encode(angleInRadians: Double, x: Double, y: Double): IntArray {
        val totalBits = totalDetectors(layers, xLayers, yLayers)
        require(totalBits <= codeSizeInBits) {
            "codeSizeInBits=$codeSizeInBits меньше требуемых $totalBits бит"
        }
        val out = IntArray(codeSizeInBits)
        var offset = 0

        // ---- ANGLE ----
        if (layers.isNotEmpty()) {
            var ang = angleInRadians % twoPi
            if (ang < 0.0) ang += twoPi
            val layerCount = layers.size
            layers.forEachIndexed { idx, layer ->
                val win = layer.arcLengthDegrees * (1.0 + layer.overlapFraction) * degreesToRadians
                val half = win / 2.0
                val step = layer.arcLengthDegrees * degreesToRadians
                val phase = (idx.toDouble() / layerCount) * step
                for (d in 0 until layer.detectorCount) {
                    val center = d * step + phase
                    val sRaw = center - half
                    val eRaw = center + half
                    var s = sRaw % twoPi; if (s < 0.0) s += twoPi
                    var e = eRaw % twoPi; if (e < 0.0) e += twoPi
                    val hit = if (s <= e) (ang >= s && ang < e) else (ang >= s || ang < e)
                    if (hit) out[offset + d] = 1
                }
                offset += layer.detectorCount
            }
        }

        // ---- X ----
        encodeLinear1D(xLayers, x, out, offset)
        offset += xLayers.sumOf { it.detectorCount }

        // ---- Y ----
        encodeLinear1D(yLayers, y, out, offset)

        lastEncodedCode = out
        return out
    }

    /** Переконфигурировать ANGLE-слои. */
    fun reconfigure(newLayers: List<Layer>, requestedCodeSizeInBits: Int? = null) {
        validateAngleLayers(newLayers)
        val totalNeeded = totalDetectors(newLayers, xLayers, yLayers)
        val newSize = requestedCodeSizeInBits ?: maxOf(codeSizeInBits, totalNeeded)
        validateCodeSize(newSize, newLayers, xLayers, yLayers)
        layers = newLayers.toList()
        codeSizeInBits = newSize
        lastEncodedCode = IntArray(0)
    }

    /** Переконфигурировать X/Y (они ОБЯЗАТЕЛЬНЫ — списки не могут быть пустыми). */
    fun reconfigureXY(newXLayers: List<LinearLayer>, newYLayers: List<LinearLayer>, requestedCodeSizeInBits: Int? = null) {
        validateLinearLayersNonEmpty(newXLayers, "X")
        validateLinearLayersNonEmpty(newYLayers, "Y")
        val totalNeeded = totalDetectors(layers, newXLayers, newYLayers)
        val newSize = requestedCodeSizeInBits ?: maxOf(codeSizeInBits, totalNeeded)
        validateCodeSize(newSize, layers, newXLayers, newYLayers)
        xLayers = newXLayers.toList()
        yLayers = newYLayers.toList()
        codeSizeInBits = newSize
        lastEncodedCode = IntArray(0)
    }

    /** Сэмплинг угла по кругу с фиксированными x,y (по умолчанию 0.0). */
    fun sampleFullCircle(stepDegrees: Double = 1.0, xConst: Double = 0.0, yConst: Double = 0.0)
            : List<Pair<Double, IntArray>> {
        require(stepDegrees > 0.0) { "Шаг дискретизации должен быть положительным" }
        val steps = (360.0 / stepDegrees).toInt()
        require(abs(steps * stepDegrees - 360.0) < 1e-6) { "Шаг дискретизации должен делить круг без остатка" }
        return (0 until steps).map { idx ->
            val angleDeg = idx * stepDegrees
            val angleRad = angleDeg * PI / 180.0
            angleRad to encode(angleRad, xConst, yConst).copyOf()
        }
    }

    // ----------------- внутренние утилиты -----------------

    private fun encodeLinear1D(layers: List<LinearLayer>, value: Double, out: IntArray, bitOffset: Int) {
        var offset = bitOffset
        val layerCount = layers.size
        layers.forEachIndexed { idx, L ->
            require(L.domainMax > L.domainMin) { "Некорректный домен линейного слоя: max<=min" }
            val v = value.coerceIn(L.domainMin, L.domainMax)
            val baseW = L.baseWidthUnits
            val winW  = baseW * (1.0 + L.overlapFraction)
            val halfW = winW / 2.0
            val domainLen = L.domainMax - L.domainMin
            val step = domainLen / L.detectorCount
            val phase = (idx.toDouble() / layerCount) * step
            for (d in 0 until L.detectorCount) {
                val center = L.domainMin + d * step + phase
                val s = center - halfW
                val e = center + halfW
                if (v >= s && v < e) out[offset + d] = 1
            }
            offset += L.detectorCount
        }
    }

    private fun totalDetectors(a: List<Layer>, x: List<LinearLayer>, y: List<LinearLayer>): Int =
        a.sumOf { it.detectorCount } + x.sumOf { it.detectorCount } + y.sumOf { it.detectorCount }
}

// ----------------- валидации -----------------

private fun validateAngleLayers(layers: List<SlidingWindowAngleEncoder.Layer>) {
    require(layers.isNotEmpty()) { "Должен быть хотя бы один угловой слой" }
    layers.forEachIndexed { i, L ->
        require(L.arcLengthDegrees > 0.0) { "Angle: arcLengthDegrees слоя ${i+1} должен быть > 0" }
        require(L.detectorCount > 0)      { "Angle: detectorCount слоя ${i+1} должен быть > 0" }
        require(L.overlapFraction >= 0.0) { "Angle: overlapFraction слоя ${i+1} не может быть отрицательным" }
    }
}

private fun validateLinearLayersNonEmpty(
    layers: List<SlidingWindowAngleEncoder.LinearLayer>,
    tag: String
) {
    require(layers.isNotEmpty()) { "$tag: должен быть хотя бы один слой" }
    layers.forEachIndexed { i, L ->
        require(L.baseWidthUnits > 0.0)   { "$tag: baseWidthUnits слоя ${i+1} должен быть > 0" }
        require(L.detectorCount > 0)      { "$tag: detectorCount слоя ${i+1} должен быть > 0" }
        require(L.overlapFraction >= 0.0) { "$tag: overlapFraction слоя ${i+1} не может быть отрицательным" }
        require(L.domainMax > L.domainMin){ "$tag: domainMax должен быть > domainMin (слой ${i+1})" }
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
