import gpu.GpuDamlLayout2D_GL430
import io.ktor.utils.io.InternalAPI
import org.lwjgl.glfw.GLFW
import viz.showAnglesGrid
import java.lang.Math.toRadians
import kotlin.math.PI
import kotlin.math.floor
import kotlin.system.exitProcess
import kotlin.time.measureTime

fun main() {
    println("os.arch=" + System.getProperty("os.arch"))

    val encoder = SlidingWindowAngleEncoder(
        initialLayers = listOf(
            SlidingWindowAngleEncoder.Layer(arcLengthDegrees = 90.0,   detectorCount = 4,   overlapFraction = 0.4),
            SlidingWindowAngleEncoder.Layer(arcLengthDegrees = 45.0,   detectorCount = 8,   overlapFraction = 0.4),
            SlidingWindowAngleEncoder.Layer(arcLengthDegrees = 22.5,   detectorCount = 16,  overlapFraction = 0.4),
            SlidingWindowAngleEncoder.Layer(arcLengthDegrees = 11.25,  detectorCount = 32,  overlapFraction = 0.4),
            SlidingWindowAngleEncoder.Layer(arcLengthDegrees = 5.625,  detectorCount = 64,  overlapFraction = 0.4),
            SlidingWindowAngleEncoder.Layer(arcLengthDegrees = 2.8125, detectorCount = 128, overlapFraction = 0.4),
        ),
        initialCodeSizeInBits = 256
    )

    val codes = mutableListOf<Pair<Double, IntArray>>()
    val stepDeg = 1.0
    val steps = (360.0 / stepDeg).toInt()
    repeat(steps) { i ->
        val angleDeg = i * stepDeg
        val angleRad = toRadians(angleDeg)
        val code = encoder.encode(angleRad)
        codes += angleDeg to code
    }

    val emptyCodes: List<Pair<Double?, IntArray>> = (0..100).map { null to IntArray(256) }

    // GPU processing
    val gpuTime = measureTime {
        val gpuLayout = GpuDamlLayout2D_GL430(codes + emptyCodes)
        val outGPU =  gpuLayout.layoutLongRange(
            farRadius = 20,
            epochs = 100,
            minSim = 0.00,
            lambdaStart = 0.30,
            lambdaEnd = 0.90,
            eta = 0.0,
            maxBatchFrac = 0.30,
        )
        gpuLayout.dispose()
        showAnglesGrid(outGPU.map { it.first })
    }
    println("GPU Layout finished! Total time: $gpuTime")


    // CPU processing
    val cpuTime = measureTime {
        val layout = DampLayout2D(angleCodes = codes + emptyCodes)
        val outCPU = layout.layoutLongRange(
            farRadius = 20,
            epochs = 100,
            minSim = 0.00,
            lambdaStart = 0.30,
            lambdaEnd = 0.90,
            eta = 0.0,
            maxBatchFrac = 0.30,
            log = false
        )
        showAnglesGrid(outCPU.map { it.first })
    }
    println("CPU Layout finished! Total time: $cpuTime")



//    val backgroundCorrelationAnalyzer = BackgroundCorrelationAnalyzer()
//
//    embeddedServer(Netty, port = 8080) {
//        detectorsUiModule(
//            encoder = encoder,
//            backgroundAnalyzer = backgroundCorrelationAnalyzer
//        )
//    }.start(wait = true)
}