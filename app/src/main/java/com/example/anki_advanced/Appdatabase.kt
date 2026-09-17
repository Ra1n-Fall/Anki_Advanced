package com.example.anki_advanced

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.anki_advanced.completion.CompletionCardDao
import com.example.anki_advanced.completion.CompletionModeConfigEntity
import com.example.anki_advanced.completion.CompletionModeDao

// 이 앱의 SQLite 데이터베이스 전체를 정의하는 파일.
// "어떤 테이블들이 있는지" + "테이블 구조가 바뀔 때 옛 데이터를 어떻게 옮길지(마이그레이션)"를 담당.

// [문법] object : Migration(1, 2) { override fun migrate(...) { ... } }
//   Migration(1, 2)는 "버전 1 → 버전 2로 바뀔 때 실행할 규칙"이라는 뜻.
//   앱을 업데이트했는데 기존에 설치된 사용자의 DB는 옛날 버전(구조)인 경우, 이 코드가 실행되면서
//   ALTER TABLE 같은 SQL로 옛 테이블을 새 구조에 맞게 고쳐준다. 이걸 안 하면 Room이 "구조가
//   안 맞다"며 앱을 강제 종료시킨다.
//
// version 1 → 2에서 바뀐 점:
// - cards 테이블에 baseInterval, lastReviewAt 컬럼 추가 (완주 모드 계산에 필요)
// - completion_mode_configs 테이블 신규 생성 (완주 모드 설정 저장용)
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 기존 cards 테이블에 컬럼 2개를 새로 추가. DEFAULT 0으로 지정해서 기존 행들도
        // 에러 없이 값 0으로 채워진다.
        database.execSQL("ALTER TABLE cards ADD COLUMN baseInterval INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE cards ADD COLUMN lastReviewAt INTEGER NOT NULL DEFAULT 0")
        // 완주 모드 설정을 저장할 새 테이블 생성.
        // [문법] """ ... """  → 코틀린의 "여러 줄 문자열(raw string)". 줄바꿈이나 따옴표를
        //   이스케이프(\") 없이 그대로 쓸 수 있어서 긴 SQL문을 적을 때 편하다.
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS completion_mode_configs (
                deckId INTEGER NOT NULL PRIMARY KEY,
                targetPeriodMs INTEGER NOT NULL,
                windowStartHour INTEGER NOT NULL DEFAULT 0,
                windowEndHour INTEGER NOT NULL DEFAULT 24,
                modeStartAt INTEGER NOT NULL,
                sessionIntervalMs INTEGER NOT NULL DEFAULT 86400000,
                isActive INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())
    }
}

// version 2 → 3에서 바뀐 점:
// - cards 테이블에 cardType 컬럼 추가 (AI 자동 생성 카드의 콘텐츠 유형: fact/code/formula/timeline)
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE cards ADD COLUMN cardType TEXT NOT NULL DEFAULT 'fact'")
    }
}

// [문법] @Database(entities = [...], version = 2, exportSchema = false)
//   이 앱에 존재하는 모든 테이블(entities)과 현재 DB 버전을 Room에게 알려주는 선언부.
//   entities 목록에 새 Entity 클래스를 추가/삭제하면 곧 테이블이 추가/삭제되는 것과 같으므로,
//   그런 변경을 할 때는 version을 올리고 위 Migration 같은 규칙을 새로 추가해야 한다.
@Database(
    entities = [
        CardEntity::class,
        DeckEntity::class,
        ReviewLogEntity::class,
        CompletionModeConfigEntity::class
    ],
    version = 3,
    exportSchema = false
)
// [문법] abstract class X : RoomDatabase()
//   추상 클래스라서 직접 인스턴스를 못 만들고, 아래 abstract fun들도 구현부가 없다.
//   대신 Room이 컴파일할 때 이 클래스를 실제로 구현한 코드를 자동 생성해준다.
//   우리는 Room.databaseBuilder(...).build() 를 호출해서 그 "자동 생성된 구현체"를 받는 것.
abstract class AppDatabase : RoomDatabase() {

    // 아래 함수들은 "이 테이블에 접근하려면 이 DAO를 통해서 해라"는 통로 역할.
    abstract fun cardDao(): CardDao

    abstract fun deckDao(): DeckDao

    abstract fun reviewLogDao(): ReviewLogDao

    // completion mode 전용 DAO
    abstract fun completionModeDao(): CompletionModeDao

    abstract fun completionCardDao(): CompletionCardDao

    // ── 싱글턴: 앱 전체에서 AppDatabase 인스턴스를 딱 하나만 쓰게 만드는 부분 ──
    //
    // [문법] companion object { ... }
    //   클래스 이름으로 바로 접근할 수 있는 "정적(static)"에 가까운 멤버들을 모아두는 블록.
    //   AppDatabase.getInstance(...) 처럼 인스턴스를 안 만들고도 바로 호출 가능.
    companion object {
        // [문법] @Volatile
        //   여러 스레드가 동시에 이 변수를 읽고 쓸 때, "가장 최신 값"을 항상 보게 강제하는 표시.
        //   이게 없으면 스레드마다 캐시된 값을 봐서, 이미 다른 스레드가 INSTANCE를 채웠는데도
        //   내 스레드는 여전히 null로 착각할 수 있다.
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // 앱 전체에서 AppDatabase 인스턴스를 하나만 생성해 공유한다.
        // 화면(ViewModel/Activity)마다 따로 Room.databaseBuilder를 호출하지 않도록 한다.
        //
        // [문법] INSTANCE ?: synchronized(this) { INSTANCE ?: ... }  ("이중 검사 잠금")
        //   1) 먼저 락 없이 INSTANCE가 이미 있는지 본다 (있으면 바로 반환, 빠름).
        //   2) 없으면 synchronized(this)로 "한 번에 한 스레드만 들어오게" 잠근 뒤,
        //      그 안에서 다시 한 번 확인한다 — 잠그는 사이 다른 스레드가 먼저 만들었을 수도 있어서.
        //   3) 그래도 없으면 그제서야 진짜로 DB를 생성한다.
        //   결과적으로 "여러 화면이 동시에 처음 호출해도 DB 인스턴스는 딱 1개만 만들어짐"이 보장됨.
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "anki.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    // 등록 안 된 버전 변경이 발생하면(마이그레이션 규칙이 없으면)
                    // 에러를 내는 대신 기존 DB를 통째로 지우고 새로 만든다는 뜻.
                    // (개발 중엔 편하지만, 실제 서비스에선 데이터 유실 위험이 있어 주의가 필요)
                    .fallbackToDestructiveMigration()
                    .build()
                    // [문법] .also { INSTANCE = it }
                    //   build()로 막 만든 결과를 그대로 반환하면서, 동시에 그 값을 INSTANCE에도
                    //   저장해두는 문법. it은 also 블록 안에서 "방금 만든 그 값"을 가리키는 이름.
                    .also { INSTANCE = it }
            }
        }
    }
}
