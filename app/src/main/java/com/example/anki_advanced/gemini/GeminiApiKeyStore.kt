package com.example.anki_advanced.gemini

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// Gemini API 키를 기기에 안전하게 저장/조회하는 저장소.
// [문법] EncryptedSharedPreferences
//   평범한 SharedPreferences와 쓰는 방법(get/put)은 똑같지만, 실제로 디스크에 저장되는
//   파일 내용 자체가 암호화된다. 루팅된 기기 등에서 파일을 직접 열어봐도 평문 키가 안 보인다.
class GeminiApiKeyStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            "gemini_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // 저장된 키가 없거나 빈 문자열이면 null을 반환.
    fun getApiKey(): String? = prefs.getString(KEY_API_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun saveApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    companion object {
        private const val KEY_API_KEY = "gemini_api_key"
    }
}
