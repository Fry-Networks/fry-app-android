package com.frynetworks.fryapp.data.dashboard.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 1 -> 2: adds the four dashboard cache tables. Purely additive — the `devices` table (and
 * every row in it) is untouched. The statements are byte-for-byte the `createSql` Room exported
 * in `app/schemas/com.frynetworks.fryapp.data.FryDatabase/2.json`, which `MigrationTest`
 * validates against the live schema on-device. Never replace this with a destructive fallback.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `remote_miners` (`minerKey` TEXT NOT NULL, `nickname` TEXT, `productName` TEXT, " +
                "`status` TEXT, `isRegistered` INTEGER NOT NULL, `virtual` INTEGER NOT NULL, `activated` INTEGER NOT NULL, " +
                "`verified` INTEGER NOT NULL, `staked` INTEGER NOT NULL, `legacyStakeUnlocked` INTEGER NOT NULL, " +
                "`byod` INTEGER NOT NULL, `node` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`minerKey`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `miner_detail_cache` (`minerKey` TEXT NOT NULL, `json` TEXT NOT NULL, " +
                "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`minerKey`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reward_summary_cache` (`minerKey` TEXT NOT NULL, `json` TEXT NOT NULL, " +
                "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`minerKey`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `asset_totals_cache` (`id` INTEGER NOT NULL, `json` TEXT NOT NULL, " +
                "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}

/** Every migration the app ships, in order, for `Room.databaseBuilder(...).addMigrations(*ALL_MIGRATIONS)`. */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
