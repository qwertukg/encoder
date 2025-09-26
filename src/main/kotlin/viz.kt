import BackgroundCorrelationAnalyzer.CorrelationProfile
import com.itextpdf.io.font.constants.StandardFonts
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.math.max

// ----------------- утилиты для работы в градусах -----------------

val EPS_DEG = 1e-7

/**
 * Рисует дугу, у которой радиус плавно увеличивается на dR от начала к концу.
 *
 * @param canvas PdfCanvas для рисования
 * @param x1,y1,x2,y2 прямоугольник описывающий базовую окружность (bbox)
 * @param startDeg начальный угол (градусы)
 * @param extentDeg длина дуги (градусы)
 * @param dR на сколько увеличить радиус к концу дуги
 * @param segments сколько частей использовать для аппроксимации (чем больше — тем глаже)
 * @param color цвет линии/заливки
 */
fun SlidingWindowAngleEncoder.drawArcRotated(
    canvas: PdfCanvas,
    x1: Double, y1: Double, x2: Double, y2: Double,
    startDeg: Double, extentDeg: Double,
    dR: Double = 10.0,
    segments: Int = 120,
    color: DeviceRgb = DeviceRgb(52, 120, 246)
) {
    val centerX = (x1 + x2) / 2.0
    val centerY = (y1 + y2) / 2.0
    val baseRadius = (x2 - x1) / 2.0

    canvas.setStrokeColor(color)

    // Идём монотонно от startDeg к startDeg+extentDeg без нормализации и без разрезов
    for (s in 0..segments) {
        val t = s.toDouble() / segments
        val angleDeg = startDeg + extentDeg * t
        val angleRad = Math.toRadians(angleDeg) // sin/cos сами «оборачиваются» каждый 2π
        val radius = baseRadius + dR * t

        val px = centerX + radius * kotlin.math.cos(angleRad)
        val py = centerY + radius * kotlin.math.sin(angleRad)

        if (s == 0) canvas.moveTo(px, py) else canvas.lineTo(px, py)
    }
    canvas.stroke()
}


/** Нормализует угол в градусах в диапазон [0, 360). */
fun SlidingWindowAngleEncoder.normalizeDegrees0to360(angleDegrees: Double): Double {
    var x = angleDegrees % 360.0
    if (x < 0.0) x += 360.0
    return x
}

/** Разбивает дугу (startDeg, extentDeg) на 1–2 непрерывных отрезка с учётом перехода через 360°. */
fun SlidingWindowAngleEncoder.splitArcDegrees(startDegRaw: Double, extentDegRaw: Double): List<Pair<Double, Double>> {
    val start = normalizeDegrees0to360(startDegRaw)
    var extent = extentDegRaw % 360.0
    if (extent < 0.0) extent += 360.0
    if (extent <= EPS_DEG) return emptyList()

    val end = start + extent
    return if (end <= 360.0 + EPS_DEG) {
        listOf(start to extent)                               // не пересекает 360
    } else {
        val first  = 360.0 - start
        val second = end - 360.0
        buildList {
            if (first  > EPS_DEG) add(start to first)         // [start .. 360)
            if (second > EPS_DEG) add(0.0   to second)        // [0 .. second)
        }
    }
}

// ----------------- проверка «попадания» угла в окно детектора -----------------

/**
 * Возвращает true, если угол angleDeg (в градусах, уже нормализован в [0,360))
 * попадает в окно детектора с центром centerDeg и половинной шириной halfWindowDeg,
 * учитывая возможный переход через 360° (интервальное сравнение по модулю окружности).
 *
 * Левая граница включена, правая — исключена (как в encode): [start, end).
 */
fun SlidingWindowAngleEncoder.hitWindowDeg(angleDeg: Double, centerDeg: Double, halfWindowDeg: Double): Boolean {
    val startRaw = centerDeg - halfWindowDeg
    val endRaw   = centerDeg + halfWindowDeg

    val start = normalizeDegrees0to360(startRaw)
    val end   = normalizeDegrees0to360(endRaw)

    return if (start <= end) {
        angleDeg >= start && angleDeg < end
    } else {
        angleDeg >= start || angleDeg < end
    }
}


