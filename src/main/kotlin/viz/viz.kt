package viz

import java.awt.*
import javax.swing.*
import java.io.File
import kotlin.math.*

private fun readAnglesCsv(path: String): List<List<Double>> =
    File(path).readLines().filter { it.isNotBlank() }
        .map { it.split(',').map { s -> s.trim().toDouble() } }

private class ArrowGrid(
    private val ang: List<List<Double>>,
    private val cell: Int = 32
) : JPanel() {
    init {
        preferredSize = Dimension(ang.first().size * cell, ang.size * cell)
        background = Color.WHITE
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // сетка (светло-серая)
        g2.color = Color(230, 230, 230)
        for (r in 0..ang.size) g2.drawLine(0, r * cell, ang[0].size * cell, r * cell)
        for (c in 0..ang[0].size) g2.drawLine(c * cell, 0, c * cell, ang.size * cell)

        val len = cell * 0.38
        val head = cell * 0.18
        val phi = Math.PI / 7

        for (r in ang.indices) for (c in ang[r].indices) {
            val cx = c * cell + cell / 2.0
            val cy = r * cell + cell / 2.0
            val a = Math.toRadians(ang[r][c])        // 0° вправо, 90° вверх
            val x2 = cx + len * cos(a)
            val y2 = cy - len * sin(a)               // инверсия Y для экрана

            g2.color = Color.BLACK
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
}

fun main(args: Array<String>) {
    val angles = readAnglesCsv("src/main/kotlin/viz/data.csv")
    SwingUtilities.invokeLater {
        JFrame("Angle matrix").apply {
            defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            contentPane = JScrollPane(ArrowGrid(angles))
            pack(); setLocationRelativeTo(null); isVisible = true
        }
    }
}