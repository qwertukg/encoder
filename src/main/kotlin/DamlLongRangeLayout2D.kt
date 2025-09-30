import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.time.measureTime

/**
 * Минимальная реализация дальнего (long-range) алгоритма раскладки из DAML.pdf.
 *
 * В терминах раздела 5.5.1 статьи класс моделирует ранний этап раскладки, когда точки
 * переставляются «дальними» обменами в пределах большого радиуса для сглаживания глобальной
 * структуры энерго-рельефа. В качестве входа используется список пар «угол-код», где код — это
 * дискретный бинарный вектор, описывающий точку в многомерном пространстве признаков, а угол —
 * вспомогательная величина для визуализации (см. рис. 17).
 *
 * Алгоритм оперирует квадратной решёткой и минимизирует суммарную энергию (раздел 5.6) за счёт
 * попарных перестановок точек. Энергия пары вычисляется согласно принципу «подобные коды
 * должны быть ближе», то есть чем сильнее похожи коды, тем выше штраф за расстояние между ними.
 */
class DamlLongRangeLayout2D(private val angleCodes: List<Pair<Double, IntArray>>) {

    private val gridSize: Int = ceil(sqrt(angleCodes.size.toDouble())).toInt()
    private val grid: MutableList<Int?> = MutableList(gridSize * gridSize) { index ->
        if (index < angleCodes.size) index else null
    }

    /**
     * Выполняет серию дальних обменов (см. раздел 5.5.1 DAML.pdf) для упорядочивания кодов.
     *
     * На каждой итерации рассматривается каждая позиция решётки и выполняется локальный поиск
     * кандидатов в заданном радиусе (идея «охвата дальнего поля»). Если перестановка двух точек
     * уменьшает их общую энергию, обмен подтверждается. Так повторяется указанное количество
     * эпох, что имитирует постепенное «охлаждение» (см. комментарий в разделе 5.8 о контроле
     * параметров).
     *
     * @param farRadius Радиус дальнего поиска (аналог r в таблице гиперпараметров).
     * @param epochs Количество эпох обхода решётки.
     * @return Список троек «исходный угол, координата y, координата x» для визуализации и анализа.
     */
    fun layout(farRadius: Int, epochs: Int): List<Triple<Double, Int, Int>> {
        // Фиксируем стартовое состояние (см. рис. 17a), чтобы отслеживать прогресс алгоритма.
        logGridState(-1)

        if (angleCodes.isEmpty()) return emptyList()
        // Каждая эпоха соответствует полному обходу решётки, как описано в разделе 5.6.
        repeat(epochs.coerceAtLeast(0)) { epoch ->
            val dt = measureTime {
                for (firstIndex in grid.indices) {
                    var currentFirstCodeIndex = grid[firstIndex] ?: continue
                    val secondCandidates = candidateIndices(firstIndex, farRadius)
                    for (secondIndex in secondCandidates) {
                        val secondCodeIndex = grid[secondIndex] ?: continue
                        if (currentFirstCodeIndex == secondCodeIndex) continue
                        // Сравниваем энергию до и после гипотетического обмена (см. раздел 5.6).
                        val currentEnergy = pairEnergy(firstIndex, secondIndex)
                        val swappedEnergy = swappedPairEnergy(firstIndex, secondIndex)
                        if (swappedEnergy < currentEnergy) {
                            val previousFirstCodeIndex = currentFirstCodeIndex
                            grid[firstIndex] = secondCodeIndex
                            currentFirstCodeIndex = secondCodeIndex
                            grid[secondIndex] = previousFirstCodeIndex
                        }
                    }
                }
            }
            println("duration = $dt")
            logGridState(epoch)
        }
        // После завершения эпох строим карту координат, аналогичную таблицам из раздела 5.5.
        return buildCoordinateMap()
    }

    /**
     * Печатает текущее состояние решётки в человекочитаемом виде.
     * Это отвечает практике визуального мониторинга процесса (рис. 17 и обсуждение в разделе 5.8).
     */
    private fun logGridState(epoch: Int) {
        val builder = StringBuilder()
        for (y in 0 until gridSize) {
            val row = (0 until gridSize).joinToString(separator = ",") { x ->
                val codeIndex = grid[y * gridSize + x]
                codeIndex?.let { angleCodes[it].first.toString() } ?: ""
            }
            builder.appendLine(row)
        }
        println("Эпоха ${epoch + 1}:\n${builder.toString().trimEnd()}\n")
    }

    /**
     * Возвращает индексы клеток, попадающих в радиус дальнего поиска от исходной позиции.
     * Суть совпадает с описанием «дальнего» обзора в разделе 5.5.1: мы просматриваем окрестность
     * в пределах радиуса r, исключая текущую точку.
     */
    private fun candidateIndices(sourceIndex: Int, farRadius: Int): Sequence<Int> {
        val radiusSquared = farRadius.toDouble().pow(2.0)
        val sourceY = sourceIndex / gridSize
        val sourceX = sourceIndex % gridSize
        return grid.indices.asSequence().filter { targetIndex ->
            if (targetIndex == sourceIndex) return@filter false
            val targetY = targetIndex / gridSize
            val targetX = targetIndex % gridSize
            val dy = (targetY - sourceY).toDouble()
            val dx = (targetX - sourceX).toDouble()
            dy * dy + dx * dx <= radiusSquared
        }
    }

