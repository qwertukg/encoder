import viz.showLayout
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.measureTime
import kotlinx.coroutines.*

// Тюнинговые константы
private const val MAX_CAND_PER_FIRST = 16       // ограничение числа кандидатов per firstIndex
private const val MAX_R_PER_DELTA     = 1000    // ограничение числа r в energyDelta

class DampLayout2D(
    private val angleCodes: List<Pair<Double?, IntArray>>,
    randomizeStart: Boolean = true,
    seed: Int = 42,
) {
    private val rng = Random(seed)
    private val n = angleCodes.size
    val gridSize: Int = ceil(sqrt(n.toDouble())).toInt()

    // Решётка хранит индексы кодов или -1 (если ячейка пустая)
    private val grid: IntArray = IntArray(gridSize * gridSize) { -1 }

    // Координаты индексов решётки
    private val ys: IntArray = IntArray(grid.size) { it / gridSize }
    private val xs: IntArray = IntArray(grid.size) { it % gridSize }

    // длина кодового слова в битах
    private val codeBitLength: Int = angleCodes.maxOfOrNull { it.second.size } ?: 0
    private val wordsPerCode: Int =
        if (codeBitLength == 0) 0 else (codeBitLength + 63) / 64

    // bitset-представление кодов: один LongArray на код
    private val bitCodes: Array<LongArray> = Array(n) { LongArray(wordsPerCode) }

    // Плотная матрица сходств Жаккара (верхний треугольник): NaN -> ещё не считали
    private val simMatrixSize: Long = (n.toLong() * (n + 1L) / 2L).also {
        require(it <= Int.MAX_VALUE) { "Similarity matrix too large for in-memory storage (n=$n)" }
    }
    private val simMatrix: FloatArray = FloatArray(simMatrixSize.toInt()) { Float.NaN }.also { matrix ->
        for (i in 0 until n) {
            matrix[simIndexUpperTri(i, i)] = 1.0f
        }
    }

    /**
     * Кеш смещений соседей по радиусу:
     * radius -> IntArray [dy0, dx0, dy1, dx1, ...].
     * Это компактно: O(radius^2) на радиус, вместо O(gridSize^2 * radius^2).
     */
    private val neighborOffsetsCache: MutableMap<Int, IntArray> = mutableMapOf()

    // Предварительно просчитанные кандидаты (radius -> Array[candidate indices per cell])
    private val candidateCache: MutableMap<Int, Array<IntArray>> = mutableMapOf()

    private data class SwapProposal(val a: Int, val b: Int, val delta: Double)

    init {
        // bitset-представление
        if (wordsPerCode > 0) {
            for (idx in 0 until n) {
                val src = angleCodes[idx].second
                val dst = bitCodes[idx]
                var k = 0
                while (k < src.size) {
                    if (src[k] == 1) {
                        val wordIndex = k / 64
                        val bitIndex  = k % 64
                        dst[wordIndex] = dst[wordIndex] or (1L shl bitIndex)
                    }
                    k++
                }
            }
        }

        // Инициализация решётки кодами
        val codeOrder = (0 until n).toMutableList()
        if (randomizeStart) codeOrder.shuffle(rng)
        codeOrder.forEachIndexed { idx, codeIndex ->
            grid[idx] = codeIndex
        }
    }

    // ======================= ПУБЛИЧНЫЕ API =======================

    fun layoutLongRange(
        farRadius: Int,
        epochs: Int,
        minSim: Double = 0.0,
        lambdaStart: Double = 0.45,
        lambdaEnd: Double = 0.70,
        eta: Double = 10.0,
        maxBatchFrac: Double = 0.5,
        log: Boolean = true,
        // если хочется ускорить: можно сразу задавать localEnergyRadius = farRadius
        forceLocalEnergyRadius: Int? = null,
    ): List<Triple<Double?, Int, Int>> {
        if (n == 0) return emptyList()

        ensureNeighbors(farRadius)

        if (log) {
            val csv = logGridState(epoch = -1, tag = "start")
            showLayout(csv)
        }

        repeat(epochs.coerceAtLeast(0)) { e ->
            val lam = lerp(
                lambdaStart,
                lambdaEnd,
                if (epochs <= 1) 1.0 else e.toDouble() / (epochs - 1).coerceAtLeast(1)
            )

            val dt: Duration = measureTime {
                runBlocking {
                    doOneEpoch(
                        searchRadius = farRadius,
                        lambda = lam,
                        eta = eta,
                        minSim = minSim,
                        maxBatchFrac = maxBatchFrac,
                        localEnergyRadius = forceLocalEnergyRadius, // null = глобально, иначе — локально
                    )
                }
            }

            if (log) {
                println("long-range epoch=${e + 1}  lambda=%.3f  duration=%s".format(lam, dt))
                val csv = logGridState(epoch = e, tag = "long")
                showLayout(csv)
            }
        }
        return buildCoordinateMap()
    }

    // ======================= ОСНОВНАЯ ЭПОХА (ПАРАЛЛЕЛЬНАЯ) =======================

    private suspend fun doOneEpoch(
        searchRadius: Int,
        lambda: Double,
        eta: Double,
        minSim: Double,
        maxBatchFrac: Double,
        localEnergyRadius: Int?,
    ) = coroutineScope {
        val occupied = (0 until n).toMutableList()
        occupied.shuffle(rng)

        if (occupied.isEmpty()) return@coroutineScope

        val maxSwaps = (occupied.size * maxBatchFrac).toInt().coerceAtLeast(1)

        val workers = minOf(
            Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            occupied.size
        )
        val chunkSize = (occupied.size + workers - 1) / workers

        val deferred = (0 until workers).map { w ->
            val from = w * chunkSize
            if (from >= occupied.size) {
                async(Dispatchers.Default) { emptyList<SwapProposal>() }
            } else {
                val to = minOf(from + chunkSize, occupied.size)
                async(Dispatchers.Default) {
                    val local = ArrayList<SwapProposal>()
                    val rnd = Random(rng.nextInt()) // локальный RNG для потока
                    for (idx in from until to) {
                        val firstIndex = occupied[idx]
                        val iCode = grid[firstIndex]
                        if (iCode == -1) continue

                        var bestSecond = -1
                        var bestDelta = 0.0

                        // Ограничиваем число кандидатов
                        val candidatesAll = candidateIndices(firstIndex, searchRadius)
                        if (candidatesAll.isEmpty()) continue

                        val candidates = sampleIndices(candidatesAll, MAX_CAND_PER_FIRST, rnd)

                        for (secondIndex in candidates) {
                            if (secondIndex == firstIndex) continue
                            val jCode = grid[secondIndex]
                            if (jCode == -1) continue

                            val baseSim = similarity(iCode, jCode)
                            if (baseSim < minSim) continue

                            val delta = energyDeltaAfterSwap(
                                firstIndex = firstIndex,
                                secondIndex = secondIndex,
                                lambda = lambda,
                                eta = eta,
                                restrictRadius = localEnergyRadius,
                                rnd = rnd
                            )

                            if (bestSecond == -1 || delta < bestDelta) {
                                bestSecond = secondIndex
                                bestDelta = delta
                            }
                        }

                        if (bestSecond >= 0 && bestDelta < 0.0) {
                            local += SwapProposal(firstIndex, bestSecond, bestDelta)
                        }
                    }
                    local
                }
            }
        }

        val proposals = deferred.flatMap { it.await() }

        val used = BooleanArray(grid.size)
        val swaps = ArrayList<Pair<Int, Int>>()

        proposals
            .sortedBy { it.delta } // самые выгодные (наиболее отрицательные) вперёд
            .forEach { p ->
                if (swaps.size >= maxSwaps) return@forEach
                if (used[p.a] || used[p.b]) return@forEach
                used[p.a] = true
                used[p.b] = true
                swaps += p.a to p.b
            }

        // Применяем батч свапов атомарно
        for ((a, b) in swaps) {
            val tmp = grid[a]
            grid[a] = grid[b]
            grid[b] = tmp
        }
    }

    // ======================= ЭНЕРГИЯ/СХОДСТВО =======================

    /**
     * Быстрая оценка Δ с сэмплированием r.
     * Если restrictRadius != null — используем только r в этом радиусе.
     * Иначе выбираем подмножество всех {0..n-1} размером <= MAX_R_PER_DELTA.
     */
    private fun energyDeltaAfterSwap(
        firstIndex: Int,
        secondIndex: Int,
        lambda: Double,
        eta: Double,
        restrictRadius: Int?,
        rnd: Random,
    ): Double {
        val iCode = grid[firstIndex]
        val jCode = grid[secondIndex]
        if (iCode == -1 || jCode == -1) return 0.0

        val useLocal = restrictRadius != null

        // Формируем список r-кандидатов для оценки
        val rCandidates: IntArray = if (useLocal) {
            val radius = restrictRadius
            ensureNeighbors(radius)
            val neighbors = candidateCache[radius]
                ?: error("Neighbor offsets not precomputed for radius=$radius")

            val set = HashSet<Int>()

            neighbors[firstIndex].forEach { set += it }
            neighbors[secondIndex].forEach { set += it }

            set.remove(firstIndex)
            set.remove(secondIndex)

            val arr = set.filter { it < grid.size }.toIntArray()
            if (arr.size > MAX_R_PER_DELTA) sampleIndices(arr, MAX_R_PER_DELTA, rnd) else arr
        } else {
            // глобальный случай: сэмплируем из 0..n-1
            val all = IntArray(n - 2) { idx ->
                if (idx >= firstIndex && idx + 1 < secondIndex) idx + 1
                else if (idx >= secondIndex) idx + 2
                else idx
            }
            if (all.size > MAX_R_PER_DELTA) sampleIndices(all, MAX_R_PER_DELTA, rnd) else all
        }

        if (rCandidates.isEmpty()) return 0.0

        var delta = 0.0
        val r2Loc = restrictRadius?.toDouble()?.pow(2.0) ?: 0.0
        for (idx in rCandidates.indices) {
            val rIndex = rCandidates[idx]
            val rCode = grid[rIndex]
            if (rCode == -1) continue

            val d1 = dist2(firstIndex, rIndex)
            val d2 = dist2(secondIndex, rIndex)

            if (useLocal) {
                val d1ok = d1 <= r2Loc
                val d2ok = d2 <= r2Loc
                if (!d1ok && !d2ok) continue
            }

            val s1 = tau(similarity(iCode, rCode), lambda, eta)
            val s2 = tau(similarity(jCode, rCode), lambda, eta)
            delta += (s2 - s1) * (d1 - d2)
        }

        return delta
    }

    private fun similarity(i: Int, j: Int): Double {
        if (i == j) return 1.0
        if (wordsPerCode == 0) return 0.0

        val a = minOf(i, j)
        val b = maxOf(i, j)
        val idx1 = simIndexUpperTri(a, b)
        val cached = simMatrix[idx1]
        if (!cached.isNaN()) return cached.toDouble()

        val s = jaccardBit(bitCodes[a], bitCodes[b])
        synchronized(simMatrix) {
            if (simMatrix[idx1].isNaN()) {
                simMatrix[idx1] = s.toFloat()
            }
        }
        return s
    }

    /** Жаккар по единичным битам для bitset-кодов. */
    private fun jaccardBit(a: LongArray, b: LongArray): Double {
        var inter = 0
        var uni = 0
        for (i in a.indices) {
            val aw = a[i]
            val bw = b[i]
            val and = aw and bw
            val or  = aw or bw
            inter += and.countOneBits()
            uni   += or.countOneBits()
        }
        if (uni == 0) return 0.0
        return inter.toDouble() / uni.toDouble()
    }

    private fun tau(x: Double, lambda: Double, eta: Double): Double {
        val sig = if (eta == 0.0) 1.0 else 1.0 / (1.0 + exp(-eta * (x - lambda)))
        return x * sig
    }

    // ======================= ГЕОМЕТРИЯ/УТИЛИТЫ =======================

    private fun ensureNeighbors(radius: Int) {
        if (radius <= 0) return
        if (!neighborOffsetsCache.containsKey(radius)) {
            val offsets = buildNeighborOffsets(radius)
            neighborOffsetsCache[radius] = offsets
            candidateCache[radius] = buildCandidateGrid(offsets)
        }
    }

    /**
     * Строит компактный список смещений (dy, dx) внутри круга радиуса radius.
     * Возвращает IntArray длиной 2*K: [dy0, dx0, dy1, dx1, ...].
     */
    private fun buildNeighborOffsets(radius: Int): IntArray {
        val r2 = radius.toDouble().pow(2.0)
        val tmp = ArrayList<Int>()
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                if (dy == 0 && dx == 0) continue
                val d2 = (dy * dy + dx * dx).toDouble()
                if (d2 <= r2) {
                    tmp += dy
                    tmp += dx
                }
            }
        }
        return tmp.toIntArray()
    }

    /**
     * Список кандидатов-клеток для firstIndex в радиусе radius.
     * Теперь берём из заранее просчитанного кеша.
     */
    private fun candidateIndices(sourceIndex: Int, radius: Int): IntArray {
        if (radius <= 0) return IntArray(0)
        val precomputed = candidateCache[radius]
            ?: error("Neighbor offsets not precomputed for radius=$radius (call ensureNeighbors first)")
        return precomputed[sourceIndex]
    }

    private fun buildCandidateGrid(offsets: IntArray): Array<IntArray> {
        val res = Array(grid.size) { IntArray(0) }
        for (cell in grid.indices) {
            val sy = ys[cell]
            val sx = xs[cell]
            val maxCount = offsets.size / 2
            val tmp = IntArray(maxCount)
            var count = 0

            var i = 0
            while (i < offsets.size) {
                val dy = offsets[i]
                val dx = offsets[i + 1]
                i += 2

                val ny = sy + dy
                val nx = sx + dx
                if (ny < 0 || ny >= gridSize || nx < 0 || nx >= gridSize) continue

                val idx = ny * gridSize + nx
                if (idx == cell) continue
                tmp[count++] = idx
            }

            res[cell] = if (count == tmp.size) tmp else tmp.copyOf(count)
        }
        return res
    }

    private fun dist2(aIndex: Int, bIndex: Int): Double {
        val dy = (ys[aIndex] - ys[bIndex]).toDouble()
        val dx = (xs[aIndex] - xs[bIndex]).toDouble()
        return dy * dy + dx * dx
    }

    private fun lerp(a: Double, b: Double, t: Double): Double =
        a + (b - a) * t.coerceIn(0.0, 1.0)

    private fun toCoord(index: Int): Pair<Int, Int> = ys[index] to xs[index]

    private fun buildCoordinateMap(): List<Triple<Double?, Int, Int>> {
        val res = MutableList<Triple<Double?, Int, Int>>(n) { Triple(0.0, 0, 0) }
        grid.forEachIndexed { idx, codeIndex ->
            if (codeIndex == -1) return@forEachIndexed
            val (angle, _) = angleCodes[codeIndex]
            val (y, x) = toCoord(idx)
            res[codeIndex] = Triple(angle, y, x)
        }
        return res
    }

    private fun logGridState(epoch: Int, tag: String): String {
        val sep = ","
        val sb = StringBuilder()
        for (y in 0 until gridSize) {
            val row = (0 until gridSize).joinToString(sep) { x ->
                val cellIndex = y * gridSize + x
                val id = grid[cellIndex]
                if (id == -1) {
                    "" // пустая ячейка
                } else {
                    val angle = angleCodes[id].first
                    if (angle == null) {
                        ""
                    } else {
                        // формат: angle;y;x
                        val aStr = String.format(Locale.US, "%.1f", angle)
                        "$aStr;$y;$x"
                    }
                }
            }
            sb.appendLine(row)
        }
        val txt = sb.toString().trimEnd()
        println("Эпоха ${epoch + 1} [$tag]:\n$txt\n")
        return txt
    }

    // --------- вспомогательное сэмплирование индексов из массива ---------

    private fun sampleIndices(src: IntArray, k: Int, rnd: Random): IntArray {
        if (src.size <= k) return src
        val res = IntArray(k)
        // простой reservoir sampling / Фишер-Йетс на первых k
        val tmp = src.copyOf()
        var size = tmp.size
        var i = 0
        while (i < k) {
            val j = rnd.nextInt(size)
            res[i] = tmp[j]
            // ставим выбранный в конец и уменьшаем "активный" размер
            tmp[j] = tmp[size - 1]
            size--
            i++
        }
        return res
    }

    private fun simIndexUpperTri(a: Int, b: Int): Int {
        require(a <= b) { "Upper-triangular index expects a<=b (got a=$a, b=$b)" }
        val aa = a.toLong()
        val bb = b.toLong()
        val idx = aa * n - aa * (aa - 1) / 2 + (bb - aa)
        return idx.toInt()
    }
}