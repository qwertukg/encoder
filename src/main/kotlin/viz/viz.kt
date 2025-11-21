package viz

import DampLayout2D
import Proto
import java.awt.*
import javax.swing.*


private fun readAnglePosMatrix(anglesString: String): List<List<Proto?>> =
    anglesString
        .lines()
        .map { it.trimEnd() }
        .filter { it.isNotEmpty() }
        .map { line ->
            line.split(',')
                .map { cell ->
                    val s = cell.trim()
                    if (s.isEmpty() || s == "n") {
                        null
                    } else {
                        val parts = s.split(';')
                        val angle = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
                        val x   = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
                        val y   = parts.getOrNull(2)?.trim()?.toDoubleOrNull()
                        if (angle == null || x == null || y == null) {
                            null
                        } else {
                            Proto(angle, x, y)
                        }
                    }
                }
        }

val frame = JFrame("Angle-position layout")

fun showLayout(anglesString: String, layout: DampLayout2D) {
    val matrix = readAnglePosMatrix(anglesString)
    SwingUtilities.invokeLater {
        frame.apply {
            defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            contentPane = JScrollPane(DotGrid(matrix, 20, layout))
            pack()
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}

class DotGrid(
    private val proto: List<List<Proto?>>,
    private val cell: Int,
    private val layout: DampLayout2D
) : JPanel() {

    init {
        preferredSize = Dimension(proto.first().size * cell, proto.size * cell)
        background = Color.BLACK
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        if (proto.isEmpty() || proto.first().isEmpty()) return

        for (r in proto.indices) {
            for (c in proto[r].indices) {
                val cellVal = proto[r][c] ?: continue

                val angle = cellVal.angle
                val a = angle.toInt()
                val x = cellVal.x.toInt()
                val y = cellVal.y.toInt()

                val cx = c * cell
                val cy = r * cell

                g2.color = colorForAngle(angle)
                g2.fillRect(cx, cy, cell, cell)
                g2.color = Color.BLACK
                g2.font = g2.font.deriveFont(6f)
                g2.drawString("$a", cx , cy + cell/2)
                g2.drawString("$x:$y", cx , cy + cell - 3)
            }
        }
    }

    private fun colorForAngle(deg: Double): Color {
        val d = ((deg % 360.0) + 360.0) % 360.0
        val hue = (d / 360.0).toFloat()
        return Color.getHSBColor(hue, 1f, 1f)
    }
}