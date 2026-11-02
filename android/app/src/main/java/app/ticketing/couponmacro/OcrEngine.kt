package app.ticketing.couponmacro

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.util.concurrent.TimeUnit

/** ML Kit(한국어, 기기 내 처리·오프라인) 글자 인식. 워커 스레드에서만 호출할 것. */
object OcrEngine {
    private val recognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    /** 화면 비트맵 -> 줄 목록(좌표는 비트맵 픽셀 = 화면 픽셀) */
    fun read(bitmap: Bitmap): List<OcrLine> {
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 10, TimeUnit.SECONDS)
        val out = ArrayList<OcrLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val r = line.boundingBox ?: continue
                val t = line.text.trim()
                if (t.isNotEmpty()) out += OcrLine(t, r.left, r.top, r.right, r.bottom)
            }
        }
        return out
    }
}
