import viz.showLayout
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.time.Duration
import kotlin.time.measureTime

/**
 * DamlLayout2D — раскладка разрежённых бинарных кодов на 2D решётку.
 *
 * Ключевые идеи, реализованные в этом классе:
 * 1) Long-range раскладка: минимизируем глобальную энергию пар перестановками точек.
 *    Энергия пары (p,q) относительно остальных точек V^+ оценивается как сумма
 *      φ = Σ_{r∈V^+} [ sim(p,r) * dist(p,r) + sim(q,r) * dist(q,r) ].
 *    Для гипотетического свапа p↔q считаем φ_swap аналогично и применяем обмен,
 *    если Δ = φ_swap - φ_current < 0. Для эффективности Δ считаем аналитически:
 *      Δ = Σ_{r∈V^+} (sim(q,r) - sim(p,r)) * (d(p,r) - d(q,r)).
 *    Это позволяет за один проход по r получить обе энергии (без двойного пересчёта).
 *
 * 2) Метрика сходства для разрежённых кодов — Жаккар по единичным битам (не «доля равных битов»),
 *    т.к. последняя завышает сходство из-за доминирования нулей.
 *    Затем применяется пороговая функция τ(x) = x * σ(η(x - λ)), где σ — сигмоида.
 *
 * 3) Стохастика: первую точку выбираем в случайном порядке; вторую — внутри радиуса.
 *    Это уменьшает зависимость от порядка обхода и лучше «разлепляет» карту на ранних стадиях.
 *
 * 4) Батчевые свапы: в каждой эпохе собираем набор выгодных обменов без общих индексов
 *    и применяем их одним коммитом, что снижает артефакты порядка и локальные колебания.
 *
 * 5) Без sqrt: работаем с квадратами расстояний — критерий сравнения сохраняется,
 *    а вычисления быстрее.
 *
 * 6) Опциональная short-range «полировка»: локальная минимизация энергии в малом радиусе,
 *    чтобы пригладить мелкие огрехи топологии, не ломая глобальную структуру.
 */
