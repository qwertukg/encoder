package viz

import java.awt.*
import javax.swing.*
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import java.util.Locale
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.roundToInt

// --- разбор входных строк в матрицу строк (совместим с твоим форматом) ---
private fun readHeightsString(heightsString: String): List<List<String>> =
    heightsString.split("\n").filter { it.isNotBlank() }
        .map { it.split(',').map { s -> s.trim() } }

/**
 * Изометрическая визуализация раскладки: значения h \in [-PI, PI] интерпретируются как высота.
 * Рисует «бруски» с верхней гранью (top) и двумя боковыми (left/right).
 */
class IsoGridPanel(
    private val H: List<List<String>>,
    private val tileW: Int = 48,   // ширина «ромба» (горизонталь в изометрии)
    private val tileH: Int = 24,   // высота «ромба» (вертикаль в изометрии)
    private val maxZ: Int = 40,    // пикселей на максимальную высоту (|h|=PI)
    private val gridGap: Int = 0   // отступ между плитками (обычно 0)
) : JPanel() {

    private val drawWalls = false
    private val rows = H.size
    private val cols = H.firstOrNull()?.size ?: 0

    init {
        // Рассчитываем примерный размер панели: центруем сетку по ширине
        val w = (cols + rows) * (tileW / 2) + 2 * maxZ + 20
        val h = (cols + rows) * (tileH / 2) + maxZ * 2 + 40
        preferredSize = Dimension(w, h)
        background = Color(245, 245, 245)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Сместим сцену так, чтобы сетка была примерно по центру
        val originX = width / 2
        val originY = tileH // небольшой верхний отступ

        // Рисуем в порядке от дальнего к ближнему, чтобы не было артефактов перекрытия:
        // (r, c) сортируем по r+c (малые — дальше, большие — ближе)
        val cells = ArrayList<Triple<Int, Int, Double>>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val s = H[r][c]
                if (s.isEmpty() || s == "n") continue
                val h = s.toDoubleOrNull() ?: continue
                cells += Triple(r, c, clampPi(h))
            }
        }
        cells.sortBy { (r, c, _) -> r + c }

        // Лёгкая сетка-«тень» на полу (необязательно)
        g2.color = Color(225, 225, 225)
        for (r in 0..rows) {
            val p1 = isoToScreen(0.0, r.toDouble(), 0.0, originX, originY)
            val p2 = isoToScreen(cols.toDouble(), r.toDouble(), 0.0, originX, originY)
            g2.drawLine(p1.x, p1.y, p2.x, p2.y)
        }
        for (c in 0..cols) {
            val p1 = isoToScreen(c.toDouble(), 0.0, 0.0, originX, originY)
            val p2 = isoToScreen(c.toDouble(), rows.toDouble(), 0.0, originX, originY)
            g2.drawLine(p1.x, p1.y, p2.x, p2.y)
        }

        // Основной проход: рисуем «бруски»
        for ((r, c, h) in cells) {
            drawIsoBlock(g2, r, c, h, originX, originY)
        }
    }

    private fun drawIsoBlock(g2: Graphics2D, r: Int, c: Int, h: Double, ox: Int, oy: Int) {
        val z = (h / Math.PI * maxZ).toInt()

        val base = isoToScreen(c.toDouble(), r.toDouble(), 0.0, ox, oy)

        val top = Point(base.x, base.y - z)
        val pN = Point(top.x, top.y - tileH / 2)
        val pS = Point(top.x, top.y + tileH / 2)
        val pW = Point(top.x - tileW / 2, top.y)
        val pE = Point(top.x + tileW / 2, top.y)

        val baseCol = heightToColor(h)
        val edgeCol = Color(30, 30, 30, 120)

        // --- ВЕРХНЯЯ «ГОРИЗОНТАЛЬНАЯ» ГРАНЬ ---
        g2.color = baseCol
        g2.fillPolygon(
            intArrayOf(pN.x, pE.x, pS.x, pW.x),
            intArrayOf(pN.y, pE.y, pS.y, pW.y),
            4
        )

        // Контур верхней грани — оставляем для читаемости
        g2.color = edgeCol
        g2.drawPolygon(
            intArrayOf(pN.x, pE.x, pS.x, pW.x),
            intArrayOf(pN.y, pE.y, pS.y, pW.y),
            4
        )

    }


    /** Изометрическое преобразование (стандартный ромбический тайлинг). */
    private fun isoToScreen(x: Double, y: Double, z: Double, ox: Int, oy: Int): Point {
        val sx = ((x - y) * (tileW / 2.0)).toInt() + ox
        val sy = ((x + y) * (tileH / 2.0) + z).toInt() + oy
        return Point(sx, sy)
    }

    /** Клипуем высоту в [-PI, PI] на всякий случай. */
    private fun clampPi(v: Double): Double = max(-PI, min(PI, v))

    /** Преобразование высоты в цвет: синяя низина → белая середина → красная вершина. */
    private fun heightToColor(h: Double): Color {
        val t = ((h / PI) + 1.0) / 2.0 // [-PI,PI] -> [0,1]
        // градиент: blue(0,80,255) -> white(245,245,245) -> red(255,60,60)
        val (r1, g1, b1) = intArrayOf(0, 80, 255)
        val (rM, gM, bM) = intArrayOf(245, 245, 245)
        val (r2, g2, b2) = intArrayOf(255, 60, 60)
        return if (t <= 0.5) {
            val k = (t / 0.5).toFloat()
            lerpColor(Color(r1, g1, b1), Color(rM, gM, bM), k)
        } else {
            val k = ((t - 0.5) / 0.5).toFloat()
            lerpColor(Color(rM, gM, bM), Color(r2, g2, b2), k)
        }
    }

    private fun lerpColor(a: Color, b: Color, t: Float): Color {
        val u = 1f - t
        val r = (a.red * u + b.red * t).toInt().coerceIn(0, 255)
        val g = (a.green * u + b.green * t).toInt().coerceIn(0, 255)
        val bl = (a.blue * u + b.blue * t).toInt().coerceIn(0, 255)
        return Color(r, g, bl)
    }

    private fun shade(c: Color, k: Float): Color {
        val r = (c.red * k).toInt().coerceIn(0, 255)
        val g = (c.green * k).toInt().coerceIn(0, 255)
        val b = (c.blue * k).toInt().coerceIn(0, 255)
        return Color(r, g, b)
    }
}