    /**
     * Вычисляет энергию текущего расположения пары точек относительно остальной решётки.
     *
     * Энергия понимается так же, как в разделе 5.6: расстояние взвешивается мерой сходства кодов.
     * Чем ближе код другой точки, тем сильнее нас «штрафует» за большую дистанцию. Поэтому обмены
     * стремятся притянуть похожие коды друг к другу и разнести непохожие.
     */
    private fun pairEnergy(firstIndex: Int, secondIndex: Int): Double {
        val firstCodeIndex = grid[firstIndex] ?: return 0.0
        val secondCodeIndex = grid[secondIndex] ?: return 0.0
        val firstCoord = toCoord(firstIndex)
        val secondCoord = toCoord(secondIndex)
        var energy = 0.0
        grid.forEachIndexed { otherIndex, otherCodeIndex ->
            val codeIndex = otherCodeIndex ?: return@forEachIndexed
            val otherCoord = toCoord(otherIndex)
            energy += similarity(firstCodeIndex, codeIndex) * distance(firstCoord, otherCoord)
            energy += similarity(secondCodeIndex, codeIndex) * distance(secondCoord, otherCoord)
        }
        return energy
    }

    /**
     * Вычисляет энергию, которую получила бы пара после обмена местами.
     * Это прямое воплощение идеи «point exchange» из раздела 5.6: мы проверяем гипотетическую
     * перестановку, не модифицируя остальной массив, и оцениваем, уменьшилась ли энергия.
     */
    private fun swappedPairEnergy(firstIndex: Int, secondIndex: Int): Double {
        val firstCodeIndex = grid[firstIndex] ?: return 0.0
        val secondCodeIndex = grid[secondIndex] ?: return 0.0
        val firstCoord = toCoord(firstIndex)
        val secondCoord = toCoord(secondIndex)
        var energy = 0.0
        grid.forEachIndexed { otherIndex, otherCodeIndex ->
            val codeIndex = otherCodeIndex ?: return@forEachIndexed
            val otherCoord = toCoord(otherIndex)
            energy += similarity(secondCodeIndex, codeIndex) * distance(firstCoord, otherCoord)
            energy += similarity(firstCodeIndex, codeIndex) * distance(secondCoord, otherCoord)
        }
        return energy
    }

    /**
     * Мера сходства между двумя кодами.
     *
     * В статье (см. раздел 5.5 и 5.6) упоминается, что для дальнего шага подходит грубая метрика,
     * подчеркивающая наличие совпадающих битов. Здесь реализовано простое отношение числа равных
     * битов к длине кода, дополнительно пропущенное через сигмоиду-порог (аналог λ-порогов).
     */
    private fun similarity(firstCodeIndex: Int, secondCodeIndex: Int): Double {
        val first = angleCodes[firstCodeIndex].second
        val second = angleCodes[secondCodeIndex].second
        val length = minOf(first.size, second.size)
        if (length == 0) return 0.0
        var equalBits = 0
        for (i in 0 until length) {
            if (first[i] == second[i]) {
                equalBits += 1
            }
        }
        val baseSimilarity = equalBits.toDouble() / length.toDouble()
        return threshold(baseSimilarity)
    }

    /**
     * Пороговая функция λ с плавным переходом (раздел 5.5, обсуждение о клиппинге).
     *
     * В DAML λ отвечает за отбрасывание слабых связей. Здесь реализован сглаженный вариант через
     * сигмоид, чтобы избегать жёстких разрывов: малые значения подавляются, а сильные почти не
     * искажаются.
     */
    private fun threshold(value: Double, lambda: Double = 0.5, eta: Double = 10.0): Double {
        val sigmoid = 1.0 / (1.0 + exp(-eta * (value - lambda)))
        return value * sigmoid
    }

    /**
     * Евклидово расстояние между двумя клетками решётки.
     * Непосредственно соответствует метрике расстояний в энергетической формуле (раздел 5.6).
     */
    private fun distance(a: Pair<Int, Int>, b: Pair<Int, Int>): Double {
        val dy = (a.first - b.first).toDouble()
        val dx = (a.second - b.second).toDouble()
        return sqrt(dy * dy + dx * dx)
    }

    /**
     * Преобразует линейный индекс в двумерные координаты на решётке.
     * Это вспомогательная функция для всех расчётов, связанных с расстояниями и визуализацией.
     */
    private fun toCoord(index: Int): Pair<Int, Int> = index / gridSize to index % gridSize

    /**
     * Формирует итоговую карту координат для исходных кодов.
     * Это аналог таблиц координат, которые строятся после завершения цикла раскладки (см. раздел 5.5).
     */
    private fun buildCoordinateMap(): List<Triple<Double, Int, Int>> {
        val result = MutableList(angleCodes.size) { Triple(0.0, 0, 0) }
        grid.forEachIndexed { index, codeIndex ->
            val actualIndex = codeIndex ?: return@forEachIndexed
            val (angle, _) = angleCodes[actualIndex]
            val coord = toCoord(index)
            result[actualIndex] = Triple(angle, coord.first, coord.second)
        }
        return result
    }
}
