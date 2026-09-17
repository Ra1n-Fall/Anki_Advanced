package com.example.anki_advanced.gemini

// 카메라 촬영/갤러리 선택으로 얻은 이미지를 Gemini에 보낼 수 있는 형태로 다듬는 유틸 함수 모음.
// - 안드로이드에서 "사진 하나 선택"의 결과는 실제 픽셀 데이터가 아니라 Uri(위치를 가리키는
//   주소값) 하나뿐이라서, 그 주소를 열어 진짜 이미지 바이트를 읽어오는 과정이 필요하다.
// - 원본 사진은 보통 수 MB~수십 MB(4000x3000 같은 해상도)라서, 그대로 Base64 인코딩해서
//   네트워크로 보내면 느리고 요청 크기 제한에 걸릴 수 있다. 그래서 여기서 한 번 크기를
//   줄이고(다운샘플링) JPEG로 압축한 뒤에야 Gemini 요청에 실어 보낸다.
// - 카메라는 특이하게 "사진을 어디에 저장할지" 목적지 Uri를 먼저 우리가 만들어서
//   카메라 앱에 넘겨줘야 하는 구조라서, 그 목적지를 만드는 함수도 여기 같이 둔다.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

// 긴 변 기준으로 이 픽셀 수를 넘지 않도록 축소한다. 플래시카드 생성에 쓰일 텍스트/도표를
// Gemini가 읽는 데는 이 정도 해상도면 충분하고, 그 이상은 용량만 늘릴 뿐 품질 이득이 적다.
private const val MAX_DIMENSION = 1024

// JPEG 압축 품질(0~100). 85는 육안으로는 원본과 거의 구분 안 되면서 용량은 꽤 줄어드는,
// 흔히 쓰이는 "적당히 보수적인" 값.
private const val JPEG_QUALITY = 85

// 이미지 Uri를 읽어서 (화면 미리보기용 Bitmap, Gemini 전송용 JPEG 압축 바이트) 쌍으로 반환한다.
// 파일을 못 읽는 등 문제가 있으면 null을 반환한다.
//
// [문법] BitmapFactory.Options().apply { inJustDecodeBounds = true }
//   실제 픽셀 데이터를 메모리에 올리지 않고 "가로/세로 크기(outWidth/outHeight)만" 알아내는
//   1차 디코딩. 만약 이 단계 없이 원본을 그대로 디코딩부터 하면, 큰 사진 한 장이 순간적으로
//   수십 MB 메모리를 잡아먹다가 OutOfMemoryError로 이어질 수 있다 — "일단 크기만 확인 →
//   얼마나 줄일지 계산 → 그 배율로 축소하며 디코딩"의 2단계 접근이 안드로이드의 표준 패턴이다.
fun decodeAndCompressImage(context: Context, uri: Uri): Pair<Bitmap, ByteArray>? {
    val resolver = context.contentResolver

    // 1단계: 크기만 확인 (실제 픽셀은 아직 메모리에 안 올라옴)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // [문법] resolver.openInputStream(uri)?.use { ... } ?: return null
    //   Uri가 가리키는 파일을 InputStream으로 여는데, 애초에 열 수 없으면(파일이 없거나
    //   권한이 없으면) null이 오므로 그 자리에서 함수 전체를 중단(return null)한다.
    //   use { }는 스트림을 다 쓰고 나면 자동으로 close()해주는 확장 함수.
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null

    // 2단계: 1단계에서 알아낸 원본 크기를 보고 "몇 배로 축소해서 디코딩할지" 계산.
    val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    // 이번엔 inJustDecodeBounds가 없으므로(기본값 false) 실제로 축소된 크기의 픽셀 데이터를
    // 진짜로 디코딩해서 Bitmap 객체를 만든다. 스트림은 1단계에서 이미 다 읽어서 소모됐으므로
    // 같은 Uri를 다시 openInputStream()으로 새로 열어야 한다(스트림 재사용 불가).
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
        ?: return null

    // 3단계: 축소된 Bitmap을 JPEG 바이트로 압축 — 이게 실제로 Gemini에 보낼 데이터가 된다.
    // [문법] ByteArrayOutputStream().use { stream -> ... }
    //   메모리 상의 바이트 배열에 쓰는 스트림. 파일이 아니라 네트워크 요청 본문에 바로
    //   실을 바이트가 필요할 때 흔히 쓰는 패턴.
    val bytes = ByteArrayOutputStream().use { stream ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        stream.toByteArray()
    }
    // [문법] bitmap to bytes
    //   Pair(bitmap, bytes)를 만드는 짧은 표기. 호출한 쪽(ViewModel.onImagePicked)에서
    //   val (bitmap, bytes) = decodeAndCompressImage(...) 처럼 구조 분해로 받는다.
    return bitmap to bytes
}

// "가로/세로가 각각 maxDimension을 넘지 않을 때까지 몇 배(2의 거듭제곱)로 줄여야 하는지" 계산.
// [문법] BitmapFactory.Options.inSampleSize
//   2배로 하면 가로/세로 각각 절반(즉 픽셀 수는 1/4)로 줄어드는 안드로이드의 디코딩 옵션.
//   반드시 2의 거듭제곱(1, 2, 4, 8...)을 넣어야 최적화된 방식으로 동작한다고 공식 문서가
//   권장해서, 여기서도 sampleSize를 1에서 시작해 조건을 만족할 때까지 계속 두 배씩 늘린다.
private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var sampleSize = 1
    while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

// 카메라 앱이 "촬영한 사진을 저장할 위치"로 쓸 임시 파일의 content:// Uri를 만들어준다.
//
// 왜 이런 함수가 필요한가: 안드로이드에서 카메라를 띄워 사진을 찍는 표준 방법
// (ActivityResultContracts.TakePicture())은 "결과를 어디에 저장할지"를 우리가 미리
// 정해서 넘겨줘야 하는 구조다. 그런데 API 24 이상에서는 다른 앱(카메라 앱)에게
// file:// 경로를 직접 노출하는 게 보안상 금지돼 있어서(FileUriExposedException),
// 대신 FileProvider가 우리 캐시 폴더의 실제 파일을 감싸는 "안전한 content:// 주소"를
// 대신 발급해준다. 카메라 앱은 이 content:// 주소로만 파일에 접근해서 사진을 써넣는다.
//
// [문법] FileProvider.getUriForFile(context, authority, file)
//   authority는 AndroidManifest.xml의 <provider>에 등록해둔 것과 반드시 일치해야 하며
//   (이 프로젝트에서는 "${applicationId}.fileprovider"), 그 설정에 연결된
//   res/xml/file_paths.xml이 "어느 폴더까지 노출을 허용할지"를 정의한다.
fun createCameraCaptureUri(context: Context): Uri {
    val imagesDir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
    val file = File(imagesDir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
