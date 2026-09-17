package com.example.anki_advanced.gemini

// 카메라 촬영/갤러리 선택으로 얻은 이미지를 Gemini에 보낼 수 있는 형태로 다듬는 유틸.
// - Uri 하나만 달랑 오므로, 실제 픽셀 데이터를 읽고 크기를 줄여 업로드 용량을 낮춘다.
// - 카메라는 결과를 저장할 목적지 Uri가 미리 있어야 해서, FileProvider로 임시 파일 Uri를 만들어준다.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

private const val MAX_DIMENSION = 1024
private const val JPEG_QUALITY = 85

// 이미지 Uri를 읽어서 (미리보기용 Bitmap, Gemini 전송용 JPEG 바이트) 쌍으로 반환.
// 원본이 너무 크면 requestSize 계산으로 한 번에 다운샘플링해서 메모리 낭비를 막는다.
fun decodeAndCompressImage(context: Context, uri: Uri): Pair<Bitmap, ByteArray>? {
    val resolver = context.contentResolver

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null

    val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
        ?: return null

    val bytes = ByteArrayOutputStream().use { stream ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        stream.toByteArray()
    }
    return bitmap to bytes
}

private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var sampleSize = 1
    while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

// 카메라 앱이 촬영 결과를 저장할 임시 파일의 content:// Uri를 만들어준다.
// AndroidManifest에 등록된 FileProvider(권한: "<packageName>.fileprovider")를 사용한다.
fun createCameraCaptureUri(context: Context): Uri {
    val imagesDir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
    val file = File(imagesDir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
