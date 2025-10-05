package gpu

import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL43C.*
import org.lwjgl.opengl.GLUtil
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryUtil.*
import java.nio.*
import java.nio.ByteOrder.nativeOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
    private var progSim   = 0
    private var progBest  = 0
    private var progSwap  = 0

    private var ssboCodes     = 0 // uint words [n * wordsPerCode]
    private var ssboGrid      = 0 // int  codeIndex [gridSize*gridSize]
    private var ssboBestJ     = 0 // int  bestJ      [gridSize*gridSize]
    private var ssboBestDelta = 0 // float bestDelta [gridSize*gridSize]
    private var ssboPairs     = 0 // int  pairs      [2 * numPairs]
    private var ssboSim       = 0 // float S         [n * n]

    // --- CPU side storage ---
    private val codesPacked = packAllCodes(angleCodes.map { it.second })   // IntArray
    private val gridInit    = IntArray(gridSize * gridSize) { -1 }

    init {
        require(n > 0) { "Нет входных кодов" }
        GLFW.glfwInit()
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4)
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
        val win = GLFW.glfwCreateWindow(64, 64, "ctx", 0, 0)
        require(win != 0L) { "GLFW window failed" }
        GLFW.glfwMakeContextCurrent(win)
        GL.createCapabilities()

        // Включаем отладку GL
        Configuration.DEBUG.set(true)
        GLUtil.setupDebugMessageCallback(System.err)

        // начальная раскладка: первые n ячеек — 0..n-1 в случайном или прямом порядке
        val order = (0 until n).toMutableList().also {
            if (randomizeStart) it.shuffle(java.util.Random(seed.toLong()))
        }
        for (i in order.indices) gridInit[i] = order[i]

        setupGL()
        uploadStatic()
        buildSimilarityOnce() // S = Jaccard(codes) (GPU, один раз)
    }

    fun dispose() {
        glDeleteProgram(progSim)
        glDeleteProgram(progBest)
        glDeleteProgram(progSwap)
        glDeleteBuffers(ssboCodes)
        glDeleteBuffers(ssboGrid)
        glDeleteBuffers(ssboBestJ)
        glDeleteBuffers(ssboBestDelta)
        glDeleteBuffers(ssboPairs)
        glDeleteBuffers(ssboSim)
        // Контекст GLFW можно не трогать (скрытое окно), или закрыть при желании
    }

    /**
     * Энерго-раскладка («дальний порядок») на GPU.
     * @param farRadius    радиус поиска кандидата j
     * @param deltaRadius  радиус учёта соседей r при оценке Δ (рекоменд. >= farRadius, но заметно меньше всей решётки)
     * @param epochs
     * @param minSim       фильтр на базовую похожесть J(i,j)
     * @param lambdaStart  τ-порог (начало)
     * @param lambdaEnd    τ-порог (конец)
     * @param eta          крутизна τ
     * @param maxBatchFrac доля неконфликтных свапов за эпоху
     */
    fun layoutLongRange(
        farRadius: Int,
        deltaRadius: Int = max(4, farRadius * 2),
        epochs: Int,
        minSim: Double = 0.25,
        lambdaStart: Double = 0.30,
        lambdaEnd: Double = 0.90,
        eta: Double = 0.0,
        maxBatchFrac: Double = 0.30,
    ): List<Pair<Double?, IntArray>> {

        // загрузка динамических буферов (grid/bestJ/bestDelta)
        uploadInts(ssboGrid, gridInit)
        uploadInts(ssboBestJ, IntArray(gridSize * gridSize) { -1 })
        uploadFloats(ssboBestDelta, FloatArray(gridSize * gridSize) { 0f })

        val totalCells = gridSize * gridSize

        for (e in 0 until epochs) {
            val t = if (epochs <= 1) 1.0 else e.toDouble() / (epochs - 1).coerceAtLeast(1)
            val lambda = (lambdaStart + (lambdaEnd - lambdaStart) * t).coerceIn(0.0, 1.0)

            // PASS 1: найти лучший j и Δ (используя предвычисленную матрицу S)
            glUseProgram(progBest)
            glUniform1i(glGetUniformLocation(progBest, "uGridSize"), gridSize)
            glUniform1i(glGetUniformLocation(progBest, "uNumCodes"), n)
            glUniform1i(glGetUniformLocation(progBest, "uRadius2"), farRadius * farRadius)
            glUniform1i(glGetUniformLocation(progBest, "uDeltaRadius2"), deltaRadius * deltaRadius)
            glUniform1f(glGetUniformLocation(progBest, "uLambda"), lambda.toFloat())
            glUniform1f(glGetUniformLocation(progBest, "uEta"), eta.toFloat())
            glUniform1f(glGetUniformLocation(progBest, "uMinSim"), minSim.toFloat())

            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssboCodes)     // только для размера, фактически не читаем
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssboGrid)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ssboBestJ)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, ssboBestDelta)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, ssboSim)       // S[n*n]

            val groups = (totalCells + 255) / 256
            glDispatchCompute(groups, 1, 1)
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_BUFFER_UPDATE_BARRIER_BIT)

            // PASS 1.5: забрать bestJ/Δ на CPU и построить батч
            val bestJ = downloadInts(ssboBestJ, totalCells)
            val bestD = downloadFloats(ssboBestDelta, totalCells)
            val maxSwaps = max(1, (occupiedCountCPU(downloadGrid()) * maxBatchFrac).toInt())
            val batchPairs = buildNonConflictingBatch(bestJ, bestD, maxSwaps)
            if (batchPairs.isEmpty()) break

            // PASS 2: применить свапы
            uploadInts(ssboPairs, batchPairs.flatMap { listOf(it.first, it.second) }.toIntArray())
            glUseProgram(progSwap)
            glUniform1i(glGetUniformLocation(progSwap, "uNumPairs"), batchPairs.size)
            glUniform1i(glGetUniformLocation(progSwap, "uGridSize"), gridSize)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssboGrid)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, ssboPairs)
            val groupsSwap = (batchPairs.size + 255) / 256
            glDispatchCompute(groupsSwap, 1, 1)
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_BUFFER_UPDATE_BARRIER_BIT)
        }

        // Возврат по порядку решётки
        val finalGrid = downloadGrid()
        val out = ArrayList<Pair<Double?, IntArray>>(n)
        for (cell in finalGrid.indices) {
            val codeIdx = finalGrid[cell]
            if (codeIdx >= 0) out += angleCodes[codeIdx]
        }
        return out
    }

    // ----------------- GL setup & helpers -----------------

    private fun setupGL() {
        progSim  = linkCompute(COMPUTE_SIM_SRC)
        progBest = linkCompute(COMPUTE_BEST_USING_S_SRC)
        progSwap = linkCompute(COMPUTE_SWAP_SRC)

        ssboCodes     = glGenBuffers()
        ssboGrid      = glGenBuffers()
        ssboBestJ     = glGenBuffers()
        ssboBestDelta = glGenBuffers()
        ssboPairs     = glGenBuffers()
        ssboSim       = glGenBuffers()
    }

    /** Загружаем неизменяемые данные (коды + матрица S выделяется, но заполняется отдельно). */
    private fun uploadStatic() {
        // codes (uint words)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssboCodes)
        val bytes = codesPacked.size * 4L
        glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_STATIC_DRAW)
        run {
            val bb = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, bytes, GL_MAP_WRITE_BIT)
                ?: error("glMapBufferRange(ssboCodes) returned null")
            bb.order(ByteOrder.nativeOrder())
            bb.asIntBuffer().put(codesPacked).flip()
            glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)
        }

        // grid
        val gridBytes = (gridSize * gridSize * 4L)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssboGrid)
        glBufferData(GL_SHADER_STORAGE_BUFFER, gridBytes, GL_DYNAMIC_DRAW)

        // bestJ / bestDelta
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssboBestJ)
        glBufferData(GL_SHADER_STORAGE_BUFFER, gridBytes, GL_DYNAMIC_DRAW)

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssboBestDelta)
        glBufferData(GL_SHADER_STORAGE_BUFFER, gridBytes, GL_DYNAMIC_DRAW)

        // pairs (стартовая ёмкость)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssboPairs)
        glBufferData(GL_SHADER_STORAGE_BUFFER, 8L * 1024, GL_DYNAMIC_DRAW)

        // S (n*n floats)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssboSim)
        glBufferData(GL_SHADER_STORAGE_BUFFER, n.toLong() * n * 4L, GL_STATIC_DRAW)

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
    }


    /** Один раз: посчитать S = Jaccard(c_i, c_j) на GPU. */
    private fun buildSimilarityOnce() {
        glUseProgram(progSim)
        glUniform1i(glGetUniformLocation(progSim, "uNumCodes"), n)
        glUniform1i(glGetUniformLocation(progSim, "uWordsPerCode"), wordsPerCode)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssboCodes)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, ssboSim)

        val gx = (n + 15) / 16
        val gy = (n + 15) / 16
        glDispatchCompute(gx, gy, 1)
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
    }

    private fun downloadGrid(): IntArray = downloadInts(ssboGrid, gridSize * gridSize)

    private fun occupiedCountCPU(grid: IntArray): Int = grid.count { it >= 0 }

    private fun buildNonConflictingBatch(bestJ: IntArray, bestD: FloatArray, maxPairs: Int): List<Pair<Int, Int>> {
        val used = BooleanArray(bestJ.size)
        val cand = bestJ.indices
            .asSequence()
            .filter { i -> val j = bestJ[i]; j >= 0 && bestD[i] < 0f && !used[i] && !used[j] }
            .map { i -> Triple(i, bestJ[i], bestD[i]) }
            .sortedBy { it.third }
            .toList()
        val res = ArrayList<Pair<Int, Int>>(maxPairs)
        for ((i, j, _) in cand) {
            if (res.size >= maxPairs) break
            if (used[i] || used[j]) continue
            used[i] = true; used[j] = true
            res += i to j
        }
        return res
    }

    // ---- Upload/Download helpers (минимум аллокаций) ----

    private fun uploadInts(buffer: Int, data: IntArray) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
        val need = data.size * 4L
        val cap = glGetBufferParameteri(GL_SHADER_STORAGE_BUFFER, GL_BUFFER_SIZE).toLong()
        if (cap < need) glBufferData(GL_SHADER_STORAGE_BUFFER, need, GL_DYNAMIC_DRAW)
        val bb = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, need, GL_MAP_WRITE_BIT)
            ?: error("glMapBufferRange(uploadInts) returned null")
        bb.order(ByteOrder.nativeOrder())
        bb.asIntBuffer().put(data).flip()
        glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
    }


    private fun uploadFloats(buffer: Int, data: FloatArray) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
        val need = data.size * 4L
        val cap = glGetBufferParameteri(GL_SHADER_STORAGE_BUFFER, GL_BUFFER_SIZE).toLong()
        if (cap < need) glBufferData(GL_SHADER_STORAGE_BUFFER, need, GL_DYNAMIC_DRAW)
        val bb = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, need, GL_MAP_WRITE_BIT)
            ?: error("glMapBufferRange(uploadFloats) returned null")
        bb.order(ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(data).flip()
        glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
    }

    private fun downloadInts(buffer: Int, count: Int): IntArray {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
        val size = glGetBufferParameteri(GL_SHADER_STORAGE_BUFFER, GL_BUFFER_SIZE)
        require(size >= count * 4) { "SSBO too small: size=$size, need=${count*4}" }
        val tmp = memAlloc(size).order(nativeOrder())
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, tmp)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
        val ib = tmp.asIntBuffer()
        val out = IntArray(count)
        ib.get(out, 0, min(count, ib.remaining()))
        memFree(tmp)
        return out
    }

    private fun downloadFloats(buffer: Int, count: Int): FloatArray {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
        val size = glGetBufferParameteri(GL_SHADER_STORAGE_BUFFER, GL_BUFFER_SIZE)
        require(size >= count * 4) { "SSBO too small: size=$size, need=${count*4}" }
        val tmp = memAlloc(size).order(nativeOrder())
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, tmp)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
        val fb = tmp.asFloatBuffer()
        val out = FloatArray(count)
        fb.get(out, 0, min(count, fb.remaining()))
        memFree(tmp)
        return out
    }

    // Пакуем массив 0/1 в 32-битные слова (Int как беззнаковый)
    private fun packAllCodes(codes: List<IntArray>): IntArray {
        val out = IntArray(codes.size * wordsPerCode)
        codes.forEachIndexed { idx, bits ->
            var word = 0
            var outPos = idx * wordsPerCode
            for (b in bits.indices) {
                if (bits[b] != 0) {
                    val bit = (b and 31)
                    word = word or (1 shl bit)
                }
                if ((b and 31) == 31) {
                    out[outPos++] = word
                    word = 0
                }
            }
            if ((bits.size and 31) != 0) {
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
        // --- PASS 0: Предвычисление S = Jaccard(c_i, c_j) ---
        private val COMPUTE_SIM_SRC = """
#version 430 core
layout(local_size_x = 16, local_size_y = 16) in;

layout(std430, binding = 0) readonly buffer Codes { uint words[]; };
layout(std430, binding = 5) writeonly buffer Sim   { float S[];   }; // [n*n], row-major

uniform int uNumCodes;
uniform int uWordsPerCode;

uint wordAt(int codeIdx, int w) { return words[codeIdx * uWordsPerCode + w]; }

float jaccard_ij(int i, int j){
    if (i==j) return 1.0;
    int inter = 0, uni = 0;
    for (int w=0; w<uWordsPerCode; ++w){
        uint a = wordAt(i,w);
        uint b = wordAt(j,w);
        inter += bitCount(a & b);
        uni   += bitCount(a | b);
    }
    return (uni == 0) ? 0.0 : float(inter) / float(uni);
}

void main(){
    int i = int(gl_GlobalInvocationID.x);
    int j = int(gl_GlobalInvocationID.y);
    if (i >= uNumCodes || j >= uNumCodes) return;
    int idx = i * uNumCodes + j;
    S[idx] = jaccard_ij(i,j);
}
""".trimIndent()

        // --- PASS 1: Поиск лучшего j и Δ с использованием S ---
        private val COMPUTE_BEST_USING_S_SRC = """
#version 430 core
layout(local_size_x = 256) in;

layout(std430, binding = 1) buffer Grid     { int   codeIndex[]; };   // [gridSize*gridSize], -1 = пусто
layout(std430, binding = 2) writeonly buffer BestJ    { int   bestJ[]; };
layout(std430, binding = 3) writeonly buffer BestD    { float bestDelta[]; };
layout(std430, binding = 5) readonly buffer Sim       { float S[];     };   // [n*n]

uniform int   uGridSize;
uniform int   uNumCodes;
uniform int   uRadius2;       // поиск кандидатов j
uniform int   uDeltaRadius2;  // учёт соседей r для Δ
uniform float uLambda;
uniform float uEta;
uniform float uMinSim;

float sigmoid(float x) { return 1.0 / (1.0 + exp(-x)); }
float tau(float x)     { return x * sigmoid(uEta * (x - uLambda)); }

float Sij(int a, int b){
    return S[a * uNumCodes + b];
}

void main() {
    uint cell = gl_GlobalInvocationID.x;
    int total = uGridSize * uGridSize;
    if (cell >= total) return;

    int iCode = codeIndex[cell];
    if (iCode < 0) { bestJ[cell] = -1; bestDelta[cell] = 0.0; return; }

    int iy = int(cell) / uGridSize;
    int ix = int(cell) % uGridSize;

    float best_d = 0.0;
    int   best_j = -1;

    // Ищем j в радиусе uRadius2
    for (int jCell = 0; jCell < total; ++jCell) {
        if (jCell == int(cell)) continue;
        int jCode = codeIndex[jCell];
        if (jCode < 0) continue;

        int jy = jCell / uGridSize;
        int jx = jCell % uGridSize;

        int dy = jy - iy;
        int dx = jx - ix;
        int dist2_ij = dy*dy + dx*dx;
        if (dist2_ij > uRadius2) continue;

        float baseSim = Sij(iCode, jCode);
        if (baseSim < uMinSim) continue;

        // Delta по окрестности (радиус uDeltaRadius2)
        float delta = 0.0;
        for (int rCell = 0; rCell < total; ++rCell) {
            if (rCell == int(cell) || rCell == jCell) continue;
            int rCode = codeIndex[rCell];
            if (rCode < 0) continue;

            int ry = rCell / uGridSize;
            int rx = rCell % uGridSize;

            int d1 = (ry-iy)*(ry-iy) + (rx-ix)*(rx-ix);
            int d2 = (ry-jy)*(ry-jy) + (rx-jx)*(rx-jx);

            // ограничим вклад Δ локальным кругом
            if (d1 > uDeltaRadius2 && d2 > uDeltaRadius2) continue;

            float s1 = tau(Sij(iCode, rCode));
            float s2 = tau(Sij(jCode,  rCode));
            delta += (s2 - s1) * float(d1 - d2);
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

        // --- PASS 2: Свапы пар (без изменений по сути) ---
        private val COMPUTE_SWAP_SRC = """
#version 430 core
layout(local_size_x = 256) in;

layout(std430, binding = 1) buffer Grid  { int codeIndex[]; };
layout(std430, binding = 4) readonly buffer Pairs { int pairs[]; }; // (a,b) подряд

uniform int uNumPairs;
uniform int uGridSize;

void main() {
    uint gid = gl_GlobalInvocationID.x;
    if (gid >= uint(uNumPairs)) return;
    int a = pairs[2*int(gid) + 0];
    int b = pairs[2*int(gid) + 1];
    int ia = codeIndex[a];
    int ib = codeIndex[b];
    codeIndex[a] = ib;
    codeIndex[b] = ia;
}
""".trimIndent()
    }
}
