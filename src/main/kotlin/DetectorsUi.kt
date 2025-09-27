import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val EPS_DEG = 1e-7

/**
 * Ktor-модуль, который заменяет PDF-визуализацию интерактивным UI.
 * Структура интерфейса следует канонам статьи DAML: отображаются конфигурации слоёв,
 * активные детекторы и профиль фоновой корреляции (разд. 4.4.1 и 2.2.4.1).
 */
fun Application.detectorsUiModule(
    encoder: SlidingWindowAngleEncoder,
    backgroundAnalyzer: BackgroundCorrelationAnalyzer,
    initialCanonicalSamples: List<Pair<Double, IntArray>>,
    initialLayout: DampLayoutVisualization
) {
    val canonicalCodesRef = AtomicReference(initialCanonicalSamples)
    val layoutRef = AtomicReference(initialLayout)

    routing {
        get("/") {
            val angleParam = call.request.queryParameters["angle"]
            val angleDeg = normalizeDegrees0to360(angleParam?.toDoubleOrNull() ?: 0.0)
            val angleRadians = angleDeg * PI / 180.0
            val encoded = encoder.encode(angleRadians)
            val activeDetectors = collectActiveDetectors(encoded, encoder.layers)
            val canonicalSamples = canonicalCodesRef.get()
            val analysis = backgroundAnalyzer.analyzeWithAngles(canonicalSamples, angleDeg)
            val stats = analysis.stats
            val profile = analysis.correlationProfile
            val layoutVisualization = layoutRef.get()

            val detectorsSvg = buildDetectorsSvg(
                layers = encoder.layers,
                activeDetectors = activeDetectors,
                markAngleRadians = angleRadians,
                markAngleDegrees = angleDeg
            )
            val correlationSvg = profile?.let { buildCorrelationSvg(it) }
            val layoutSvg = buildLayoutSvg(layoutVisualization)
            val encodedBitsBlock = formatCodeBlocks(encoded)
            val activeBitCount = encoded.count { it != 0 }

            val configStatus = call.request.queryParameters["configStatus"]
            val configMessage = call.request.queryParameters["configMessage"]
            val feedback = when (configStatus) {
                "ok" -> ConfigFeedback.Success(configMessage ?: "Конфигурация слоёв обновлена по канону DAML.")
                "error" -> ConfigFeedback.Error(configMessage ?: "Не удалось применить конфигурацию слоёв.")
                else -> null
            }

            call.respondHtml {
                head {
                    meta { charset = "utf-8" }
                    title { +"Визуализатор DAML-кодера" }
                    style {
                        unsafe {
                            raw(
                                """
                                body {
                                    font-family: 'JetBrains Mono', 'Fira Code', monospace;
                                    background: #f3f4f6;
                                    margin: 0;
                                    padding: 32px;
                                    color: #111827;
                                }
                                h1 {
                                    margin-top: 0;
                                    margin-bottom: 24px;
                                    font-size: 28px;
                                }
                                form.angle-input {
                                    display: flex;
                                    flex-wrap: wrap;
                                    gap: 12px;
                                    align-items: center;
                                    margin-bottom: 24px;
                                }
                                form.angle-input input[type="number"] {
                                    padding: 8px 12px;
                                    border-radius: 8px;
                                    border: 1px solid #d1d5db;
                                    background: #fff;
                                    min-width: 120px;
                                }
                                form.angle-input input[type="submit"] {
                                    padding: 8px 18px;
                                    border-radius: 8px;
                                    border: none;
                                    background: #2563eb;
                                    color: #fff;
                                    font-weight: 600;
                                    cursor: pointer;
                                }
                                form.layer-config {
                                    display: flex;
                                    flex-direction: column;
                                    gap: 16px;
                                }
                                form.layer-config textarea {
                                    font-family: 'JetBrains Mono', 'Fira Code', monospace;
                                    min-height: 160px;
                                    border-radius: 12px;
                                    border: 1px solid #d1d5db;
                                    padding: 12px 14px;
                                    resize: vertical;
                                }
                                form.layer-config .inputs {
                                    display: flex;
                                    flex-wrap: wrap;
                                    gap: 16px;
                                }
                                form.layer-config input[type="number"] {
                                    padding: 8px 12px;
                                    border-radius: 8px;
                                    border: 1px solid #d1d5db;
                                    background: #fff;
                                    min-width: 120px;
                                }
                                form.layer-config input[type="submit"] {
                                    padding: 10px 22px;
                                    border-radius: 10px;
                                    border: none;
                                    background: linear-gradient(135deg, #2563eb, #9333ea);
                                    color: #fff;
                                    font-weight: 600;
                                    cursor: pointer;
                                    align-self: flex-start;
                                }
                                .grid {
                                    display: grid;
                                    gap: 24px;
                                    grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
                                    margin-bottom: 24px;
                                }
                                .card {
                                    background: #fff;
                                    border-radius: 16px;
                                    padding: 24px;
                                    box-shadow: 0 12px 24px rgba(15, 23, 42, 0.12);
                                }
                                .card h2 {
                                    margin-top: 0;
                                    margin-bottom: 16px;
                                    font-size: 20px;
                                }
                                pre.bits {
                                    font-size: 10px;
                                    background: #0f172a;
                                    color: #e2e8f0;
                                    padding: 16px;
                                    border-radius: 12px;
                                    overflow-x: auto;
                                    white-space: pre-wrap;
                                }
                                table.layers {
                                    width: 100%;
                                    border-collapse: collapse;
                                }
                                table.layers th, table.layers td {
                                    border-bottom: 1px solid #e5e7eb;
                                    padding: 8px 0;
                                    text-align: left;
                                    font-size: 14px;
                                }
                                table.layers th {
                                    color: #6b7280;
                                    font-weight: 600;
                                }
                                .stats {
                                    display: flex;
                                    flex-wrap: wrap;
                                    gap: 24px;
                                    margin-bottom: 24px;
                                }
                                .stat {
                                    background: linear-gradient(135deg, #2563eb, #9333ea);
                                    color: #fff;
                                    padding: 16px 20px;
                                    border-radius: 14px;
                                    min-width: 180px;
                                    box-shadow: 0 10px 20px rgba(79, 70, 229, 0.25);
                                }
                                .stat span.value {
                                    display: block;
                                    font-size: 22px;
                                    font-weight: 700;
                                }
                                .stat span.label {
                                    font-size: 12px;
                                    text-transform: uppercase;
                                    letter-spacing: 0.08em;
                                }
                                .svg-wrapper {
                                    width: 100%;
                                    overflow: auto;
                                }
                                .note {
                                    font-size: 13px;
                                    color: #6b7280;
                                }
                                .config-message {
                                    padding: 12px 16px;
                                    border-radius: 10px;
                                    font-size: 14px;
                                    font-weight: 500;
                                }
                                .config-message.success {
                                    background: rgba(34, 197, 94, 0.12);
                                    color: #166534;
                                    border: 1px solid rgba(34, 197, 94, 0.4);
                                }
                                .config-message.error {
                                    background: rgba(248, 113, 113, 0.12);
                                    color: #991b1b;
                                    border: 1px solid rgba(248, 113, 113, 0.4);
                                }
                                """.trimIndent()
                            )
                        }
                    }
                }
                body {
                    h1 { +"Интерактивный DAML-кодер (Ktor UI)" }
                    form(classes = "angle-input") {
                        method = FormMethod.get
                        label {
                            htmlFor = "angle"
                            +"Угол в градусах (0–360):"
                        }
                        numberInput(name = "angle") {
                            id = "angle"
                            step = "0.1"
                            value = String.format(Locale.US, "%.2f", angleDeg)
                            min = "0.0"
                            max = "360.0"
                        }
                        submitInput { value = "Обновить" }
                    }

                    div(classes = "stats") {
                        div(classes = "stat") {
                            span("value") { +String.format(Locale.US, "%.6f", stats.meanCorrelation) }
                            span("label") { +"Средняя корреляция" }
                        }
                        div(classes = "stat") {
                            span("value") { +String.format(Locale.US, "%.6f", stats.maxCorrelation) }
                            span("label") { +"Максимальная корреляция" }
                        }
                        div(classes = "stat") {
                            span("value") { +"$activeBitCount / ${encoded.size}" }
                            span("label") { +"Активные биты" }
                        }
                    }

                    div(classes = "grid") {
                        div(classes = "card") {
                            h2 { +"Слои детекторов" }
                            div(classes = "svg-wrapper") {
                                unsafe { raw(detectorsSvg) }
                            }
                            p(classes = "note") {
                                +"Дуги построены по схеме скользящих окон из разд. 4.4.1 DAML."
                            }
                        }
                        div(classes = "card") {
                            h2 { +"Каноническая 2D-раскладка кодов" }
                            div(classes = "svg-wrapper") {
                                unsafe { raw(layoutSvg) }
                            }
                            p(classes = "note") {
                                +"Цвет точек кодов соответствует углу из выборки по HSV (см. разд. 5.5 DAML)."
                            }
                        }
                        div(classes = "card") {
                            h2 { +"Профиль фоновой корреляции" }
                            if (correlationSvg != null) {
                                div(classes = "svg-wrapper") {
                                    unsafe { raw(correlationSvg) }
                                }
                            } else {
                                p { +"Недостаточно данных для профиля корреляции." }
                            }
                            p(classes = "note") {
                                +"Расчёт выполнен дискретной косинусной мерой, как предписано в разд. 2.2.4.1 DAML."
                            }
                        }
                    }

                    div(classes = "card") {
                        h2 { +"Битовый код" }
                        pre(classes = "bits") {
                            +encodedBitsBlock
                        }
                    }
                    br
                    div(classes = "card") {
                        h2 { +"Конфигурация слоёв" }
                        feedback?.let { message ->
                            div(classes = "config-message ${if (message is ConfigFeedback.Success) "success" else "error"}") {
                                +message.text
                            }
                        }
                        form(classes = "layer-config") {
                            method = FormMethod.post
                            action = "/layers"
                            textArea {
                                name = "layersConfig"
                                +buildLayerConfigText(encoder.layers)
                            }
                            div(classes = "inputs") {
                                div {
                                    label {
                                        htmlFor = "codeSize"
                                        +"Размер кодового слова (≥ сумма детекторов):"
                                    }
                                    numberInput(name = "codeSize") {
                                        id = "codeSize"
                                        min = "1"
                                        value = encoder.codeSizeInBits.toString()
                                    }
                                }
                                hiddenInput(name = "angle") {
                                    value = String.format(Locale.US, "%.2f", angleDeg)
                                }
                            }
                            submitInput { value = "Применить конфигурацию" }
                        }
                        p(classes = "note") {
                            +"Каждая строка описывает слой: длина дуги; число детекторов; перекрытие (см. разд. 4.4.1 DAML)."
                        }
                        table(classes = "layers") {
                            thead {
                                tr {
                                    th { +"Слой" }
                                    th { +"Длина дуги, °" }
                                    th { +"Детекторов" }
                                    th { +"Перекрытие" }
                                }
                            }
                            tbody {
                                encoder.layers.forEachIndexed { index, layer ->
                                    tr {
                                        td { +(index + 1).toString() }
                                        td { +String.format(Locale.US, "%.3f", layer.arcLengthDegrees) }
                                        td { +layer.detectorCount.toString() }
                                        td { +String.format(Locale.US, "%.2f", layer.overlapFraction) }
                                    }
                                }
                            }
                            tfoot {
                                tr {
                                    td { +"Σ" }
                                    td { +"—" }
                                    td { +encoder.layers.sumOf { it.detectorCount }.toString() }
                                    td { +"—" }
                                }
                            }
                        }
                    }
                }
            }
        }
        post("/layers") {
            val params = call.receiveParameters()
            val rawConfig = params["layersConfig"] ?: ""
            val angleParam = params["angle"]?.takeIf { it.isNotBlank() }
            val codeSizeParam = params["codeSize"]?.takeIf { it.isNotBlank() }

            try {
                val layers = parseLayersConfiguration(rawConfig)
                val requestedCodeSize = codeSizeParam?.toIntOrNull()?.also {
                    require(it > 0) { "Размер кодового слова должен быть положительным" }
                }

                encoder.reconfigure(layers, requestedCodeSize)
                val newSamples = encoder.sampleFullCircle(stepDegrees = 1.0)
                canonicalCodesRef.set(newSamples)
                layoutRef.set(buildDampLayoutVisualization(newSamples))

                call.respondRedirect(buildConfigRedirectUrl(angleParam, "ok", "Конфигурация слоёв обновлена."))
            } catch (ex: IllegalArgumentException) {
                call.respondRedirect(
                    buildConfigRedirectUrl(angleParam, "error", ex.message ?: "Некорректные параметры.")
                )
            }
        }
    }
}

