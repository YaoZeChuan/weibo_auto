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
import cn.vove7.weibo.auto.data.entity.ReportOutboxItem
import cn.vove7.weibo.auto.data.dao.ReportOutboxDao

@Database(
    entities = [
        WeiboAccount::class,
        TaskRecord::class,
        PostTemplate::class,
        CommentTemplate::class,
        TaskExecutionLog::class,
        ReportOutboxItem::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun taskRecordDao(): TaskRecordDao
    abstract fun taskExecutionLogDao(): TaskExecutionLogDao
    abstract fun postTemplateDao(): PostTemplateDao
    abstract fun commentTemplateDao(): CommentTemplateDao
    abstract fun reportOutboxDao(): ReportOutboxDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "weibo_auto.db",
                ).addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
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

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `weibo_accounts` ADD COLUMN `dailyTaskDayStart` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `weibo_accounts` ADD COLUMN `dailyCheckInStatus` TEXT NOT NULL DEFAULT 'UNKNOWN'")
                database.execSQL("ALTER TABLE `weibo_accounts` ADD COLUMN `dailyBrowseCompletedCount` INTEGER NOT NULL DEFAULT -1")
                database.execSQL("ALTER TABLE `weibo_accounts` ADD COLUMN `dailyBrowseRequiredCount` INTEGER NOT NULL DEFAULT -1")
                database.execSQL("ALTER TABLE `weibo_accounts` ADD COLUMN `dailyCommentCompletedCount` INTEGER NOT NULL DEFAULT -1")
                database.execSQL("ALTER TABLE `weibo_accounts` ADD COLUMN `dailyCommentRequiredCount` INTEGER NOT NULL DEFAULT -1")
                database.execSQL("ALTER TABLE `weibo_accounts` ADD COLUMN `dailyRepostCompletedCount` INTEGER NOT NULL DEFAULT -1")
                database.execSQL("ALTER TABLE `weibo_accounts` ADD COLUMN `dailyRepostRequiredCount` INTEGER NOT NULL DEFAULT -1")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `weibo_accounts` ADD COLUMN `dailyWaterPostDayStart` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `weibo_accounts` ADD COLUMN `dailyWaterPostCompletedCount` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `report_outbox` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`type` TEXT NOT NULL, `payload` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `attemptCount` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, `lastError` TEXT, `uploadedAt` INTEGER)"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_report_outbox_state` ON `report_outbox` (`state`)")
            }
        }
    }
}
