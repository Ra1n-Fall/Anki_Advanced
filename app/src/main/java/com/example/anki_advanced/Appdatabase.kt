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

// version 1→2:
// - cards 테이블에 baseInterval, lastReviewAt 추가 (completion mode 전용)
// - completion_mode_configs 테이블 신규 생성
// - 기존 learningStep은 version 1 최초 생성 시부터 포함되어 있으므로 여기서는 추가 불필요
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE cards ADD COLUMN baseInterval INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE cards ADD COLUMN lastReviewAt INTEGER NOT NULL DEFAULT 0")
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

@Database(
    entities = [
        CardEntity::class,
        DeckEntity::class,
        ReviewLogEntity::class,
        CompletionModeConfigEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun cardDao(): CardDao

    abstract fun deckDao(): DeckDao

    abstract fun reviewLogDao(): ReviewLogDao

    // completion mode 전용 DAO
    abstract fun completionModeDao(): CompletionModeDao

    abstract fun completionCardDao(): CompletionCardDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // 앱 전체에서 AppDatabase 인스턴스를 하나만 생성해 공유한다.
        // 화면(ViewModel/Activity)마다 따로 Room.databaseBuilder를 호출하지 않도록 한다.
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "anki.db"
                ).addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
