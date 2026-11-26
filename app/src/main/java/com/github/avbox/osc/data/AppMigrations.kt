package com.github.avbox.osc.data

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

private const val VodFollowCreateSql = "CREATE TABLE IF NOT EXISTS `vodFollow` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `vodId` TEXT, `sourceKey` TEXT, `cid` TEXT, `name` TEXT, `pic` TEXT, `addedTime` INTEGER NOT NULL, `updateDays` TEXT NOT NULL, `updateHour` INTEGER NOT NULL)"

val MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(VodFollowCreateSql)
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `vodFollow` ADD COLUMN `tmdbInfo` TEXT NOT NULL DEFAULT ''")
    }
}

private const val DownloadTaskCreateSql = "CREATE TABLE IF NOT EXISTS `download_task` (" +
    "`id` TEXT NOT NULL, " +
    "`sourceKey` TEXT, " +
    "`vodId` TEXT, " +
    "`vodName` TEXT, " +
    "`playFlag` TEXT, " +
    "`episodeIndex` INTEGER NOT NULL, " +
    "`episodeName` TEXT, " +
    "`rawSeriesUrl` TEXT, " +
    "`url` TEXT NOT NULL, " +
    "`headersJson` TEXT NOT NULL, " +
    "`kind` TEXT NOT NULL, " +
    "`fileName` TEXT NOT NULL, " +
    "`dirHint` TEXT, " +
    "`totalBytes` INTEGER NOT NULL, " +
    "`downloadedBytes` INTEGER NOT NULL, " +
    "`segmentsDone` INTEGER NOT NULL, " +
    "`segmentsTotal` INTEGER NOT NULL, " +
    "`status` TEXT NOT NULL, " +
    "`error` TEXT, " +
    "`errorKind` TEXT, " +
    "`etag` TEXT, " +
    "`lastModified` TEXT, " +
    "`contentUri` TEXT, " +
    "`createdAt` INTEGER NOT NULL, " +
    "`updatedAt` INTEGER NOT NULL, " +
    "`completedAt` INTEGER NOT NULL, " +
    "PRIMARY KEY(`id`))"

val MIGRATION_3_4 = object : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(DownloadTaskCreateSql)
    }
}
