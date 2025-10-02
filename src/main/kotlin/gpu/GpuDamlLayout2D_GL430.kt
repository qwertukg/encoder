package gpu

import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL43C.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.memAlloc
import org.lwjgl.system.MemoryUtil.memAllocFloat
import org.lwjgl.system.MemoryUtil.memAllocInt
import org.lwjgl.system.MemoryUtil.memFree
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * GpuDamlLayout2D_GL430 — GPU-реализация DAML (long-range) через compute + SSBO.
 * Идея: коды лежат в бит-пакованном SSBO (только для сходства), позиционная карта grid[]
 * хранит индекс кода в каждой клетке. Мы свапаем ИНДЕКСЫ в grid (а не копируем массивы битов).
 *
 * Вызов: val out = GpuDamlLayout2D_GL430(angleCodes).layoutLongRange(...)
 * Возврат: список Pair(angle, IntArray) в ПОРЯДКЕ обхода решётки (row-major).
 */
@OptIn(ExperimentalUnsignedTypes::class)
class GpuDamlLayout2D_GL430(
    private val angleCodes: List<Pair<Double?, IntArray>>,
    private val randomizeStart: Boolean = true,
    private val seed: Int = 42
) {
    private val n = angleCodes.size
    private val gridSize: Int = ceil(sqrt(n.toDouble())).toInt()
    private val bitsPerCode = angleCodes.firstOrNull()?.second?.size ?: 0
    private val wordsPerCode = (bitsPerCode + 31) / 32

    // --- GL objects ---
    private var progBest = 0
    private var progSwap = 0
    private var ssboCodes = 0
    private var ssboGrid = 0
    private var ssboBestJ = 0
    private var ssboBestDelta = 0
    private var ssboPairs = 0

    // --- CPU side storage ---
    private val codesPacked = packAllCodes(angleCodes.map { it.second })
    private val gridInit = IntArray(gridSize * gridSize) { -1 }

    init {
        GLFW.glfwInit()
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4)
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3) // просим 4.3
        val win = GLFW.glfwCreateWindow(64, 64, "ctx", 0, 0)
        require(win != 0L) { "GLFW window failed" }
        GLFW.glfwMakeContextCurrent(win)
        org.lwjgl.opengl.GL.createCapabilities()   // ВАЖНО: вызвать до любых gl* вызовов

        // начальная раскладка: первые n ячеек заполняем 0..n-1
        val order = (0 until n).toMutableList().also {
            if (randomizeStart) it.shuffle(java.util.Random(seed.toLong()))
        }
        for (i in order.indices) gridInit[i] = order[i]
        setupGL()
    }

    fun dispose() {
        glDeleteProgram(progBest)
        glDeleteProgram(progSwap)
        glDeleteBuffers(ssboCodes)
        glDeleteBuffers(ssboGrid)
        glDeleteBuffers(ssboBestJ)
        glDeleteBuffers(ssboBestDelta)
        glDeleteBuffers(ssboPairs)
    }

    /**
     * Long-range раскладка на GPU (энергия по всей решётке).
     * Параметры эквивалентны твоему CPU-классу.
     */
    fun layoutLongRange(
        farRadius: Int,
        epochs: Int,
        minSim: Double = 0.0,
        lambdaStart: Double = 0.45,
        lambdaEnd: Double = 0.70,
        eta: Double = 10.0,
        maxBatchFrac: Double = 0.5,
    ): List<Pair<Double?, IntArray>> {
        // загрузка исходных буферов
        uploadSSBO(ssboCodes, codesPacked)
        uploadSSBO(ssboGrid, gridInit)
        // bestJ / bestDelta размером в кол-во ячеек
        uploadSSBO(ssboBestJ, IntArray(gridSize * gridSize) { -1 })
        uploadSSBO(ssboBestDelta, FloatArray(gridSize * gridSize) { 0f })

        val totalCells = gridSize * gridSize
        val maxSwapsPerEpoch = (occupiedCountCPU(downloadGrid()) * maxBatchFrac).toInt().coerceAtLeast(1)
        val r2 = (farRadius * farRadius)

        for (e in 0 until epochs) {
            val t = if (epochs <= 1) 1.0 else (e.toDouble() / (epochs - 1).coerceAtLeast(1))
            val lambda = (lambdaStart + (lambdaEnd - lambdaStart) * t).coerceIn(0.0, 1.0)

            // pass 1: GPU считает лучший j и его delta для каждого занятого i (в радиусе farRadius)
            glUseProgram(progBest)
            glUniform1i(glGetUniformLocation(progBest, "uGridSize"), gridSize)
            glUniform1i(glGetUniformLocation(progBest, "uNumCodes"), n)
            glUniform1i(glGetUniformLocation(progBest, "uBitsPerCode"), bitsPerCode)
            glUniform1i(glGetUniformLocation(progBest, "uWordsPerCode"), wordsPerCode)
            glUniform1i(glGetUniformLocation(progBest, "uRadius2"), r2)
            glUniform1f(glGetUniformLocation(progBest, "uLambda"), lambda.toFloat())
            glUniform1f(glGetUniformLocation(progBest, "uEta"), eta.toFloat())
            glUniform1f(glGetUniformLocation(progBest, "uMinSim"), minSim.toFloat())

            // bind SSBOs
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssboCodes)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssboGrid)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ssboBestJ)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, ssboBestDelta)

            // одно рабочее задание на каждую ячейку
            val groups = (totalCells + 255) / 256
            glDispatchCompute(groups, 1, 1)
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_BUFFER_UPDATE_BARRIER_BIT)

            // CPU: читаем bestJ/delta, строим неконфликтный батч
            val bestJ = downloadInts(ssboBestJ, totalCells)
            val bestD = downloadFloats(ssboBestDelta, totalCells)
            val batchPairs = buildNonConflictingBatch(bestJ, bestD, maxSwapsPerEpoch)

            if (batchPairs.isEmpty()) {
                // сходимость
                break
            }

            // pass 2: GPU свапает grid по списку пар
            uploadSSBO(ssboPairs, batchPairs.flatMap { listOf(it.first, it.second) }.toIntArray())

            glUseProgram(progSwap)
            glUniform1i(glGetUniformLocation(progSwap, "uNumPairs"), batchPairs.size)
            glUniform1i(glGetUniformLocation(progSwap, "uGridSize"), gridSize)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssboGrid)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, ssboPairs)

            val groupsSwap = (batchPairs.size + 255) / 256
            glDispatchCompute(groupsSwap, 1, 1)
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_BUFFER_UPDATE_BARRIER_BIT)
        }

        // финальный grid → возвращаем список в порядке решётки (row-major)
        val finalGrid = downloadGrid()
        val out = ArrayList<Pair<Double?, IntArray>>(n)
        for (cell in 0 until finalGrid.size) {
            val codeIdx = finalGrid[cell]
            if (codeIdx >= 0) {
                val (angle, bits) = angleCodes[codeIdx]
                out += angle to bits
            }
        }
        return out
    }

    // ----------------- Helpers -----------------

    private fun setupGL() {
        progBest = linkCompute(COMPUTE_BEST_SRC)
        progSwap = linkCompute(COMPUTE_SWAP_SRC)
        ssboCodes = glGenBuffers()
        ssboGrid = glGenBuffers()
        ssboBestJ = glGenBuffers()
        ssboBestDelta = glGenBuffers()
        ssboPairs = glGenBuffers()
    }

    // IntArray
    private fun uploadSSBO(buffer: Int, data: IntArray) {
        val bb = memAllocInt(data.size)
        bb.put(data).flip()
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
        glBufferData(GL_SHADER_STORAGE_BUFFER, bb, GL_DYNAMIC_DRAW)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
        memFree(bb)
    }

    // FloatArray
    private fun uploadSSBO(buffer: Int, data: FloatArray) {
        val fb = memAllocFloat(data.size)
        fb.put(data).flip()
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
        glBufferData(GL_SHADER_STORAGE_BUFFER, fb, GL_DYNAMIC_DRAW)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
        memFree(fb)
    }

    // UIntArray
    private fun uploadSSBO(buffer: Int, data: UIntArray) {
        val ib = memAllocInt(data.size)
        data.forEach { ib.put(it.toInt()) }
        ib.flip()
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
        glBufferData(GL_SHADER_STORAGE_BUFFER, ib, GL_STATIC_DRAW)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
        memFree(ib)
    }

    private fun downloadGrid(): IntArray {
        val totalCells = gridSize * gridSize
        return downloadInts(ssboGrid, totalCells)
    }

    private fun downloadInts(buffer: Int, count: Int): IntArray {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
        val size = glGetBufferParameteri(GL_SHADER_STORAGE_BUFFER, GL_BUFFER_SIZE)
        require(size >= count * 4) { "SSBO too small: size=$size, need=${count*4}" }

        val tmp = memAlloc(size).order(ByteOrder.nativeOrder())
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, tmp)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)

        tmp.rewind()
        val ib = tmp.asIntBuffer()
        val out = IntArray(count)
        ib.get(out, 0, count.coerceAtMost(ib.remaining()))
        memFree(tmp)
        return out
    }

    private fun downloadFloats(buffer: Int, count: Int): FloatArray {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
        val size = glGetBufferParameteri(GL_SHADER_STORAGE_BUFFER, GL_BUFFER_SIZE)
        require(size >= count * 4) { "SSBO too small: size=$size, need=${count*4}" }

        val tmp = memAlloc(size).order(ByteOrder.nativeOrder())
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, tmp)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)

        tmp.rewind()
        val fb = tmp.asFloatBuffer()
        val out = FloatArray(count)
        fb.get(out, 0, count.coerceAtMost(fb.remaining()))
        memFree(tmp)
        return out
    }

    private fun occupiedCountCPU(grid: IntArray): Int = grid.count { it >= 0 }

    private fun buildNonConflictingBatch(bestJ: IntArray, bestD: FloatArray, maxPairs: Int): List<Pair<Int, Int>> {
        val used = BooleanArray(bestJ.size)
        // Собираем все кандидаты с отрицательным Δ, сортируем по возрастанию Δ
        val cand = bestJ.indices
            .filter { idx -> bestJ[idx] >= 0 && bestD[idx] < 0f && !used[idx] && !used[bestJ[idx]] }
            .map { idx -> Triple(idx, bestJ[idx], bestD[idx]) }
            .sortedBy { it.third } // самое выгодное (наименьшее Δ) сначала

        val res = ArrayList<Pair<Int, Int>>(maxPairs)
        for ((i, j, _) in cand) {
            if (res.size >= maxPairs) break
            if (used[i] || used[j]) continue
            used[i] = true
            used[j] = true
            res += i to j
        }
        return res
    }

    // Пакуем массив 0/1 в слова по 32 бита
    private fun packAllCodes(codes: List<IntArray>): UIntArray {
        val out = UIntArray(codes.size * wordsPerCode)
        codes.forEachIndexed { idx, bits ->
            var w = 0
            var word = 0u
            var outPos = idx * wordsPerCode
            for (b in bits.indices) {
                if (bits[b] != 0) {
                    val bit = (b % 32)
                    word = word or (1u shl bit)
                }
                if ((b % 32) == 31) {
                    out[outPos++] = word
                    word = 0u
                    w++
                }
            }
            if ((bits.size % 32) != 0) {
                out[outPos] = word
            }
        }
        return out
    }

    private fun linkCompute(src: String): Int {
        val sh = glCreateShader(GL_COMPUTE_SHADER)
        glShaderSource(sh, src)
        glCompileShader(sh)
        if (glGetShaderi(sh, GL_COMPILE_STATUS) == GL_FALSE) {
            val log = glGetShaderInfoLog(sh)
            glDeleteShader(sh)
            error("Compute shader compile error:\n$log")
        }
        val prog = glCreateProgram()
        glAttachShader(prog, sh)
        glLinkProgram(prog)
        glDeleteShader(sh)
        if (glGetProgrami(prog, GL_LINK_STATUS) == GL_FALSE) {
            val log = glGetProgramInfoLog(prog)
            glDeleteProgram(prog)
            error("Program link error:\n$log")
        }
        return prog
    }

    companion object {
        // Compute шейдер 1: лучший j и его Δ для каждой занятой ячейки i
        private val COMPUTE_BEST_SRC = """
#version 430 core
layout(local_size_x = 256) in;

layout(std430, binding = 0) readonly buffer Codes { uint words[]; };          // [numCodes * wordsPerCode]
layout(std430, binding = 1) buffer Grid { int codeIndex[]; };                 // [gridSize*gridSize], -1 = пусто
layout(std430, binding = 2) writeonly buffer BestJ { int bestJ[]; };          // [gridSize*gridSize]
layout(std430, binding = 3) writeonly buffer BestD { float bestDelta[]; };    // [gridSize*gridSize]

uniform int   uGridSize;
uniform int   uNumCodes;
uniform int   uBitsPerCode;
uniform int   uWordsPerCode;
uniform int   uRadius2;      // farRadius^2
uniform float uLambda;
uniform float uEta;
uniform float uMinSim;

float sigmoid(float x) { return 1.0 / (1.0 + exp(-x)); }
float tau(float x)     { return x * sigmoid(uEta * (x - uLambda)); }

uint wordAt(int codeIdx, int w) {
    return words[codeIdx * uWordsPerCode + w];
}

float jaccard(int codeA, int codeB) {
    int inter = 0;
    int uni   = 0;
    for (int w=0; w<uWordsPerCode; ++w) {
        uint a = wordAt(codeA, w);
        uint b = wordAt(codeB, w);
        inter += bitCount(a & b);
        uni   += bitCount(a | b);
    }
    if (uni == 0) return 0.0;
    return float(inter) / float(uni);
}

void main() {
    uint cell = gl_GlobalInvocationID.x; // iCell
    int totalCells = uGridSize * uGridSize;
    if (cell >= totalCells) return;

    int iCode = codeIndex[cell];
    if (iCode < 0) {
        bestJ[cell] = -1;
        bestDelta[cell] = 0.0;
        return;
    }

    // координаты i
    int iy = int(cell) / uGridSize;
    int ix = int(cell) % uGridSize;

    float best_d = 0.0;
    int   best_j = -1;

    // перебираем кандидатов j в радиусе
    for (int jCell=0; jCell<totalCells; ++jCell) {
        if (jCell == int(cell)) continue;
        int jCode = codeIndex[jCell];
        if (jCode < 0) continue;

        int jy = jCell / uGridSize;
        int jx = jCell % uGridSize;

        int dy = jy - iy;
        int dx = jx - ix;
        int dist2_ij = dy*dy + dx*dx;
        if (dist2_ij > uRadius2) continue;

        // быстрый фильтр: base J(i,j) >= minSim?
        float baseSim = jaccard(iCode, jCode);
        if (baseSim < uMinSim) continue;

        // считаем Δ по всей решётке
        float delta = 0.0;
        for (int rCell=0; rCell<totalCells; ++rCell) {
            if (rCell == int(cell) || rCell == jCell) continue;
            int rCode = codeIndex[rCell];
            if (rCode < 0) continue;

            int ry = rCell / uGridSize;
            int rx = rCell % uGridSize;

            float d1 = float((ry-iy)*(ry-iy) + (rx-ix)*(rx-ix));
            float d2 = float((ry-jy)*(ry-jy) + (rx-jx)*(rx-jx));

            float s1 = tau(jaccard(iCode, rCode));
            float s2 = tau(jaccard(jCode, rCode));

            delta += (s2 - s1) * (d1 - d2);
        }

        if (best_j == -1 || delta < best_d) {
            best_j = jCell;
            best_d = delta;
        }
    }

    bestJ[cell] = best_j;
    bestDelta[cell] = best_d;
}
""".trimIndent()

        // Compute шейдер 2: применяем свапы к grid (пары ячеек)
        private val COMPUTE_SWAP_SRC = """
#version 430 core
layout(local_size_x = 256) in;

layout(std430, binding = 1) buffer Grid { int codeIndex[]; };
layout(std430, binding = 4) readonly buffer Pairs { int pairs[]; }; // [2*numPairs], (a,b) подряд

uniform int uNumPairs;
uniform int uGridSize;

void main() {
    uint gid = gl_GlobalInvocationID.x;
    if (gid >= uint(uNumPairs)) return;

    int a = pairs[2*int(gid) + 0];
    int b = pairs[2*int(gid) + 1];

    // swap codeIndex[a] <-> codeIndex[b]
    int ia = codeIndex[a];
    int ib = codeIndex[b];
    codeIndex[a] = ib;
    codeIndex[b] = ia;
}
""".trimIndent()
    }
}