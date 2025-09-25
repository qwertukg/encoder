import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas

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
    radius: Float = 200f
) {
    PdfDocument(PdfWriter(outputPath)).use { pdf ->
        val page = pdf.addNewPage(PageSize.A4)
        val pdfCanvas = PdfCanvas(page)

        // Геометрия страницы
        val pageCenterX = page.pageSize.width  / 2f
        val pageCenterY = page.pageSize.height / 2f

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

        // Нормализованный угол подсветки (если задан)
        val markAngleDeg = markAngleRadians?.let { normalizeDegrees0to360(Math.toDegrees(it)) }

        // Подсветка активных детекторов (слой, индекс) по правилу «интервального попадания»
        val activeDetectors = mutableSetOf<Pair<Int, Int>>()
        if (markAngleDeg != null) {
            val layerCount = layers.size.coerceAtLeast(1)
            layers.forEachIndexed { layerIndex, layer ->
                val centerStepRadians = fullCircleInRadians / layer.detectorCount
                val centerStepDeg = Math.toDegrees(centerStepRadians)
                val layerPhaseRadians = (layerIndex.toDouble() / layerCount) * centerStepRadians
                val layerPhaseDeg = Math.toDegrees(layerPhaseRadians)

                val windowWidthDeg = layer.arcLengthDegrees * (1.0 + layer.overlapFraction)
                val halfWindowDeg = windowWidthDeg / 2.0

                for (detectorIndex in 0 until layer.detectorCount) {
                    val centerDeg = normalizeDegrees0to360(detectorIndex * centerStepDeg + layerPhaseDeg)
                    if (hitWindowDeg(markAngleDeg, centerDeg, halfWindowDeg)) {
                        activeDetectors += layerIndex to detectorIndex
                    }
                }
            }
        }

        // Рисуем дуги детекторов каждого слоя
        var layerIndex = 0
        var radialOffset = 15.0
        val layerCount = layers.size.coerceAtLeast(1)

        layers.forEach { layer ->
            val color = layerColors[layerIndex % layerColors.size]

            val centerStepRadians = fullCircleInRadians / layer.detectorCount
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
            radialOffset += 15.0
        }

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
    }
}