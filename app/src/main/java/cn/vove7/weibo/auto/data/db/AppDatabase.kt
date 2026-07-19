package cn.vove7.weibo.auto.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.vove7.weibo.auto.data.dao.AccountDao
import cn.vove7.weibo.auto.data.dao.CommentTemplateDao
import cn.vove7.weibo.auto.data.dao.PostTemplateDao
import cn.vove7.weibo.auto.data.dao.TaskRecordDao
import cn.vove7.weibo.auto.data.dao.TaskExecutionLogDao
import cn.vove7.weibo.auto.data.entity.CommentTemplate
import cn.vove7.weibo.auto.data.entity.PostTemplate
import cn.vove7.weibo.auto.data.entity.TaskRecord
import cn.vove7.weibo.auto.data.entity.TaskExecutionLog
import cn.vove7.weibo.auto.data.entity.WeiboAccount

@Database(
    entities = [
        WeiboAccount::class,
        TaskRecord::class,
        PostTemplate::class,
        CommentTemplate::class,
        TaskExecutionLog::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun taskRecordDao(): TaskRecordDao
    abstract fun taskExecutionLogDao(): TaskExecutionLogDao
    abstract fun postTemplateDao(): PostTemplateDao
    abstract fun commentTemplateDao(): CommentTemplateDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "weibo_auto.db",
                ).addMigrations(MIGRATION_5_6)
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { instance = it }
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_execution_logs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`completedAt` INTEGER, " +
                        "`accountsSummary` TEXT NOT NULL, " +
                        "`tasksSummary` TEXT NOT NULL, " +
                        "`result` TEXT NOT NULL, " +
                        "`detail` TEXT)"
                )
            }
        }
    }
}