/** Публичный метод: показать изометрию для матрицы высот (строки через \n, элементы через ','). */
val isoFrame = JFrame("Isometric layout")

fun showIsometricLayout(
    heightsString: String,
    tileW: Int = 48,
    tileH: Int = 24,
    maxZ: Int = 40
) {
    val H = readHeightsString(heightsString)
    SwingUtilities.invokeLater {
        isoFrame.apply {
            defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            contentPane = JScrollPane(IsoGridPanel(H, tileW, tileH, maxZ))
            pack(); setLocationRelativeTo(null); isVisible = true
        }
    }
}



fun showAnglesGridIso(
    angles: List<Double?>,
    cell: Int = 32,
    decimals: Int = 1,
    title: String = "Angle matrix"
) {
    val frame = JFrame("Angle matrix ISO")
    require(angles.isNotEmpty()) { "angles must not be empty" }

    // Квадратная матрица S×S, заполняем по строкам
    val s = ceil(sqrt(angles.size.toDouble())).toInt()
    val gridDeg: MutableList<MutableList<String>> = MutableList(s) { MutableList(s) { "" } } // для ArrowGrid (градусы)
    val gridRad: MutableList<MutableList<String>> = MutableList(s) { MutableList(s) { "" } } // для Iso (радианы)

    val fmt = "%.${decimals}f"
    for (i in angles.indices) {
        val r = i / s
        val c = i % s
        val aDeg = angles[i]
        if (aDeg != null) {
            // Стрелочная сетка — как раньше (в градусах)
            gridDeg[r][c] = String.format(Locale.US, fmt, aDeg)
            // Изометрия — в радианах ([-π, π])
            var rad = Math.toRadians(aDeg)
            // Нормализуем к [-π, π]
            rad = ((rad + PI) % (2 * PI)).let { if (it < 0) it + 2 * PI else it } - PI
            gridRad[r][c] = String.format(Locale.US, fmt, rad)
        } else {
            gridDeg[r][c] = ""
            gridRad[r][c] = ""
        }
    }

    // Режим визуализации: "iso" (по умолчанию) или "arrow"
    val mode = System.getProperty("viz.mode", "iso").lowercase(Locale.US)

    SwingUtilities.invokeLater {
        frame.title = title
        frame.contentPane = when (mode) {
            "arrow" -> JScrollPane(ArrowGrid(gridDeg, cell))
            else -> {
                // Подберём габариты изометрии из "cell":
                // ширина ромба ~ 1.5*cell, высота ромба ~ 0.75*cell, макс. высота столбика ~ 1.25*cell
                val tileW = (cell * 1.5).roundToInt().coerceAtLeast(16)
                val tileH = (cell * 0.75).roundToInt().coerceAtLeast(8)
                val maxZ  = (cell * 1.25).roundToInt().coerceAtLeast(12)
                JScrollPane(IsoGridPanel(gridRad, tileW = tileW, tileH = tileH, maxZ = maxZ))
            }
        }
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }
}

