package com.example.anki_advanced

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// [변경] 마이그레이션 추가
// 기존: version 1, 마이그레이션 없음
// 변경: version 2, learningStep 컬럼 추가
// 이유: 기존 DB 데이터를 유지하면서 새 컬럼 추가
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE cards ADD COLUMN learningStep INTEGER NOT NULL DEFAULT 0"
        )
    }
}

@Database(
    entities = [CardEntity::class, DeckEntity::class, ReviewLogEntity::class],
    // cards 테이블이 필요하다.
    // cards 테이블 구조는 CardEntity에 정의되어 있다.
    // 이 DB는 CardEntity 테이블을 포함한다고 Room에게 알려준다.

    version = 1,

    exportSchema = false
    // 스키마 기록 파일이 필요 없다.
)
abstract class AppDatabase : RoomDatabase() {
    // RoomDatabase를 상속하며, 실제 구현은 Room이 자동으로 해주기에 abstract class로 선언한다.

    abstract fun cardDao(): CardDao
    // CardEntity를 위한 Dao를 반환하는 함수(반환 타입을 보고 Room이 알아서 인식)
    // cardDao() 함수를 선언만 해 두고 구현은 Room에게 맡긴다.

    abstract fun deckDao(): DeckDao

    abstract fun reviewLogDao(): ReviewLogDao
}