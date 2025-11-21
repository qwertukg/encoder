package viz

import Proto
import java.awt.*
import javax.swing.*
import kotlin.math.sqrt


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

fun showLayout(anglesString: String) {
    val matrix = readAnglePosMatrix(anglesString)
    SwingUtilities.invokeLater {
        frame.apply {
            defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            contentPane = JScrollPane(DotGrid(matrix, 5))
            pack()
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}

class DotGrid(
    private val ang: List<List<Proto?>>,
    private val cell: Int
) : JPanel() {

    init {
        preferredSize = Dimension(ang.first().size * cell, ang.size * cell)
        background = Color.BLACK
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        if (ang.isEmpty() || ang.first().isEmpty()) return

        val maxRow = (ang.size - 1).coerceAtLeast(1)
        val maxCol = (ang.first().size - 1).coerceAtLeast(1)

        for (r in ang.indices) {
            for (c in ang[r].indices) {
                val cellVal = ang[r][c] ?: continue

                val angleDeg = cellVal.angle

                val ny = cellVal.x.toFloat() / maxRow
                val nx = cellVal.y.toFloat() / maxCol

                val s = (0.5f + 0.5f * nx).coerceIn(0f, 1f)
                val v = (0.5f + 0.5f * (1f - ny)).coerceIn(0f, 1f)

                val cx = c * cell
                val cy = r * cell

                g2.color = colorForAngle(angleDeg, s, v)
                g2.fillRect(cx, cy, cell, cell)
            }
        }
    }

    private fun colorForAngle(deg: Double, s: Float = 0.9f, v: Float = 0.95f): Color {
        val d = ((deg % 360.0) + 360.0) % 360.0
        val hue = (d / 360.0).toFloat()
        return Color.getHSBColor(hue, 1f, 1f)
    }
}