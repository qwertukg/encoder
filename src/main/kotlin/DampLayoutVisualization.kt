import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * DampLayoutVisualization описывает каноническую 2D-раскладку кодов согласно разд. 5.5 DAML.
 */
data class DampLayoutVisualization(
    val width: Int,
    val height: Int,
    val points: List<Point>,
) {
    data class Point(val x: Int, val y: Int, val angleDegrees: Double)
}

/**
 * Формирует визуализацию канонической раскладки по статье DAML: набор углов сортируется,
 * после чего каждый угол сопоставляется с ближайшей свободной ячейкой сетки по полярному углу
 * относительно центра пинвила (см. описание канонической 2D-раскладки в разделе 5.5 DAML).
 */
fun buildDampLayoutVisualization(
    samples: List<Pair<Double, IntArray>>,
    parameters: DampLayout2D.Parameters = DampLayout2D.Parameters()
): DampLayoutVisualization {
    require(samples.isNotEmpty()) { "Нужно предоставить хотя бы один код для раскладки" }

    val angles = samples.map { (angleRadians, _) ->
        normalizeDegrees((angleRadians * 180.0) / PI)
    }.sorted()
    val side = computePinwheelSide(angles.size, parameters.marginFraction)
    val points = assignAnglesToPinwheel(angles, side, side)
    return DampLayoutVisualization(side, side, points)
}

private fun normalizeDegrees(angle: Double): Double {
    var result = angle % 360.0
    if (result < 0.0) result += 360.0
    return result
}

private fun assignAnglesToPinwheel(
    angles: List<Double>,
    width: Int,
    height: Int
): List<DampLayoutVisualization.Point> {
    data class Cell(val x: Int, val y: Int, val angle: Double, val radius: Double)

    val centerX = (width - 1) / 2.0
    val centerY = (height - 1) / 2.0
    val available = ArrayList<Cell>(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val dx = x - centerX
            val dy = centerY - y
            val radius = sqrt(dx * dx + dy * dy)
            val polar = if (radius == 0.0) 0.0 else normalizeDegrees(Math.toDegrees(atan2(dy, dx)))
            available += Cell(x, y, polar, radius)
        }
    }

    val remaining = available.toMutableList()
    val result = ArrayList<DampLayoutVisualization.Point>(angles.size)
    for (angle in angles) {
        var bestIndex = -1
        var bestDiff = Double.POSITIVE_INFINITY
        var bestRadius = Double.POSITIVE_INFINITY
        var bestAngle = Double.POSITIVE_INFINITY
        for (index in remaining.indices) {
            val cell = remaining[index]
            val diff = angularDistance(cell.angle, angle)
            if (diff < bestDiff - 1e-9 ||
                (kotlin.math.abs(diff - bestDiff) <= 1e-9 && cell.radius < bestRadius - 1e-9) ||
                (kotlin.math.abs(diff - bestDiff) <= 1e-9 && kotlin.math.abs(cell.radius - bestRadius) <= 1e-9 && cell.angle < bestAngle - 1e-9)
            ) {
                bestIndex = index
                bestDiff = diff
                bestRadius = cell.radius
                bestAngle = cell.angle
            }
        }
        if (bestIndex == -1) {
            throw IllegalStateException("Недостаточно ячеек для размещения всех углов пинвила")
        }
        val cell = remaining.removeAt(bestIndex)
        result += DampLayoutVisualization.Point(cell.x, cell.y, angle)
    }
    return result
}

private fun computePinwheelSide(pointCount: Int, marginFraction: Double): Int {
    val safetyMargin = if (marginFraction.isFinite() && marginFraction > 0.0) marginFraction else 0.0
    val totalSlots = ceil(pointCount * (1.0 + safetyMargin)).toInt().coerceAtLeast(pointCount)
    val side = ceil(sqrt(totalSlots.toDouble())).toInt().coerceAtLeast(1)
    return if (side % 2 == 0) side + 1 else side
}

private fun angularDistance(first: Double, second: Double): Double {
    val diff = kotlin.math.abs(normalizeDegrees(first - second))
    return kotlin.math.min(diff, 360.0 - diff)
}
