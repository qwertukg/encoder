// OracleParamSearch.kt
package oracle

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Точка в "оракуле": угол (в градусах) + позиция (row, col) в клетках.
 * Никак не связана с твоим классом Proto.
 */
data class OraclePoint(
    val angleDeg: Double,
    val row: Double,
    val col: Double
)

/**
 * Минимальная разность углов по окружности [0; 360).
 */
fun angleDiffDeg(a: Double, b: Double): Double {
    val d = abs(a - b)
    return if (d > 180.0) 360.0 - d else d
}

/**
 * Евклидово расстояние между позициями в клетках.
 */
fun posDistCells(p: OraclePoint, q: OraclePoint): Double {
    val dr = p.row - q.row
    val dc = p.col - q.col
    return sqrt(dr * dr + dc * dc)
}

/**
 * Оракульная близость:
 *
 * sim = (w_angle * exp(-(d_angle / σ_angle)^2) + w_pos * exp(-(d_pos / σ_pos)^2)) / (w_angle + w_pos)
 *
 * Все параметры вещественные, строго положительные.
 */
fun oracleSimilarity(
    a: OraclePoint,
    b: OraclePoint,
    angleWeight: Double,
    posWeight: Double,
    sigmaAngleDeg: Double,
    sigmaPosCells: Double
): Double {
    val dAng = angleDiffDeg(a.angleDeg, b.angleDeg)
    val dPos = posDistCells(a, b)

    val angTerm = exp(- (dAng / sigmaAngleDeg).pow(2.0))
    val posTerm = exp(- (dPos / sigmaPosCells).pow(2.0))

    val wSum = angleWeight + posWeight
    return if (wSum <= 0.0) 0.0 else (angleWeight * angTerm + posWeight * posTerm) / wSum
}

/**
 * Результат одной комбинации параметров в грид-поиске.
 */
data class OracleParamResult(
    val angleWeight: Double,
    val posWeight: Double,
    val sigmaAngleDeg: Double,
    val sigmaPosCells: Double,
    val sim16_32: Double,
    val loss: Double
)

/**
 * Научный (хоть и простой) грид-поиск параметров для пары:
 *
 *   p16 = (угол 0°, позиция 16,16)
 *   p32 = (угол 0°, позиция 32,32)
 *
 * Цель: подобрать параметры так, чтобы sim(p16, p32) была близка к targetSim.
 *
 * Никаких раскладок, кодов и т.д. — только аналитическая формула.
 */
fun runOracleParamSearch16_32() {
    // наша тестовая пара
    val p16 = OraclePoint(angleDeg = 0.0, row = 16.0, col = 16.0)
    val p32 = OraclePoint(angleDeg = 0.0, row = 32.0, col = 32.0)

    // целевая близость (можешь потом поменять и перегнать поиск)
    val targetSim = 0.60

    // сетка параметров (тоже можно потом расширять/докручивать)
    val angleWeights = listOf(0.25, 0.5, 1.0, 2.0)
    val posWeights   = listOf(0.25, 0.5, 1.0, 2.0)

    val sigmaAnglesDeg = listOf(30.0, 60.0, 90.0, 120.0)
    val sigmaPosCells  = listOf(4.0, 8.0, 12.0, 16.0, 24.0, 32.0)

    val results = mutableListOf<OracleParamResult>()

    for (aw in angleWeights) {
        for (pw in posWeights) {
            val wSum = aw + pw
            if (wSum <= 0.0) continue

            for (sigAng in sigmaAnglesDeg) {
                for (sigPos in sigmaPosCells) {
                    if (sigAng <= 0.0 || sigPos <= 0.0) continue

                    val sim = oracleSimilarity(
                        a = p16,
                        b = p32,
                        angleWeight = aw,
                        posWeight = pw,
                        sigmaAngleDeg = sigAng,
                        sigmaPosCells = sigPos
                    )

                    val loss = (sim - targetSim).pow(2.0)

                    results += OracleParamResult(
                        angleWeight = aw,
                        posWeight = pw,
                        sigmaAngleDeg = sigAng,
                        sigmaPosCells = sigPos,
                        sim16_32 = sim,
                        loss = loss
                    )
                }
            }
        }
    }

    // сортируем по loss (чем ближе к targetSim, тем лучше)
    val sorted = results.sortedBy { it.loss }

    println("=== Oracle param search for pair (0°,16,16) vs (0°,32,32) ===")
    println("Target similarity = $targetSim")
    println("Total combinations = ${results.size}")
    println()
    println("Top 30 parameter sets:")
    println("angleW\tposW\tσ_angle\tσ_pos\t| sim16_32\tloss")

    for ((idx, r) in sorted.take(30).withIndex()) {
        println(
            "${idx + 1}\t" +
                    "${"%.2f".format(r.angleWeight)}\t" +
                    "${"%.2f".format(r.posWeight)}\t" +
                    "${"%.1f".format(r.sigmaAngleDeg)}\t" +
                    "${"%.1f".format(r.sigmaPosCells)}\t" +
                    "|\t${"%.4f".format(r.sim16_32)}\t" +
                    "${"%.6f".format(r.loss)}"
        )
    }

    val best = sorted.firstOrNull()
    if (best != null) {
        println()
        println("Best params:")
        println("  angleWeight   = ${best.angleWeight}")
        println("  posWeight     = ${best.posWeight}")
        println("  sigmaAngleDeg = ${best.sigmaAngleDeg}")
        println("  sigmaPosCells = ${best.sigmaPosCells}")
        println("  sim16_32      = ${best.sim16_32}")
        println("  loss          = ${best.loss}")
    }
}