fun SlidingWindowAngleEncoder.drawDetectorsPdf(
    outputPath: String,
    markAngleRadians: Double? = null,
    correlationProfile: CorrelationProfile? = null,
    radius: Float = 150f
) {
    val outputFile = File(outputPath)
    outputFile.parentFile?.let { parent ->
        if (!parent.exists()) parent.mkdirs()
    }

    val tempFilePath = Files.createTempFile("detectors-temp", ".pdf")
    try {
        PdfDocument(PdfWriter(tempFilePath.toString())).use { pdf ->
            if (outputFile.exists()) {
                PdfDocument(PdfReader(outputFile)).use { existing ->
                    if (existing.numberOfPages > 0) {
                        existing.copyPagesTo(1, existing.numberOfPages, pdf)
                    }
                }
            }

            val page = pdf.addNewPage(PageSize.A4)
            val markAngleDeg = markAngleRadians?.let { normalizeDegrees0to360(Math.toDegrees(it)) }
            markAngleDeg?.let {
                val labelPrefix = String.format(Locale.US, "%.2f° ", it)
                page.setPageLabel(null, labelPrefix)
            }
            val pdfCanvas = PdfCanvas(page)
            val font = PdfFontFactory.createFont(StandardFonts.COURIER)

            // Геометрия страницы
            val pageCenterX = page.pageSize.width  / 2f
            val pageCenterY = (page.pageSize.height / 3f) * 1

            // Базовый круг
            pdfCanvas.setLineWidth(1f)
                .setStrokeColor(DeviceRgb(0, 0, 0))
                .circle(pageCenterX.toDouble(), pageCenterY.toDouble(), radius.toDouble())
                .stroke()

            // Палитра для слоёв
            val layerColors = listOf(
                DeviceRgb(52, 120, 246),
                DeviceRgb(34, 197, 94),
                DeviceRgb(234, 179, 8),
                DeviceRgb(239, 68, 68),
                DeviceRgb(168, 85, 247),
                DeviceRgb(16, 185, 129),
                DeviceRgb(59, 130, 246),
                DeviceRgb(245, 158, 11)
            )

            // Нормализованный угол подсветки (если задан) вычислен выше (markAngleDeg).

            // Код, полученный по каноническому encode из разд. 4.4.1 DAML (используем для подсветки и подписи).
            val encodedBitsForMark = markAngleRadians?.let { encode(it) }

            // Подсветка активных детекторов (слой, индекс) берём из готового кода, чтобы визуализация совпадала с encode.
            val activeDetectors = mutableSetOf<Pair<Int, Int>>()
            if (encodedBitsForMark != null) {
                var globalBitOffset = 0
                layers.forEachIndexed { layerIndex, layer ->
                    for (detectorIndex in 0 until layer.detectorCount) {
                        val bitIndex = globalBitOffset + detectorIndex
                        if (bitIndex < encodedBitsForMark.size && encodedBitsForMark[bitIndex] == 1) {
                            activeDetectors += layerIndex to detectorIndex
                        }
                    }
                    globalBitOffset += layer.detectorCount
                }
            }

            // Рисуем дуги детекторов каждого слоя
            var layerIndex = 0
            var radialOffset = 15.0
            var maxRadialOffsetUsed = 0.0
            val layerCount = layers.size.coerceAtLeast(1)

            layers.forEach { layer ->
                val color = layerColors[layerIndex % layerColors.size]

                val centerStepRadians = (layer.arcLengthDegrees) * Math.PI / 180.0
                val centerStepDeg = Math.toDegrees(centerStepRadians)
                val layerPhaseRadians = (layerIndex.toDouble() / layerCount) * centerStepRadians
                val layerPhaseDeg = Math.toDegrees(layerPhaseRadians)

                val windowWidthDeg = layer.arcLengthDegrees * (1.0 + layer.overlapFraction)
                val halfWindowDeg = windowWidthDeg / 2.0

                // bbox для дуг этого слоя (слегка увеличиваем радиус на слой)
                val x1 = (pageCenterX - (radius + radialOffset)).toDouble()
                val y1 = (pageCenterY - (radius + radialOffset)).toDouble()
                val x2 = (pageCenterX + (radius + radialOffset)).toDouble()
                val y2 = (pageCenterY + (radius + radialOffset)).toDouble()

                for (detectorIndex in 0 until layer.detectorCount) {
                    val centerDeg = detectorIndex * centerStepDeg + layerPhaseDeg
                    val startDeg = centerDeg - halfWindowDeg
                    val extentDeg = windowWidthDeg

                    val isActive = (layerIndex to detectorIndex) in activeDetectors
                    pdfCanvas.setStrokeColor(color)
                    pdfCanvas.setLineWidth(if (isActive) 2.0f else 1.0f)

                    // один вызов — одна непрерывная дуга (без «двух маленьких»)
                    drawArcRotated(
                        canvas = pdfCanvas,
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        startDeg = startDeg,
                        extentDeg = extentDeg,
                        dR = 7.0,
                        segments = 120,
                        color = color
                    )

                }

                layerIndex++
                if (radialOffset > maxRadialOffsetUsed) {
                    maxRadialOffsetUsed = radialOffset
                }
                radialOffset += 15.0
            }

            // Текстовый блок с характеристиками слоёв, чтобы pdf содержал каноническое описание конфигурации из DAML.
            val textStartX = 40.0
            val textTopY = page.pageSize.height - 40.0
            val textLineHeight = 12.0
            val layerHeader = "Detector layers:"

            pdfCanvas.beginText()
                .setFontAndSize(font, 10f)
                .moveText(textStartX, textTopY)
                .showText(layerHeader)

            layers.forEachIndexed { index, layer ->
                val windowWidthDeg = layer.arcLengthDegrees * (1.0 + layer.overlapFraction)
                val centerStepDeg = layer.arcLengthDegrees
                val layerDescription = String.format(
                    Locale.getDefault(),
                    "Layer %d: bow %.3f°, count %d, overlap %.2f",
                    index + 1,
                    layer.arcLengthDegrees,
                    layer.detectorCount,
                    layer.overlapFraction,
                )
                pdfCanvas.moveText(0.0, -textLineHeight)
                    .showText(layerDescription)
            }
            pdfCanvas.endText()

            val layerTextBlockHeight = textLineHeight * (layers.size + 1)
            val angleInfoTopY = textTopY - layerTextBlockHeight - 16.0

            // (Опционально) Радиальная метка угла markAngleRadians
            if (markAngleRadians != null) {
                val dx = (radius * kotlin.math.cos(markAngleRadians)).toFloat()
                val dy = (radius * kotlin.math.sin(markAngleRadians)).toFloat()
                pdfCanvas.setStrokeColor(DeviceRgb(0, 0, 0))
                    .setLineWidth(0.8f)
                    .moveTo(pageCenterX.toDouble(), pageCenterY.toDouble())
                    .lineTo((pageCenterX + dx).toDouble(), (pageCenterY + dy).toDouble())
                    .stroke()
            }

            var codeBlockBottomY: Double? = null

            // Подписи: угол в градусах и битовый код, оформленный графикой (палочки и пробелы) по канону DAML.
            if (markAngleDeg != null && encodedBitsForMark != null) {
                val angleText = String.format(Locale.US, "Angle: %.2f°", markAngleDeg)

                pdfCanvas.beginText()
                    .setFontAndSize(font, 10f)
                    .moveText(textStartX, angleInfoTopY)
                    .showText(angleText)
                    .moveText(0.0, -textLineHeight)
                    .showText("Code:")

                pdfCanvas.endText()

                val codeStartX = textStartX
                val codeTopY = angleInfoTopY - 2 * textLineHeight - 8.0
                val bitHeight = 10.0
                val rowSpacing = 14.0
                val bitSpacing = 2.0
                val bitsPerRow = 256
                val activeSegmentColor = DeviceRgb(0, 0, 0)
                val inactiveSegmentColor = DeviceRgb(200, 200, 200)

                pdfCanvas.saveState()
                pdfCanvas.setLineWidth(1f)

                var bitIndex = 0
                var rowIndex = 0
                while (bitIndex < encodedBitsForMark.size) {
                    val bitsThisRow = minOf(bitsPerRow, encodedBitsForMark.size - bitIndex)
                    var x = codeStartX
                    val yTop = codeTopY - rowIndex * rowSpacing
                    val yBottom = yTop - bitHeight
                    for (i in 0 until bitsThisRow) {
                        val bit = encodedBitsForMark[bitIndex + i]
                        pdfCanvas.setStrokeColor(if (bit == 1) activeSegmentColor else inactiveSegmentColor)
                        pdfCanvas.moveTo(x, yTop)
                            .lineTo(x, yBottom)
                            .stroke()
                        x += bitSpacing
                    }
                    bitIndex += bitsThisRow
                    rowIndex++
                    codeBlockBottomY = yBottom
                }

                pdfCanvas.restoreState()
            }

            val chartProfile = correlationProfile
            if (chartProfile != null && chartProfile.points.isNotEmpty()) {
                val chartLeft = textStartX
                val chartRight = page.pageSize.width - textStartX
                val chartBottomBase = pageCenterY + (radius + maxRadialOffsetUsed) + 24.0
                val chartBottom = max(chartBottomBase, 40.0)
                val topCandidate = (codeBlockBottomY ?: (angleInfoTopY - 2 * textLineHeight)) - 24.0
                val chartTop = max(topCandidate, chartBottom + 80.0)
                val chartHeight = chartTop - chartBottom
                val chartWidth = chartRight - chartLeft

                val xMin = 0.0
                val xMax = 360.0
                val yMin = 0.0
                val yMaxValue = chartProfile.points.maxOfOrNull { it.correlation } ?: 0.0
                val yMax = if (yMaxValue <= 0.0) 1.0 else max(1.0, yMaxValue * 1.1)

                fun mapX(angle: Double): Double {
                    val clamped = angle.coerceIn(xMin, xMax)
                    val ratio = if (xMax - xMin == 0.0) 0.0 else (clamped - xMin) / (xMax - xMin)
                    return chartLeft + ratio * chartWidth
                }

                fun mapY(value: Double): Double {
                    val safeValue = value.coerceIn(yMin, yMax)
                    val ratio = if (yMax - yMin == 0.0) 0.0 else (safeValue - yMin) / (yMax - yMin)
                    return chartBottom + ratio * chartHeight
                }

                val axisColor = DeviceRgb(90, 90, 90)
                pdfCanvas.saveState()
                pdfCanvas.setLineWidth(0.8f)
                    .setStrokeColor(axisColor)
                    .moveTo(chartLeft, chartBottom)
                    .lineTo(chartLeft, chartTop)
                    .stroke()
                    .moveTo(chartLeft, chartBottom)
                    .lineTo(chartRight, chartBottom)
                    .stroke()

                val yTickCount = 4
                for (i in 1..yTickCount) {
                    val value = yMin + (yMax - yMin) * (i.toDouble() / yTickCount)
                    val y = mapY(value)
                    pdfCanvas.setStrokeColor(DeviceRgb(210, 210, 210))
                        .moveTo(chartLeft, y)
                        .lineTo(chartRight, y)
                        .stroke()
                    pdfCanvas.setStrokeColor(axisColor)
                    pdfCanvas.beginText()
                        .setFontAndSize(font, 8f)
                        .moveText(chartLeft - 30.0, y - 3.0)
                        .showText(String.format(Locale.US, "%.2f", value))
                        .endText()
                }

                val xTickValues = listOf(0.0, 90.0, 180.0, 270.0, 360.0)
                xTickValues.forEach { angleTick ->
                    val x = mapX(angleTick)
                    pdfCanvas.setStrokeColor(axisColor)
                        .moveTo(x, chartBottom)
                        .lineTo(x, chartBottom - 4.0)
                        .stroke()
                    pdfCanvas.beginText()
                        .setFontAndSize(font, 8f)
                        .moveText(x - 10.0, chartBottom - 14.0)
                        .showText(String.format(Locale.US, "%.0f°", angleTick))
                        .endText()
                }

                val sortedPoints = chartProfile.points.sortedBy { it.angleDegrees }
                if (sortedPoints.isNotEmpty()) {
                    pdfCanvas.setStrokeColor(DeviceRgb(52, 120, 246))
                        .setLineWidth(1.2f)
                    sortedPoints.forEachIndexed { index, point ->
                        val x = mapX(point.angleDegrees)
                        val y = mapY(point.correlation)
                        if (index == 0) {
                            pdfCanvas.moveTo(x, y)
                        } else {
                            pdfCanvas.lineTo(x, y)
                        }
                    }
                    pdfCanvas.stroke()

                    pdfCanvas.setFillColor(DeviceRgb(52, 120, 246))
                    sortedPoints.forEach { point ->
                        val x = mapX(point.angleDegrees)
                        val y = mapY(point.correlation)
                        pdfCanvas.circle(x, y, 1.8)
                    }
                    pdfCanvas.fill()
                }

                val referenceX = mapX(chartProfile.referenceAngleDegrees)
                pdfCanvas.setLineWidth(0.8f)
                    .setStrokeColor(DeviceRgb(239, 68, 68))
                    .setLineDash(3f, 3f)
                    .moveTo(referenceX, chartBottom)
                    .lineTo(referenceX, chartTop)
                    .stroke()

                pdfCanvas.restoreState()

                pdfCanvas.beginText()
                    .setFontAndSize(font, 9f)
                    .moveText(chartLeft, chartTop + 12.0)
                    .showText("Профиль корреляции (дискретная косинусная мера)")
                    .moveText(0.0, -12.0)
                    .showText(
                        String.format(
                            Locale.US,
                            "Опорный угол: %.2f°",
                            chartProfile.referenceAngleDegrees
                        )
                    )
                    .endText()
            }
        }

        Files.move(tempFilePath, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(tempFilePath)
    }
}