class DampLayout2D(
    private val angleCodes: List<Pair<Double, IntArray>>,
    randomizeStart: Boolean = true,
    seed: Int = 42,
) {
    private val rng = Random(seed)
    private val n = angleCodes.size
    private val gridSize: Int = ceil(sqrt(n.toDouble())).toInt()
    val swapsLog = mutableListOf<Int>()

    // Решётка хранит индексы кодов или null (если ячейка пустая)
    private val grid: MutableList<Int?> = MutableList(gridSize * gridSize) { null }

    init {
        // Инициализация: кладём коды в первые n ячеек (строго слева направо, сверху вниз),
        // но случайно перемешиваем порядок самих кодов — так пустые клетки остаются только в
        // конце матрицы, а стартовая конфигурация остаётся стохастической (см. DAML, разд. 5.6).
        val codeOrder = (0 until n).toMutableList()
        if (randomizeStart) codeOrder.shuffle(rng)
        codeOrder.forEachIndexed { idx, codeIndex ->
            grid[idx] = codeIndex
        }
    }

    // ======================= ПУБЛИЧНЫЕ API =======================

    /**
     * Long-range раскладка.
     *
     * @param farRadius радиус дальнего поиска в клетках
     * @param epochs количество эпох (полных обходов)
     * @param minSim минимальное базовое сходство (Жаккар) для отбора пары (ускоряет)
     * @param lambdaStart начальное λ в τ-пороге (фильтрует слабые связи)
     * @param lambdaEnd финальное λ (можно поднять к концу, чтобы «дожать» сильные связи)
     * @param eta крутизна сигмоиды в τ
     * @param maxBatchFrac максимум доли точек, участвующих в свапах за эпоху (0..1)
     * @param log печатать состояние решётки по эпохам
     * @return список троек (угол, y, x) для исходных кодов
     */
    fun layoutLongRange(
        farRadius: Int,
        epochs: Int,
        minSim: Double = 0.0,
        lambdaStart: Double = 0.45,
        lambdaEnd: Double = 0.70,
        eta: Double = 10.0,
        maxBatchFrac: Double = 0.5,
        log: Boolean = true,
    ): List<Triple<Double, Int, Int>> {
        if (n == 0) return emptyList()
        if (log) {
            val csv = logGridState(epoch = -1, tag = "start")
            showLayout(csv)
        }
        var swapCount = 0
        repeat(epochs.coerceAtLeast(0)) { e ->
            val lam = lerp(lambdaStart, lambdaEnd, if (epochs <= 1) 1.0 else e.toDouble() / (epochs - 1).coerceAtLeast(1))
            val dt: Duration = measureTime {
                doOneEpoch(
                    searchRadius = farRadius,
                    lambda = lam,
                    eta = eta,
                    minSim = minSim,
                    maxBatchFrac = maxBatchFrac,
                    localEnergyRadius = null, // long-range: считаем энергию по всей решётке
                )
            }
            if (log) {
                println("long-range epoch=${e + 1}  lambda=%.3f  duration=%s".format(lam, dt))
                val csv = logGridState(epoch = e, tag = "long")
                showLayout(csv)
            }
        }
        return buildCoordinateMap()
    }

    // ======================= ОСНОВНАЯ ЭПОХА =======================

    /**
     * Один батчевый проход:
     *  - случайный порядок «первых» позиций
     *  - поиск лучшего «второго» в радиусе
     *  - расчёт выгод свапов Δ по аналитической формуле
     *  - отбор неконфликтующих свапов и атомарное применение батча
     *
     * Если localEnergyRadius == null — оцениваем Δ по всей решётке (long-range).
     * Если localEnergyRadius != null — считаем Δ только по соседям в этом радиусе (short-range).
     */
    private fun doOneEpoch(
        searchRadius: Int,
        lambda: Double,
        eta: Double,
        minSim: Double,
        maxBatchFrac: Double,
        localEnergyRadius: Int?,
    ) {
        val occupied = grid.indices.filter { grid[it] != null }.toMutableList()
        occupied.shuffle(rng)

        val taken = BooleanArray(grid.size) // запрет двойного участия в батче
        val swaps = ArrayList<Pair<Int, Int>>() // пары индексов ячеек (i, j)
        val maxSwaps = (occupied.size * maxBatchFrac).toInt().coerceAtLeast(1)

        for (firstIndex in occupied) {
            if (swaps.size >= maxSwaps) break
            if (taken[firstIndex]) continue
            val iCode = grid[firstIndex] ?: continue

            var bestSecond = -1
            var bestDelta = 0.0 // Ищем Δ < 0 (уменьшение энергии)
            val candidates = candidateIndices(firstIndex, searchRadius)
                .filter { it != firstIndex && !taken[it] && grid[it] != null }

            for (secondIndex in candidates) {
                val jCode = grid[secondIndex]!!
                // Базовый Жаккар как быстрый фильтр (без τ)
                val baseSim = jaccard(iCode, jCode)
                if (baseSim < minSim) continue

                // Δ по всей решётке или локально (в малом радиусе)
                val delta = energyDeltaAfterSwap(
                    firstIndex = firstIndex,
                    secondIndex = secondIndex,
                    lambda = lambda,
                    eta = eta,
                    restrictRadius = localEnergyRadius
                )

                if (bestSecond == -1 || delta < bestDelta) {
                    bestSecond = secondIndex
                    bestDelta = delta
                }
            }

            if (bestSecond >= 0 && bestDelta < 0.0 && !taken[bestSecond]) {
                swaps += firstIndex to bestSecond
                taken[firstIndex] = true
                taken[bestSecond] = true
            }
        }

        // Применяем батч неконфликтующих свапов (атомарно)
        for ((a, b) in swaps) {
            val ia = grid[a]
            val ib = grid[b]
            grid[a] = ib
            grid[b] = ia
        }

        swapsLog.add(swaps.size)
    }

    // ======================= ЭНЕРГИЯ/СХОДСТВО =======================

    /**
     * Аналитическая дельта энергии Δ = φ_swap - φ_current.
     * Для каждой «третьей» точки r:
     *   d1 = dist2(p, r), d2 = dist2(q, r)
     *   s1 = τ(sim(iCode, rCode)), s2 = τ(sim(jCode, rCode))
     *   вклад в Δ: (s2 - s1) * (d1 - d2)
     *
     * Если restrictRadius == null — суммируем по всем занятым r.
     * Иначе — только по r в круге радиуса restrictRadius вокруг p и q.
     */
    private fun energyDeltaAfterSwap(
        firstIndex: Int,
        secondIndex: Int,
        lambda: Double,
        eta: Double,
        restrictRadius: Int?,
    ): Double {
        val iCode = grid[firstIndex] ?: return 0.0
        val jCode = grid[secondIndex] ?: return 0.0
        val p = toCoord(firstIndex)
        val q = toCoord(secondIndex)

        val useLocal = restrictRadius != null
        val r2 = restrictRadius?.toDouble()?.pow(2.0) ?: 0.0

        var delta = 0.0
        grid.forEachIndexed { rIndex, rCodeIdx ->
            val rCode = rCodeIdx ?: return@forEachIndexed
            if (rIndex == firstIndex || rIndex == secondIndex) return@forEachIndexed

            val rCoord = toCoord(rIndex)
            val d1 = dist2(p, rCoord)
            val d2 = dist2(q, rCoord)

            if (useLocal) {
                val d1ok = d1 <= r2
                val d2ok = d2 <= r2
                if (!d1ok && !d2ok) return@forEachIndexed
            }

            val s1 = tau(jaccard(iCode, rCode), lambda, eta)
            val s2 = tau(jaccard(jCode, rCode), lambda, eta)

            delta += (s2 - s1) * (d1 - d2)
        }
        return delta
    }

    /** Жаккар по единичным битам IntArray{0,1}. */
    private fun jaccard(i: Int, j: Int): Double {
        val a = angleCodes[i].second
        val b = angleCodes[j].second
        val m = minOf(a.size, b.size)
        var inter = 0
        var uni = 0
        var k = 0
        while (k < m) {
            val ak = a[k]
            val bk = b[k]
            val a1 = ak == 1
            val b1 = bk == 1
            if (a1 || b1) uni++
            if (a1 && b1) inter++
            k++
        }
        if (uni == 0) return 0.0
        return inter.toDouble() / uni.toDouble()
    }

    /** τ-порог: τ(x) = x * σ(η(x - λ)). */
    private fun tau(x: Double, lambda: Double, eta: Double): Double {
        val sig = 1.0 / (1.0 + exp(-eta * (x - lambda)))
        return x * sig
    }

    // ======================= ГЕОМЕТРИЯ/УТИЛИТЫ =======================

    /** Кандидаты (индексы ячеек) в круге радиуса r от sourceIndex. */
    private fun candidateIndices(sourceIndex: Int, radius: Int): Sequence<Int> {
        val r = Random.nextInt(1..radius)
        val r2 = r.toDouble().pow(2.0)
        val (sy, sx) = toCoord(sourceIndex)
        return grid.indices.asSequence().filter { idx ->
            if (idx == sourceIndex) return@filter false
            val (ty, tx) = toCoord(idx)
            val dy = (ty - sy).toDouble()
            val dx = (tx - sx).toDouble()
            (dy * dy + dx * dx) <= r2
        }
    }

    /** Квадрат евклидова расстояния между клетками (без sqrt). */
    private fun dist2(a: Pair<Int, Int>, b: Pair<Int, Int>): Double {
        val dy = (a.first - b.first).toDouble()
        val dx = (a.second - b.second).toDouble()
        return dy * dy + dx * dx
    }

    /** Линейная интерполяция. */
    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t.coerceIn(0.0, 1.0)

    /** Индекс -> (y, x). */
    private fun toCoord(index: Int): Pair<Int, Int> = index / gridSize to index % gridSize

    /** Текущая карта координат (угол, y, x) для исходных кодов. */
    private fun buildCoordinateMap(): List<Triple<Double, Int, Int>> {
        val res = MutableList(n) { Triple(0.0, 0, 0) }
        grid.forEachIndexed { idx, codeIndex ->
            val actual = codeIndex ?: return@forEachIndexed
            val (angle, _) = angleCodes[actual]
            val (y, x) = toCoord(idx)
            res[actual] = Triple(angle, y, x)
        }
        return res
    }

    /** Печать состояния решётки (для отладки/мониторинга). */
    private fun logGridState(epoch: Int, tag: String): String {
        val sep = "," // безопасный разделитель для копипаста в Excel/CSV
        val sb = StringBuilder()
        for (y in 0 until gridSize) {
            val row = (0 until gridSize).joinToString(sep) { x ->
                val id = grid[y * gridSize + x]
                when (id) {
                    null -> ""                                    // пустая клетка → пустое поле CSV
                    else -> String.format(Locale.US, "%.1f", angleCodes[id].first)
                    // если углы всегда кратны 1°, можно короче:
                    // else -> angleCodes[id].first.roundToInt().toString()
                }
            }
            sb.appendLine(row)
        }
        println("Эпоха ${epoch + 1} [$tag]:\n${sb.toString().trimEnd()}\n")
        return sb.toString().trimEnd()
    }
}