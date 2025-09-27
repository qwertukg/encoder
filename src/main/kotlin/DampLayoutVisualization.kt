import kotlin.math.PI

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
 * Формирует визуализацию канонической раскладки по статье DAML: сначала строится список кодов,
 * затем применяется алгоритм DampLayout2D, а результат переводится в координаты с привязкой к углам.
 */
fun buildDampLayoutVisualization(
    samples: List<Pair<Double, IntArray>>,
    parameters: DampLayout2D.Parameters = DampLayout2D.Parameters()
): DampLayoutVisualization {
    require(samples.isNotEmpty()) { "Нужно предоставить хотя бы один код для раскладки" }

    val signatureToAngle = HashMap<String, Double>(samples.size)
    samples.forEach { (angleRadians, code) ->
        val angleDegrees = normalizeDegrees((angleRadians * 180.0) / PI)
        val signature = codeSignature(code)
        signatureToAngle.putIfAbsent(signature, angleDegrees)
    }

    val codes = samples.map { it.second }
    val layoutMatrix = DampLayout2D(codes, parameters).layout()
    val height = layoutMatrix.size
    val width = layoutMatrix.firstOrNull()?.size ?: 0

    val points = mutableListOf<DampLayoutVisualization.Point>()
    layoutMatrix.forEachIndexed { y, row ->
        row.forEachIndexed { x, code ->
            if (code != null) {
                val signature = codeSignature(code)
                val angle = signatureToAngle[signature]
                    ?: error("Не удалось сопоставить угол для кода с подписью $signature")
                points += DampLayoutVisualization.Point(x, y, angle)
            }
        }
    }

    return DampLayoutVisualization(width, height, points)
}

private fun codeSignature(code: IntArray): String {
    val builder = StringBuilder()
    code.forEachIndexed { index, value ->
        if (value != 0) {
            if (builder.isNotEmpty()) builder.append(',')
            builder.append(index)
        }
    }
    return builder.toString()
}

private fun normalizeDegrees(angle: Double): Double {
    var result = angle % 360.0
    if (result < 0.0) result += 360.0
    return result
}
