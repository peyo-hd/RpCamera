package com.arpi.rpcamera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.util.*
import kotlin.collections.ArrayList


class ImageAnalyzer(context: Context, private val listener: RecogListener) :
        ImageAnalysis.Analyzer {
    private var tfliteModel : MappedByteBuffer
    private var labelList = ArrayList<String>()
    private var outputs: Array<ByteArray>

    private lateinit var tflite: Interpreter
    private var initialized = false

    init {
        tfliteModel = FileUtil.loadMappedFile(context, "mobilenet_v1_1_0_224_quantized.tflite")
        loadLabels(context.assets.open("labels.txt"))
        outputs = Array(1) { ByteArray(labelList.size) }
    }

    private fun loadLabels(stream: InputStream) {
        val reader = BufferedReader(InputStreamReader(stream))
        var line = reader.readLine()
        while(line != null) {
            labelList.add(line)
            line = reader.readLine()
        }
    }

    private fun tfInit() {
        if (initialized) return

        val options = Interpreter.Options()
        options.setNumThreads(2) // CPU
        //options.addDelegate(GpuDelegate()) // GPU
        //options.setUseNNAPI(true) // NNAPI
        tflite = Interpreter(tfliteModel, options)

        initialized = true
    }

    fun tfClose() {
        tflite.close()
        initialized = false
    }

    private var startTime: Long = 0
    private var endTime: Long = 0

    override fun analyze(proxy: ImageProxy) {
        val bitmap = getBitmap(proxy)
        if (bitmap == null) {
            proxy.close()
        } else {
            tfInit()
            convertBitmapToByteBuffer(bitmap)

            startTime = SystemClock.uptimeMillis()
            tflite.run(imgData, outputs)
            endTime = SystemClock.uptimeMillis()

            proxy.close()
            listener(getResult())
            Thread.sleep(500)
        }
    }

    private val RESULTS_TO_SHOW = 3
    private val sortedLabels = PriorityQueue<Map.Entry<String, Byte>>(RESULTS_TO_SHOW)
    { o1, o2 -> o1.value.compareTo(o2.value) }

    private fun getResult(): String {
        for (i in 0 until labelList.size) {
            sortedLabels.add(AbstractMap.SimpleEntry(labelList[i], outputs[0][i]))
            if (sortedLabels.size > RESULTS_TO_SHOW) {
                sortedLabels.poll()
            }
        }

        var text = ""
        for (i in 0 until sortedLabels.size) {
            val label = sortedLabels.poll()
            text = String.format("\n   %s: %d", label.key, label.value) + text
        }
        text = "Recognition: " + (endTime - startTime) + " msec" + text
        return text
    }


    private var imgData: ByteBuffer? = null
    private val intValues = IntArray(224 * 224)
    private val IMAGE_MEAN = 128.0f
    private val IMAGE_STD = 128.0f

    private fun convertBitmapToByteBuffer(bitmap: Bitmap) {
        if (imgData == null) {
            imgData = ByteBuffer.allocateDirect(
                    1 * 224 * 224 * 3)
            imgData!!.order(ByteOrder.nativeOrder())
        }
        imgData!!.rewind()
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until 224) {
            for (j in 0 until 224) {
                val v: Int = intValues.get(pixel++)
                imgData!!.put((v shr 16 and 0xFF).toByte())
                imgData!!.put((v shr 8 and 0xFF).toByte())
                imgData!!.put((v and 0xFF).toByte())
            }
        }
    }

    private val yuvToRgbConverter = YuvToRgbConverter(context)
    private lateinit var bitmapBuffer: Bitmap

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun getBitmap(imageProxy: ImageProxy): Bitmap? {
        val image = imageProxy.image ?: return null

        if (!::bitmapBuffer.isInitialized) {
            bitmapBuffer = Bitmap.createBitmap(
                    640, 480, Bitmap.Config.ARGB_8888
            )
        }
        yuvToRgbConverter.yuvToRgb(image, bitmapBuffer)

        val scale = Matrix()
        scale.setScale(224/480f, 224/480f)
        return Bitmap.createBitmap(bitmapBuffer, 80, 0, 480, 480, scale, false)
    }
}