private sealed interface ConfigFeedback {
    val text: String

    class Success(override val text: String) : ConfigFeedback
    class Error(override val text: String) : ConfigFeedback
}

private fun buildLayerConfigText(layers: List<SlidingWindowAngleEncoder.Layer>): String =
    layers.joinToString("\n") { layer ->
        listOf(
            String.format(Locale.US, "%.6f", layer.arcLengthDegrees),
            layer.detectorCount.toString(),
            String.format(Locale.US, "%.4f", layer.overlapFraction)
        ).joinToString("; ")
    }

private fun parseLayersConfiguration(raw: String): List<SlidingWindowAngleEncoder.Layer> {
    val lines = raw.lineSequence()
        .mapIndexedNotNull { index, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapIndexedNotNull null
            val parts = trimmed.split(';', ',', ' ', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            require(parts.size == 3) {
                "Строка ${index + 1}: ожидалось три параметра (длина; детекторы; перекрытие)"
            }

            val arcLength = parts[0].replace(',', '.').toDoubleOrNull()
                ?: throw IllegalArgumentException("Строка ${index + 1}: не удалось прочитать длину дуги")
            val detectorCount = parts[1].toIntOrNull()
                ?: throw IllegalArgumentException("Строка ${index + 1}: количество детекторов должно быть целым")
            val overlap = parts[2].replace(',', '.').toDoubleOrNull()
                ?: throw IllegalArgumentException("Строка ${index + 1}: перекрытие должно быть числом")

            SlidingWindowAngleEncoder.Layer(
                arcLengthDegrees = arcLength,
                detectorCount = detectorCount,
                overlapFraction = overlap
            )
        }
        .toList()

    require(lines.isNotEmpty()) { "Нужно задать хотя бы один слой" }
    return lines
}

private fun buildConfigRedirectUrl(angle: String?, status: String, message: String): String {
    val queryParams = mutableListOf<String>()
    if (!angle.isNullOrBlank()) {
        queryParams += "angle=" + URLEncoder.encode(angle, StandardCharsets.UTF_8)
    }
    queryParams += "configStatus=" + URLEncoder.encode(status, StandardCharsets.UTF_8)
    queryParams += "configMessage=" + URLEncoder.encode(message, StandardCharsets.UTF_8)
    return buildString {
        append('/')
        if (queryParams.isNotEmpty()) {
            append('?')
            append(queryParams.joinToString("&"))
        }
    }
}

private fun normalizeDegrees0to360(angleDegrees: Double): Double {
    var x = angleDegrees % 360.0
    if (x < 0.0) x += 360.0
    return if (x >= 360.0 && x - 360.0 < EPS_DEG) 0.0 else x
}

private fun collectActiveDetectors(
    encodedBits: IntArray,
    layers: List<SlidingWindowAngleEncoder.Layer>
): Set<Pair<Int, Int>> {
    val active = mutableSetOf<Pair<Int, Int>>()
    var offset = 0
    layers.forEachIndexed { layerIndex, layer ->
        for (detectorIndex in 0 until layer.detectorCount) {
            val bitIndex = offset + detectorIndex
            if (bitIndex < encodedBits.size && encodedBits[bitIndex] != 0) {
                active += layerIndex to detectorIndex
            }
        }
        offset += layer.detectorCount
    }
    return active
}

private fun buildDetectorsSvg(
    layers: List<SlidingWindowAngleEncoder.Layer>,
    activeDetectors: Set<Pair<Int, Int>>,
    markAngleRadians: Double,
    markAngleDegrees: Double
): String {
    val width = 640.0
    val height = 520.0
    val centerX = width / 2.0
    val centerY = height / 2.0
    val baseRadius = 150.0
    val layerColors = listOf(
        "#3478F6",
        "#22C55E",
        "#EAB308",
        "#EF4444",
        "#A855F7",
        "#10B981",
        "#3B82F6",
        "#F59E0B"
    )

    val builder = StringBuilder()
    builder.appendLine("""<svg viewBox="0 0 $width $height" width="$width" height="$height" xmlns="http://www.w3.org/2000/svg">""")
    builder.appendLine("""<rect x="0" y="0" width="$width" height="$height" fill="#ffffff" rx="18"/>""")
    builder.appendLine("""<circle cx="${fmt(centerX)}" cy="${fmt(centerY)}" r="${fmt(baseRadius)}" fill="none" stroke="#111827" stroke-width="1.2"/>""")

    val markX = centerX + baseRadius * cos(markAngleRadians)
    val markY = centerY - baseRadius * sin(markAngleRadians)
    builder.appendLine(
        """<line x1="${fmt(centerX)}" y1="${fmt(centerY)}" x2="${fmt(markX)}" y2="${fmt(markY)}" stroke="#111827" stroke-width="1.4" stroke-dasharray="6 4"/>"""
    )
    builder.appendLine(
        """<text x="${fmt(centerX)}" y="${fmt(centerY - baseRadius - 20)}" text-anchor="middle" fill="#1f2937" font-size="14">${String.format(Locale.US, "%.2f°", markAngleDegrees)}</text>"""
    )

    var radialOffset = 18.0
    val layerCount = layers.size.coerceAtLeast(1)
    layers.forEachIndexed { layerIndex, layer ->
        val color = layerColors[layerIndex % layerColors.size]
        val centerStepDeg = layer.arcLengthDegrees
        val windowWidthDeg = layer.arcLengthDegrees * (1.0 + layer.overlapFraction)
        val halfWindowDeg = windowWidthDeg / 2.0
        val radius = baseRadius + radialOffset
        val layerPhaseDeg = (layerIndex.toDouble() / layerCount) * centerStepDeg

        for (detectorIndex in 0 until layer.detectorCount) {
            val centerDeg = detectorIndex * centerStepDeg + layerPhaseDeg
            val startDeg = centerDeg - halfWindowDeg
            val extentDeg = windowWidthDeg
            val points = buildArcPolylinePoints(centerX, centerY, radius, startDeg, extentDeg)
            val active = (layerIndex to detectorIndex) in activeDetectors
            val strokeWidth = if (active) "2.4" else "1.0"
            val opacity = if (active) "1.0" else "0.6"
            builder.appendLine(
                """<polyline fill="none" stroke="$color" stroke-width="$strokeWidth" stroke-linecap="round" opacity="$opacity" points="$points"/>"""
            )
        }

        radialOffset += 18.0
    }

    builder.appendLine("</svg>")
    return builder.toString()
}

private fun buildArcPolylinePoints(
    centerX: Double,
    centerY: Double,
    radius: Double,
    startDeg: Double,
    extentDeg: Double,
    segments: Int = 180
): String {
    val points = mutableListOf<String>()
    for (s in 0..segments) {
        val t = s.toDouble() / segments
        val angleDeg = startDeg + extentDeg * t
        val angleRad = angleDeg * PI / 180.0
        val px = centerX + radius * cos(angleRad)
        val py = centerY - radius * sin(angleRad)
        points += fmt(px) + "," + fmt(py)
    }
    return points.joinToString(" ")
}

private fun buildCorrelationSvg(profile: BackgroundCorrelationAnalyzer.CorrelationProfile): String {
    val width = 640.0
    val height = 320.0
    val marginLeft = 56.0
    val marginRight = 24.0
    val marginTop = 32.0
    val marginBottom = 48.0

    val chartWidth = width - marginLeft - marginRight
    val chartHeight = height - marginTop - marginBottom

    val points = profile.points.sortedBy { it.angleDegrees }
    val maxCorrelation = points.maxOfOrNull { it.correlation } ?: 0.0
    val safeMax = if (maxCorrelation <= 0.0) 1.0 else maxCorrelation

    fun angleToX(angleDeg: Double): Double = marginLeft + (normalizeDegrees0to360(angleDeg) / 360.0) * chartWidth
    fun valueToY(value: Double): Double = marginTop + chartHeight - (value / safeMax) * chartHeight

    val polylinePoints = points.joinToString(" ") { point ->
        fmt(angleToX(point.angleDegrees)) + "," + fmt(valueToY(point.correlation))
    }

    val builder = StringBuilder()
    builder.appendLine("""<svg viewBox="0 0 $width $height" width="$width" height="$height" xmlns="http://www.w3.org/2000/svg">""")
    builder.appendLine("""<rect x="0" y="0" width="$width" height="$height" fill="#ffffff" rx="18"/>""")

    // Оси
    builder.appendLine(
        """<line x1="${fmt(marginLeft)}" y1="${fmt(marginTop)}" x2="${fmt(marginLeft)}" y2="${fmt(marginTop + chartHeight)}" stroke="#111827" stroke-width="1.2"/>"""
    )
    builder.appendLine(
        """<line x1="${fmt(marginLeft)}" y1="${fmt(marginTop + chartHeight)}" x2="${fmt(marginLeft + chartWidth)}" y2="${fmt(marginTop + chartHeight)}" stroke="#111827" stroke-width="1.2"/>"""
    )

    // Деления по углам
    listOf(0.0, 90.0, 180.0, 270.0, 360.0).forEach { angle ->
        val x = angleToX(angle)
        builder.appendLine(
            """<line x1="${fmt(x)}" y1="${fmt(marginTop + chartHeight)}" x2="${fmt(x)}" y2="${fmt(marginTop + chartHeight + 8)}" stroke="#9ca3af" stroke-width="1"/>"""
        )
        builder.appendLine(
            """<text x="${fmt(x)}" y="${fmt(marginTop + chartHeight + 24)}" fill="#4b5563" font-size="12" text-anchor="middle">${String.format(Locale.US, "%.0f°", angle)}</text>"""
        )
    }

    // Деления по значению корреляции
    listOf(0.0, safeMax).distinct().forEach { value ->
        val y = valueToY(value)
        builder.appendLine(
            """<line x1="${fmt(marginLeft - 8)}" y1="${fmt(y)}" x2="${fmt(marginLeft)}" y2="${fmt(y)}" stroke="#9ca3af" stroke-width="1"/>"""
        )
        builder.appendLine(
            """<text x="${fmt(marginLeft - 12)}" y="${fmt(y + 4)}" fill="#4b5563" font-size="12" text-anchor="end">${String.format(Locale.US, "%.3f", value)}</text>"""
        )
    }

    builder.appendLine(
        """<polyline fill="none" stroke="#2563EB" stroke-width="2.4" stroke-linejoin="round" stroke-linecap="round" points="$polylinePoints"/>"""
    )

    val refX = angleToX(profile.referenceAngleDegrees)
    builder.appendLine(
        """<line x1="${fmt(refX)}" y1="${fmt(marginTop)}" x2="${fmt(refX)}" y2="${fmt(marginTop + chartHeight)}" stroke="#f97316" stroke-dasharray="6 6" stroke-width="1.5"/>"""
    )
    builder.appendLine(
        """<text x="${fmt(refX)}" y="${fmt(marginTop - 10)}" fill="#f97316" font-size="12" text-anchor="middle">${String.format(Locale.US, "Опорный угол %.2f°", normalizeDegrees0to360(profile.referenceAngleDegrees))}</text>"""
    )

    builder.appendLine("</svg>")
    return builder.toString()
}

private fun buildLayoutSvg(layout: DampLayoutVisualization): String {
    if (layout.width <= 0 || layout.height <= 0) {
        return """<svg viewBox=\"0 0 120 80\" width=\"120\" height=\"80\" xmlns=\"http://www.w3.org/2000/svg\"><text x=\"60\" y=\"45\" fill=\"#6b7280\" font-size=\"12\" text-anchor=\"middle\">Нет данных</text></svg>"""
    }

    val cellSize = 18.0
    val padding = 28.0
    val svgWidth = padding * 2 + layout.width * cellSize
    val svgHeight = padding * 2 + layout.height * cellSize
    val pointRadius = cellSize * 0.35

    val builder = StringBuilder()
    builder.appendLine("""<svg viewBox=\"0 0 ${fmt(svgWidth)} ${fmt(svgHeight)}\" width=\"${fmt(svgWidth)}\" height=\"${fmt(svgHeight)}\" xmlns=\"http://www.w3.org/2000/svg\">""")
    builder.appendLine("""<rect x=\"0\" y=\"0\" width=\"${fmt(svgWidth)}\" height=\"${fmt(svgHeight)}\" fill=\"#ffffff\" rx=\"18\"/>""")

    layout.points.forEach { point ->
        val cx = padding + (point.x + 0.5) * cellSize
        val cy = padding + (point.y + 0.5) * cellSize
        val color = hsvToHex(point.angleDegrees)
        builder.appendLine(
            """<circle cx=\"${fmt(cx)}\" cy=\"${fmt(cy)}\" r=\"${fmt(pointRadius)}\" fill=\"$color\" stroke=\"#111827\" stroke-width=\"0.5\"/>"""
        )
    }

    builder.appendLine("</svg>")
    return builder.toString()
}

private fun hsvToHex(angleDegrees: Double, saturation: Double = 1.0, value: Double = 1.0): String {
    val h = ((angleDegrees % 360.0) + 360.0) % 360.0
    val c = value * saturation
    val x = c * (1 - kotlin.math.abs((h / 60.0) % 2 - 1))
    val m = value - c

    val (r1, g1, b1) = when {
        h < 60.0 -> Triple(c, x, 0.0)
        h < 120.0 -> Triple(x, c, 0.0)
        h < 180.0 -> Triple(0.0, c, x)
        h < 240.0 -> Triple(0.0, x, c)
        h < 300.0 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }

    val r = ((r1 + m) * 255.0).roundToInt().coerceIn(0, 255)
    val g = ((g1 + m) * 255.0).roundToInt().coerceIn(0, 255)
    val b = ((b1 + m) * 255.0).roundToInt().coerceIn(0, 255)

    return String.format("#%02X%02X%02X", r, g, b)
}

private fun formatCodeBlocks(code: IntArray, groupSize: Int = 4, rowSize: Int = 64): String {
    if (code.isEmpty()) return ""
    val builder = StringBuilder()
    var index = 0
    while (index < code.size) {
        builder.append(if (code[index] == 0) "." else "|")
        index++
//        val limit = min(index + rowSize, code.size)
//        var groupCounter = 0
//        for (i in index until limit) {
//            builder.append(code[i])
//            groupCounter++
//            if (groupCounter == groupSize && i != limit - 1) {
//                builder.append(' ')
//                groupCounter = 0
//            }
//        }
//        index = limit
//        if (index < code.size) {
//            builder.append('\n')
//        }
    }
    return builder.toString()
}

private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)
