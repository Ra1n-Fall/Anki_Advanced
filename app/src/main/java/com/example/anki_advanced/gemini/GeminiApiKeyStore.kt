package com.example.anki_advanced.gemini

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// Gemini API 키를 기기에 안전하게 저장/조회하는 저장소.
//
// 왜 그냥 SharedPreferences가 아니라 암호화 버전을 쓰는가:
//   API 키는 이 계정의 Gemini 사용량 전체를 대신 쓸 수 있는 비밀번호나 마찬가지라서,
//   평범한 SharedPreferences처럼 /data/data/<패키지>/shared_prefs/*.xml에 평문으로 남으면
//   루팅된 기기나 백업 파일 유출 시 그대로 털린다. EncryptedSharedPreferences는 그 파일
//   내용 자체를 암호화해서 저장하므로, 파일을 직접 열어봐도 키 값이 안 보인다.
//
// [문법] EncryptedSharedPreferences
//   androidx.security 라이브러리가 제공하는 SharedPreferences 구현체.
//   getString()/edit().putString() 같은 사용법은 일반 SharedPreferences와 완전히 동일해서,
//   "암호화된 저장소"라는 사실을 몰라도 코드는 그냥 평범한 Preferences처럼 짤 수 있다.
//   내부적으로 MasterKey(기기의 Android Keystore에 보관되는 진짜 암호화 키)를 이용해
//   읽고 쓸 때마다 자동으로 암/복호화를 해준다.
class GeminiApiKeyStore(context: Context) {

    // [문법] context.applicationContext
    //   생성자로 받은 context가 Activity처럼 "화면이 끝나면 사라지는" 대상일 수도 있어서,
    //   그걸 그대로 들고 있으면 액티비티가 소멸된 뒤에도 참조가 남아 메모리 누수가 날 수 있다.
    //   applicationContext는 앱이 실행되는 동안 항상 살아있는 Context라서, 이렇게 한 번
    //   바꿔서 저장해두면 이 클래스가 오래 살아남아도(예: 싱글턴처럼 계속 재사용돼도) 안전하다.
    private val appContext = context.applicationContext

    // [문법] by lazy { ... }
    //   이 프로퍼티(prefs)를 실제로 "처음 사용하는 시점"에 딱 한 번만 블록 안의 코드를 실행해서
    //   값을 만들고, 그 다음부터는 캐시된 값을 그대로 재사용하는 위임 프로퍼티.
    //   MasterKey 생성 + EncryptedSharedPreferences.create()는 약간 비용이 드는 작업이라,
    //   GeminiApiKeyStore 객체를 만들자마자 바로 실행하지 않고 "진짜 필요할 때"로 미뤄둔다.
    private val prefs: SharedPreferences by lazy {
        // MasterKey: 실제 암호화에 쓰이는 키. AES256_GCM 방식으로 생성해서
        // Android Keystore(하드웨어 보안 영역)에 저장되게 한다 — 앱 코드에서 이 키 값 자체를
        // 직접 볼 수는 없고, 안드로이드 시스템이 대신 암/복호화 연산만 해준다.
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        // "gemini_secure_prefs"라는 이름의 파일을 암호화 버전으로 만든다.
        // PrefKeyEncryptionScheme: 저장되는 키 이름(예: "gemini_api_key")까지도 암호화.
        // PrefValueEncryptionScheme: 실제 값(API 키 문자열)을 암호화.
        // 즉 파일을 열어봐도 어떤 키에 어떤 값이 들었는지 전혀 알아볼 수 없다.
        EncryptedSharedPreferences.create(
            appContext,
            "gemini_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // 저장된 키를 읽어온다. 저장된 적이 없거나(null), 공백만 저장돼 있으면(예: 사용자가
    // 실수로 스페이스바만 눌렀던 경우) 둘 다 "키가 없다"로 취급해서 null을 반환한다.
    // [문법] ?.trim()?.takeIf { ... }
    //   getString()이 null이면 뒤 체인 전체가 그냥 null로 끝난다(세이프 콜 ?.).
    //   trim()으로 앞뒤 공백을 지운 다음, takeIf로 "조건(비어있지 않음)을 만족할 때만"
    //   그 값을 통과시키고, 조건을 안 만족하면 역시 null이 된다.
    fun getApiKey(): String? = prefs.getString(KEY_API_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    // API 키를 저장(덮어쓰기)한다.
    // [문법] prefs.edit().putString(...).apply()
    //   SharedPreferences는 직접 값을 바꿀 수 없고, edit()으로 "수정 작업 묶음"을 하나 만든 뒤
    //   원하는 만큼 put*()을 호출하고 마지막에 apply()(또는 commit())를 불러야 실제로 반영된다.
    //   apply()는 디스크 쓰기를 백그라운드에서 비동기로 처리해서 호출한 쪽을 안 막는다.
    fun saveApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    // 저장된 키를 삭제한다. "API 키 변경" 버튼을 누르면 이 함수가 불려서
    // hasApiKey 상태가 다시 false가 되고, 화면이 키 등록 화면으로 돌아간다.
    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    // [문법] companion object { private const val ... }
    //   인스턴스마다 새로 만들 필요 없는 상수를 클래스 레벨에 딱 하나만 두는 방법.
    //   "gemini_api_key"라는 문자열을 여러 함수에서 반복해서 타이핑하면 오타 위험이 있으니,
    //   이름 붙은 상수 하나로 통일해서 씀.
    companion object {
        private const val KEY_API_KEY = "gemini_api_key"
    }
}
