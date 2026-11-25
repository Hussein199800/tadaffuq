package com.tadaffuq.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun status(s: TaskStatus): String = s.name
    @TypeConverter fun status(s: String): TaskStatus = TaskStatus.valueOf(s)
    @TypeConverter fun net(n: NetKind): String = n.name
    @TypeConverter fun net(n: String): NetKind = NetKind.valueOf(n)
    @TypeConverter fun tstate(s: TorrentRunState): String = s.name
    @TypeConverter fun tstate(s: String): TorrentRunState = TorrentRunState.valueOf(s)
}

@Database(
    entities = [TaskEntity::class, SegmentEntity::class, UsageEntity::class, PackageEntity::class, SavedLinkEntity::class, BookmarkEntity::class, HistoryEntity::class, TorrentEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    abstract fun usage(): UsageDao
    abstract fun links(): LinkDao
    abstract fun browser(): BrowserDao
    abstract fun torrents(): TorrentDao

    companion object {
        const val NAME = "tadaffuq.db"
        const val VERSION = 3

        // لا fallbackToDestructiveMigration أبداً: سجل المستخدم لا يُحذف مع التحديثات.
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME).build()
    }
}
