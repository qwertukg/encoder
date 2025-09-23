import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.geom.AffineTransform
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min


class SlidingWindowAngleEncoder(
    // arcWidthRad -> stepRatio
    val layers: Map<Double, Double> = linkedMapOf(
        (90.0      * PI / 180.0) to 0.6,
        (45.0      * PI / 180.0) to 0.6,
        (22.5      * PI / 180.0) to 0.6,
        (11.25     * PI / 180.0) to 0.6,
        (5.625     * PI / 180.0) to 0.6,
        (2.8125    * PI / 180.0) to 0.6, // with 256 bit - sens step ~<=1°
//        (1.40625   * PI / 180.0) to 0.6,
//        (0.703125  * PI / 180.0) to 0.6,
    ),
    val codeSize: Int = 256
) {
    private val twoPi = 2.0 * PI

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
        val L = layers.size - 1

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
     * @param pageSize размер страницы (по умолчанию A4)
     * @param margin отступ от границ страницы (pt)
     * @param radius радиус круга (pt)
     */
    fun drawDetectorsPdf(
        outputPath: String,
        markAngleRadians: Double? = null,
        pageSize: PageSize = PageSize.A4,
        margin: Float = 36f,
        radius: Float = 220f
    ) {
        PdfDocument(PdfWriter(outputPath)).use { pdf ->
            val page = pdf.addNewPage(pageSize)
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
                val L = max(layers.size, 1)
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
            val L = max(layers.size, 1)
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
                    var start = center - w / 2.0  // рад
                    var extent = w                // рад (всегда положительный)

                    // Нормализуем в градусы
                    var startDeg = Math.toDegrees(start)
                    var extentDeg = Math.toDegrees(extent)

                    // Если дуга пересекает 360/0 — разбиваем на две
                    val endDeg = startDeg + extentDeg
                    canvas.setStrokeColor(color)
                    val isActive = (layerIdx to i) in activeByLayer

                    canvas.setLineWidth(if (isActive) 2.2f else 1.0f)

                    if (endDeg <= 360.0 && startDeg >= 0.0) {
                        // одна дуга без разрыва
                        canvas.arc(x1, y1, x2, y2, startDeg, extentDeg).stroke()
//                        drawArcRotated(canvas,x1, y1, x2, y2, startDeg, extentDeg)
                    } else {
                        // разрыв: нарисуем две части
                        // Нормализация: сводим startDeg в [0,360)
                        val s = ((startDeg % 360.0) + 360.0) % 360.0
                        val e = extentDeg
                        val first = min(360.0 - s, e)
                        val second = max(0.0, e - first)
                        if (first > 0.0) {
                            canvas.arc(x1, y1, x2, y2, s, first).stroke()
//                            drawArcRotated(canvas,x1, y1, x2, y2, s, first)

                        }
                        if (second > 0.0) {
                            canvas.arc(x1, y1, x2, y2, 0.0, second).stroke()
//                            drawArcRotated(canvas,x1, y1, x2, y2, 0.0, second)
                        }
                    }
                }
                layerIdx++
                rOffset+=7
            }

            // (Необязательно) Радиальная метка угла markAngleRadians
            if (markAngleRadians != null) {
                val a = markAngleRadians
                val dx = (radius * kotlin.math.cos(a)).toFloat()
                val dy = (radius * kotlin.math.sin(a)).toFloat()
                canvas.setStrokeColor(DeviceRgb(0, 0, 0))
                    .setLineWidth(0.8f)
                    .moveTo(cx.toDouble(), cy.toDouble())
                    .lineTo((cx + dx).toDouble(), (cy + dy).toDouble())
                    .stroke()
            }
        }
    }

    fun drawArcRotated(
        canvas: PdfCanvas,
        x1: Double, y1: Double, x2: Double, y2: Double,
        startDeg: Double, extentDeg: Double,
        rotationDeg: Double
    ) {
        val start = startDeg + rotationDeg
        canvas.arc(x1, y1, x2, y2, start, extentDeg).stroke()
    }

}
