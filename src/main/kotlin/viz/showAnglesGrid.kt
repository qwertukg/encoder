package viz

import java.util.Locale
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Рисует список углов на квадратной решётке (row-major).
 * 0° направо, 90° вверх — как в ArrowGrid.
 *
 * @param angles список углов в градусах
 * @param cell   размер клетки (px), как в ArrowGrid
 * @param decimals сколько знаков после запятой выводить/хранить в ячейке
 * @param title  заголовок окна
 */
fun showAnglesGrid(
    angles: List<Double?>,
    cell: Int = 32,
    decimals: Int = 1,
    title: String = "Angle matrix"
) {
    require(angles.isNotEmpty()) { "angles must not be empty" }

    // квадратная матрица S×S, заполняем по строкам
    val s = ceil(sqrt(angles.size.toDouble())).toInt()
    val grid: MutableList<MutableList<String>> = MutableList(s) { MutableList(s) { "" } }
    val fmt = "%.${decimals}f"

    for (i in angles.indices) {
        val r = i / s
        val c = i % s
        grid[r][c] = String.format(Locale.US, fmt, angles[i])
    }

    SwingUtilities.invokeLater {
        frame.title = title
        frame.contentPane = JScrollPane(ArrowGrid(grid, cell))
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }
}
