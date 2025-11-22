import SlidingWindowAngleEncoder.LinearLayer
import SlidingWindowAngleEncoder.Layer
import kotlin.math.sqrt
import kotlin.math.roundToInt
import kotlin.time.measureTime

fun main() {
    println("os.arch=" + System.getProperty("os.arch"))

    val a0 = 0.0
    val aN = 360.0
    val x0 = 0.0
    val xN = 32.0
    val y0 = 0.0
    val yN = 32.0

    val encoder = SlidingWindowAngleEncoder(
        // ---- ANGLE конфигурация ----
        listOf(
            Layer(120.0),
            Layer(60.0),
            Layer(30.0),
            Layer(15.0),
            Layer(5.0),
        ),
        // ---- X конфигурации ----
        listOf(
            LinearLayer(baseWidthUnits = 0.5,  overlapFraction = 0.4, domainMin = x0, domainMax = xN),
            LinearLayer(baseWidthUnits = 1.0,  overlapFraction = 0.4, domainMin = x0, domainMax = xN),
            LinearLayer(baseWidthUnits = 2.0,  overlapFraction = 0.4, domainMin = x0, domainMax = xN),
            LinearLayer(baseWidthUnits = 4.0,  overlapFraction = 0.4, domainMin = x0, domainMax = xN),
            LinearLayer(baseWidthUnits = 8.0,  overlapFraction = 0.4, domainMin = x0, domainMax = xN),
            LinearLayer(baseWidthUnits = 16.0, overlapFraction = 0.4, domainMin = x0, domainMax = xN),
        ),
        // ---- Y конфигурации ----
        listOf(
            LinearLayer(baseWidthUnits = 0.5,  overlapFraction = 0.4, domainMin = y0, domainMax = yN),
            LinearLayer(baseWidthUnits = 1.0,  overlapFraction = 0.4, domainMin = y0, domainMax = yN),
            LinearLayer(baseWidthUnits = 2.0,  overlapFraction = 0.4, domainMin = y0, domainMax = yN),
            LinearLayer(baseWidthUnits = 4.0,  overlapFraction = 0.4, domainMin = y0, domainMax = yN),
            LinearLayer(baseWidthUnits = 8.0,  overlapFraction = 0.4, domainMin = y0, domainMax = yN),
            LinearLayer(baseWidthUnits = 16.0, overlapFraction = 0.4, domainMin = y0, domainMax = yN),
        ),
        useRandomBitMapping = true,
    )

    // --- генерим прототипы + коды ---
    val codes = mutableListOf<Pair<Proto, IntArray>>()
    var a = a0
    while (a < aN) {
        a += 1.0
        var x = x0
        while (x < xN) {
            x += 16.0
            var y = y0
            while (y < yN) {
                y += 16.0
                val angleRad = Math.toRadians(a)
                val code = encoder.encode(angleRad, x, y)

                val proto = Proto(angle = a, x = x, y = y)
                codes += proto to code
            }
        }
    }

    val emptyCodes: List<Pair<Proto?, IntArray>> =
        (0..1000).map { null to IntArray(encoder.codeSizeInBits) }

    val allCodes = codes + emptyCodes

    // === ВАЖНО: параметры оракула под ГИПЕРПИНВИЛ ===
    // угол доминирует, позиция – мягкий модификатор
    val layout = DampLayout2D(
        allCodes,
        maxAngleDeg = 180.0,   // как было, период по углу
        posRangeFrac = 1.0,    // позиции нормализуются мягко (не разлетаются в 4 острова)
        sigmaAngleDeg = 28.0,  // умеренно широкий по углу
        sigmaPos = 24.0,       // ШИРОКАЯ гауссиана по позиции → 4 позиции почти слиты
        angleWeight = 1.0,     // угол – главный
        posWeight = 0.25       // позиция – слабый, но ненулевой вклад
    )

    println("Total non-empty protos = ${codes.size}, total codes (with empties) = ${allCodes.size}")
    println("Grid size = ${layout.gridSize} x ${layout.gridSize}")

    // --- тестовые пары для анализа ---
    val p1a = Proto(289.0, 16.0, 32.0)
    val p1b = Proto(107.0, 16.0, 16.0)   // far-angle / similar-pos

    val p2a = Proto(96.0, 16.0, 16.0)
    val p2b = Proto(102.0, 16.0, 16.0)   // close-angle / same-pos

    val p3a = Proto(289.0, 16.0, 32.0)
    val p3b = Proto(289.0, 32.0, 32.0)   // same-angle / diff-pos

    fun logPair(label: String, aProto: Proto, bProto: Proto) {
        val j = layout.jaccardSimilarity(aProto, bProto)
        val an = layout.analyticSimilarity(aProto, bProto)
        println("$label: $aProto vs $bProto -> jaccard=%.4f, analytic=%.4f".format(j, an))
    }

    println()
    println("=== Analytic vs Jaccard similarities (before layout) ===")
    logPair("1) far-angle / similar-pos", p1a, p1b)
    logPair("2) close-angle / same-pos",  p2a, p2b)
    logPair("3) same-angle / diff-pos",   p3a, p3b)

    println()
    println("=== Running layout ===")

    val cpuTime = measureTime {
        val outCPU = layout.layoutLongRange(
            farRadius = layout.gridSize / 2, // даём глобальную перестановку по всей решётке
            epochs = 100,
            minSim = 0.0,
            lambdaStart = 0.30,
            lambdaEnd = 0.90,
            eta = 0.85,          // оракул остаётся сильным, но не 100%
            maxBatchFrac = 0.30,
            log = true
        )

        // --- координаты прототипов после раскладки ---
        val coordByProto: Map<Proto, Pair<Int, Int>> =
            outCPU
                .mapNotNull { (p, y, x) -> p?.let { it to (y to x) } }
                .toMap()

        fun gridInfo(label: String, aProto: Proto, bProto: Proto) {
            val aCoord = coordByProto[aProto]
            val bCoord = coordByProto[bProto]
            if (aCoord == null || bCoord == null) {
                println("$label: $aProto vs $bProto -> one of protos not found in coord map")
                return
            }
            val (ya, xa) = aCoord
            val (yb, xb) = bCoord
            val dy = ya - yb
            val dx = xa - xb
            val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())

            println(
                "$label:\n" +
                        "  $aProto -> (y=$ya, x=$xa)\n" +
                        "  $bProto -> (y=$yb, x=$xb)\n" +
                        "  dy=$dy, dx=$dx, gridDist=%.3f".format(dist)
            )
        }

        println()
        println("=== Grid distances after layout ===")
        gridInfo("1) far-angle / similar-pos", p1a, p1b)
        gridInfo("2) close-angle / same-pos",  p2a, p2b)
        gridInfo("3) same-angle / diff-pos",   p3a, p3b)

        // --- центроиды 4 позиций (для гиперпинвила) ---
        fun centerOfPos(label: String, x: Double, y: Double): Pair<Double, Double>? {
            val pts = coordByProto
                .filterKeys { it.x == x && it.y == y }
                .values
            if (pts.isEmpty()) {
                println("$label: no points found")
                return null
            }
            val meanY = pts.map { it.first }.average()
            val meanX = pts.map { it.second }.average()
            println("$label center: mean(y)=%.2f, mean(x)=%.2f, count=%d".format(meanY, meanX, pts.size))
            return meanY to meanX
        }

        println()
        println("=== Position cluster centers (mean grid coords) ===")
        val c11 = centerOfPos("pos (16,16)", 16.0, 16.0)
        val c12 = centerOfPos("pos (16,32)", 16.0, 32.0)
        val c21 = centerOfPos("pos (32,16)", 32.0, 16.0)
        val c22 = centerOfPos("pos (32,32)", 32.0, 32.0)

        fun distCenters(name: String, a: Pair<Double, Double>?, b: Pair<Double, Double>?) {
            if (a == null || b == null) {
                println("$name: one of centers is null")
                return
            }
            val dy = a.first - b.first
            val dx = a.second - b.second
            val d = kotlin.math.sqrt(dy * dy + dx * dx)
            println("$name distance: dy=%.2f, dx=%.2f, dist=%.3f".format(dy, dx, d))
        }

        println()
        println("=== Distances between position centers ===")
        distCenters("(16,16) <-> (16,32)", c11, c12)
        distCenters("(16,16) <-> (32,16)", c11, c21)
        distCenters("(16,16) <-> (32,32)", c11, c22)
        distCenters("(16,32) <-> (32,16)", c12, c21)
        distCenters("(16,32) <-> (32,32)", c12, c22)
        distCenters("(32,16) <-> (32,32)", c21, c22)
    }

    println()
    println("CPU Layout finished! Total time: $cpuTime")
}