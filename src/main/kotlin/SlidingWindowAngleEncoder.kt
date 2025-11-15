import kotlin.math.PI
import kotlin.math.roundToInt

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

    // ---- X/Y конфигурации (теперь с ДЕФОЛТАМИ) ----
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
                yLayers.sumOf { it.detectorCount }
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

    // --------- константы ---------
    val twoPi: Double = 2.0 * PI
    private val degreesToRadians: Double = PI / 180.0

    /** Последний код (для отладки). */
    var lastEncodedCode: IntArray = IntArray(0); private set

    init {
        validateAngleLayers(layers)
//        validateLinearLayersNonEmpty(xLayers, "X")
//        validateLinearLayersNonEmpty(yLayers, "Y")
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
                val offsetRad = layer.offsetDegrees * degreesToRadians
                for (d in 0 until layer.detectorCount) {
                    val center = d * step + phase + offsetRad
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