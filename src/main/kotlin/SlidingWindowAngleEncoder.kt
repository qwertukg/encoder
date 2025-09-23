import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.geom.AffineTransform
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin


class SlidingWindowAngleEncoder(
    // arcWidthRad -> stepRatio
    val layers: Map<Double, Double> = linkedMapOf(
        (90.0      * PI / 180.0) to 0.5,
        (45.0      * PI / 180.0) to 0.5,
        (22.5      * PI / 180.0) to 0.5,
        (11.25     * PI / 180.0) to 0.5,
        (5.625     * PI / 180.0) to 0.5,
        (2.8125    * PI / 180.0) to 0.5, // with 256 bit - sens step ~<=1°
//        (1.40625   * PI / 180.0) to 0.6,
//        (0.703125  * PI / 180.0) to 0.6,
    ),
    val codeSize: Int = 256
) {
    private val twoPi = 2.0 * PI
    val L = layers.size - 0

    /** Разница углов в (-π, π]. Удобно для проверки симметричного окна вокруг центра. */
    private fun angDiff(a: Double): Double {
        var x = (a + PI) % (2.0 * PI)
        if (x < 0.0) x += 2.0 * PI
        return x - PI
    }

    /** Печать кода в виде массива из 0 и 1. */
    private fun printCode(bits: IntArray, a: Double) {
        println(bits.joinToString(prefix = "[", postfix = "]", separator = "") + ":${a * 180.0 / PI}")
    }

    /**
     * Закодировать угол (Double в радианах) методом «скользящего окна».
     */
    fun encode(angleRadians: Double): IntArray {
        val out = IntArray(codeSize)
        var offset = 0
        var layerIdx = 0

        // Примечание: порядок обхода Map зависит от реализации.
        // Для стабильного порядка слоёв используйте LinkedHashMap или mapOf(...) в желаемой последовательности.
        for ((arcWidthRad, stepRatio) in layers) {
            val w = arcWidthRad
            val d = w * stepRatio
            val n = ceil(twoPi / d).toInt().coerceAtLeast(1)
            val halfW = w / 2.0

            // фаза слоя циклически «проворачивается» по числу слоёв
            val phase = (layerIdx % L) * d / L

            for (i in 0 until n) {
                val center = (i * d + phase)
                val diff = angDiff(angleRadians - center)
                if (diff >= -halfW && diff < halfW) {
                    val idx = offset + i
                    if (idx in 0 until codeSize) out[idx] = 1
                }
            }
            offset += n
            layerIdx++
        }

        // Печать результата перед возвратом
        printCode(out, angleRadians)
        return out
    }


    /**
     * Нарисовать дуги детекторов всех слоёв на круге в PDF.
     *
     * @param outputPath путь к PDF
     * @param markAngleRadians опционально: угол, для которого подсветить активные детекторы (толще/ярче)
     * @param radius радиус круга (pt)
     */
    fun drawDetectorsPdf(
        outputPath: String,
        markAngleRadians: Double? = null,
        radius: Float = 200f
    ) {
        PdfDocument(PdfWriter(outputPath)).use { pdf ->
            val page = pdf.addNewPage(PageSize.A4)
            val canvas = PdfCanvas(page)

            // Геометрия страницы
            val cx = page.pageSize.width  / 2f
            val cy = page.pageSize.height / 2f

            // Базовый круг
            canvas.setLineWidth(1f)
                .setStrokeColor(DeviceRgb(0, 0, 0))
                .circle(cx.toDouble(), cy.toDouble(), radius.toDouble())
                .stroke()

            // Палитра для слоёв
            val colors = listOf(
                DeviceRgb(52, 120, 246),
                DeviceRgb(34, 197, 94),
                DeviceRgb(234, 179, 8),
                DeviceRgb(239, 68, 68),
                DeviceRgb(168, 85, 247),
                DeviceRgb(16, 185, 129),
                DeviceRgb(59, 130, 246),
                DeviceRgb(245, 158, 11)
            )

            // Подсветка активных детекторов (если задан markAngleRadians)
            val activeByLayer = mutableSetOf<Pair<Int, Int>>() // (layerIdx, detIdx)
            if (markAngleRadians != null) {
                var off = 0
                var k = 0
                for ((w, r) in layers) {
                    val d = w * r
                    val n = ceil(twoPi / d).toInt().coerceAtLeast(1)
                    val halfW = w / 2.0
                    val phase = (k % L) * d / L
                    for (i in 0 until n) {
                        val center = (i * d + phase) % twoPi
                        val diff = angDiff(markAngleRadians - center)
                        if (diff >= -halfW && diff < halfW) {
                            activeByLayer += k to i
                        }
                    }
                    off += n
                    k++
                }
            }

            // Рисуем дуги детекторов каждого слоя
            var layerIdx = 0
            var rOffset = 15
            for ((arcWidthRad, stepRatio) in layers) {
                val color = colors[layerIdx % colors.size]
                val w = arcWidthRad
                val d = w * stepRatio
                val n = ceil(twoPi / d).toInt().coerceAtLeast(1)
                val phase = (layerIdx % L) * d / L

                // прямоугольник окружности для arc (iText7: arc(x1,y1,x2,y2,startDeg,extentDeg))
                val x1 = (cx - (radius + rOffset)).toDouble()
                val y1 = (cy - (radius + rOffset)).toDouble()
                val x2 = (cx + (radius + rOffset)).toDouble()
                val y2 = (cy + (radius + rOffset)).toDouble()

                for (i in 0 until n) {
                    val center = (i * d + phase) % twoPi
                    val start = center - w / 2.0  // рад
                    val extent = w                // рад (всегда положительный)

                    // Нормализуем в градусы
                    val startDeg = Math.toDegrees(start)
                    val extentDeg = Math.toDegrees(extent)

                    // Если дуга пересекает 360/0 — разбиваем на две
                    canvas.setStrokeColor(color)
                    val isActive = (layerIdx to i) in activeByLayer

                    canvas.setLineWidth(if (isActive) 2.2f else 1.0f)
                    drawArcRotated(canvas,x1, y1, x2, y2, startDeg, extentDeg, color = color)
                }
                layerIdx++
                rOffset+=10
            }

            // (Необязательно) Радиальная метка угла markAngleRadians
            if (markAngleRadians != null) {
                val a = markAngleRadians
                val dx = (radius * cos(a)).toFloat()
                val dy = (radius * sin(a)).toFloat()
                canvas.setStrokeColor(DeviceRgb(0, 0, 0))
                    .setLineWidth(0.8f)
                    .moveTo(cx.toDouble(), cy.toDouble())
                    .lineTo((cx + dx).toDouble(), (cy + dy).toDouble())
                    .stroke()
            }
        }
    }

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
    fun drawArcRotated(
        canvas: PdfCanvas,
        x1: Double, y1: Double, x2: Double, y2: Double,
        startDeg: Double, extentDeg: Double,
        dR: Double = 5.0,
        segments: Int = 60,
        color: DeviceRgb = DeviceRgb(52, 120, 246)
    ) {
        val cx = (x1 + x2) / 2.0
        val cy = (y1 + y2) / 2.0
        val baseR = (x2 - x1) / 2.0

        canvas.setStrokeColor(color)

        val step = extentDeg / segments
        for (s in 0 until segments) {
            val a0 = startDeg + s * step
            val a1 = a0 + step
            val t0 = s.toDouble() / segments
            val t1 = (s + 1).toDouble() / segments

            val r0 = baseR + dR * t0
            val r1 = baseR + dR * t1

            val x0 = cx + r0 * cos(Math.toRadians(a0))
            val y0 = cy + r0 * sin(Math.toRadians(a0))
            val x1p = cx + r1 * cos(Math.toRadians(a1))
            val y1p = cy + r1 * sin(Math.toRadians(a1))

            if (s == 0) {
                canvas.moveTo(x0, y0)
            }
            canvas.lineTo(x1p, y1p)
        }
        canvas.stroke()
    }

}
