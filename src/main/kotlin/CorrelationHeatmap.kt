import org.jfree.chart.ChartFactory
import org.jfree.chart.JFreeChart
import org.jfree.chart.axis.NumberAxis
import org.jfree.chart.ChartPanel
import org.jfree.chart.plot.PlotOrientation
import org.jfree.chart.plot.XYPlot
import org.jfree.chart.renderer.GrayPaintScale
import org.jfree.chart.renderer.LookupPaintScale
import org.jfree.chart.renderer.xy.XYBlockRenderer
import org.jfree.chart.ui.RectangleInsets
import org.jfree.data.xy.DefaultXYZDataset
import org.jfree.data.xy.XYSeries
import org.jfree.data.xy.XYSeriesCollection
import org.jfree.data.xy.XYZDataset
import java.awt.Color
import java.awt.Dimension
import javax.swing.JFrame
import kotlin.math.min
import kotlin.math.roundToInt

fun cosBits(a: IntArray, b: IntArray): Double {
    val m = minOf(a.size, b.size)
    var dot = 0
    var cntA = 0
    var cntB = 0
    var k = 0
    while (k < m) {
        val ai = a[k]
        val bj = b[k]
        if (ai == 1 && bj == 1) dot++
        if (ai == 1) cntA++
        if (bj == 1) cntB++
        k++
    }
    if (cntA == 0 || cntB == 0) return 0.0
    return dot / (kotlin.math.sqrt(cntA.toDouble()) * kotlin.math.sqrt(cntB.toDouble()))
}

private fun jaccardBits(a: IntArray, b: IntArray): Double {
    val m = minOf(a.size, b.size)
    var inter = 0
    var uni = 0
    var k = 0
    while (k < m) {
        val ai = a[k] == 1
        val bj = b[k] == 1
        if (ai || bj) {
            uni++
            if (ai && bj) inter++
        }
        k++
    }
    return if (uni == 0) 0.0 else inter.toDouble() / uni.toDouble()
}

fun buildCodeCorrelationMatrix(
    angleCodes: List<Pair<Double, IntArray>>
): Array<DoubleArray> {
    val n = angleCodes.size
    val sim = Array(n) { DoubleArray(n) }

    for (i in 0 until n) {
        sim[i][i] = 1.0
        val a = angleCodes[i].second
        for (j in i + 1 until n) {
            val b = angleCodes[j].second
            val v = jaccardBits(a, b)
//            val v = cosBits(a, b)
            sim[i][j] = v
            sim[j][i] = v
        }
    }

    return sim
}

fun createHeatmapDataset(matrix: Array<DoubleArray>): XYZDataset {
    val n = matrix.size
    val total = n * n
    val x = DoubleArray(total)
    val y = DoubleArray(total)
    val z = DoubleArray(total)
    var idx = 0
    for (row in 0 until n) {
        for (col in 0 until n) {
            x[idx] = col.toDouble()
            y[idx] = row.toDouble()
            z[idx] = matrix[row][col]
            idx++
        }
    }
    val ds = DefaultXYZDataset()
    ds.addSeries("corr", arrayOf(x, y, z))
    return ds
}

fun createHeatmapChart(dataset: XYZDataset, title: String): JFreeChart {
    val xAxis = NumberAxis("Angle")
    val yAxis = NumberAxis("Angle")
    xAxis.lowerMargin = 0.0
    xAxis.upperMargin = 0.0
    yAxis.lowerMargin = 0.0
    yAxis.upperMargin = 0.0

    val renderer = XYBlockRenderer().apply {
        blockWidth = 1.0
        blockHeight = 1.0

        paintScale = GrayPaintScale(0.0, 1.0)
    }

    val plot = XYPlot(dataset, xAxis, yAxis, renderer).apply {
        axisOffset = RectangleInsets(0.0, 0.0, 0.0, 0.0)
    }

    return JFreeChart(title, JFreeChart.DEFAULT_TITLE_FONT, plot, false)
}

class HeatmapFrame(chart: JFreeChart) : JFrame("Correlation Heatmap") {
    init {
        contentPane = ChartPanel(chart).apply {
            preferredSize = Dimension(1000, 1000)
        }
        defaultCloseOperation = EXIT_ON_CLOSE
        pack()
        setLocationRelativeTo(null)
        isVisible = true

    }
}

/**
 * corr[i][j] — сходство между кодами для углов i° и j°.
 * Строим кривую "угол (j°) -> сходство" для фиксированного угла targetAngleDegrees.
 */
fun showSimilarityCurve(
    corr: Array<DoubleArray>,
    targetAngleDegrees: Double
) {
    require(corr.isNotEmpty()) { "corr is empty" }
    val n = corr.size
    require(corr.all { it.size == n }) { "corr must be square" }

    // индекс угла в дискретизации 1° (0..n-1)
    var idx = targetAngleDegrees.roundToInt() % n
    if (idx < 0) idx += n

    val series = XYSeries("similarity@${targetAngleDegrees}°")
    for (j in 0 until n) {
        val angleDeg = j.toDouble()  // j градусов
        val sim = corr[idx][j]
        series.add(angleDeg, sim)
    }

    val dataset = XYSeriesCollection(series)

    val chart: JFreeChart = ChartFactory.createXYLineChart(
        "Similarity vs angle (θ₀ = $targetAngleDegrees°)",
        "Angle (deg)",
        "Similarity",
        dataset,
        PlotOrientation.VERTICAL,
        false,  // legend
        true,   // tooltips
        false   // urls
    )

    JFrame("Similarity curve").apply {
        contentPane = ChartPanel(chart).apply {
            preferredSize = Dimension(800, 600)
        }
        pack()
        setLocationRelativeTo(null)
        isVisible = true
        defaultCloseOperation = JFrame.EXIT_ON_CLOSE
    }
}

// Вызов из твоего кода после получения angleCodes
fun showAngleCodesCorrelationHeatmap(angleCodes: List<Pair<Double, IntArray>>): Array<DoubleArray> {
    val corr = buildCodeCorrelationMatrix(angleCodes)
    val dataset = createHeatmapDataset(corr)
    val chart = createHeatmapChart(dataset, "Angle code correlation matrix heatmap")
    HeatmapFrame(chart)
    return corr
}