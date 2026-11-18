package com.wzy.replymate.core

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 本地中文 OCR：ML Kit，离线、免费，图片不出本机。 */
object Ocr {

    suspend fun recognize(ctx: Context, uri: Uri): String = suspendCancellableCoroutine { cont ->
        val image = runCatching { InputImage.fromFilePath(ctx, uri) }.getOrElse { e ->
            cont.resumeWithException(e)
            return@suspendCancellableCoroutine
        }
        val client = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        client.process(image)
            .addOnSuccessListener { result ->
                client.close()
                cont.resume(result.text)
            }
            .addOnFailureListener { e ->
                client.close()
                cont.resumeWithException(e)
            }
    }

    /** 无障碍截图/系统截屏拿到的 Bitmap 直接本地识别，不上传 */
    suspend fun recognizeBitmap(ctx: Context, bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val client = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                client.close()
                cont.resume(result.text)
            }
            .addOnFailureListener { e ->
                client.close()
                cont.resumeWithException(e)
            }
    }
}
