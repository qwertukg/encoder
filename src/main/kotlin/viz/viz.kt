package viz

import java.awt.*
import javax.swing.*
import java.io.File
import kotlin.math.*

class ArrowGrid(
    private val ang: List<List<String>>,
    private val cell: Int = 32
) : JPanel() {
    init {
        preferredSize = Dimension(ang.first().size * cell, ang.size * cell)
        background = Color.BLACK
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // сетка (светло-серая)
//        g2.color = Color(230, 230, 230)
//        for (r in 0..ang.size) g2.drawLine(0, r * cell, ang[0].size * cell, r * cell)
//        for (c in 0..ang[0].size) g2.drawLine(c * cell, 0, c * cell, ang.size * cell)

        val len = cell * 0.38
        val head = cell * 0.18
        val phi = Math.PI / 7

        for (r in ang.indices) for (c in ang[r].indices) {
            val angle = ang[r][c]
            if (angle.isEmpty() || angle == "n") continue // TODO
            val angleDouble = angle.toDouble()
            val cx = c * cell + cell / 2.0
            val cy = r * cell + cell / 2.0
            val a = Math.toRadians(angleDouble)        // 0° вправо, 90° вверх
            val x2 = cx + len * cos(a)
            val y2 = cy - len * sin(a)               // инверсия Y для экрана

            val angleDeg = angle.toDouble()
            g2.color = colorForAngle(angleDeg)
            g2.drawLine(cx.toInt(), cy.toInt(), x2.toInt(), y2.toInt())

            // наконечник стрелки
            val theta = atan2(cy - y2, x2 - cx)      // угол линии (с учётом экранного Y)
            for (s in intArrayOf(1, -1)) {
                val rho = theta + phi * s
                val x = x2 - head * cos(rho)
                val y = y2 + head * sin(rho)
                g2.drawLine(x2.toInt(), y2.toInt(), x.toInt(), y.toInt())
            }
        }
    }

    // Подбор цвета по углу: HSV (HSB в Java)
    private fun colorForAngle(deg: Double, s: Float = 0.9f, v: Float = 0.95f): Color {
        // нормализуем в [0, 360)
        val d = ((deg % 360.0) + 360.0) % 360.0
        val hue = (d / 360.0).toFloat()          // [0,1)
        return Color.getHSBColor(hue, s, v)
    }
}

