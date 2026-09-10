package app.batstats.battery.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@TypeConverters(EnumConverters::class)
@Database(
    entities = [BatterySample::class, ChargeSession::class, AlarmRule::class, AppEnergyStat::class],
    version = 4,
    exportSchema = true
)
abstract class BatteryDatabase : RoomDatabase() {
    abstract fun batteryDao(): BatteryDao
    abstract fun sessionDao(): SessionDao
    abstract fun alarmDao(): AlarmDao
    abstract fun appEnergyDao(): AppEnergyDao

    companion object {
        @Volatile private var INSTANCE: BatteryDatabase? = null

        /** Adds the flag that separates auto-tracked charge sessions from manual ones. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE charge_sessions ADD COLUMN autoStarted INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Drops the per-app energy history.
         *
         * Every row in it was written by a parser that matched all `pwi` rows rather than
         * only the per-app ones, so the device-wide component totals - screen, cpu, cell -
         * were charged to whichever package owns uid 0. There is no way to separate that
         * back out after the fact, and the App Drain screen would present it as fact, so it
         * starts again from the first correct poll. Only this table is affected; samples and
         * sessions are untouched.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM app_energy_stats")
            }
        }

        fun get(context: Context): BatteryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BatteryDatabase::class.java,
                    "battery.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    // A blanket destructive fallback meant every future schema change would
                    // silently wipe months of history on update. Real migrations from here
                    // on; only v1, which no migration was ever written for and which the
                    // v2 bump already wiped, still falls back.
                    .fallbackToDestructiveMigrationFrom(true, 1)
                    .build().also { INSTANCE = it }
            }
    }
}

class EnumConverters {
    @TypeConverter fun fromSessionType(t: SessionType?): String? = t?.name
    @TypeConverter fun toSessionType(s: String?): SessionType? = s?.let { enumValueOf<SessionType>(it) }

    @TypeConverter fun fromAlarmType(t: AlarmType?): String? = t?.name
    @TypeConverter fun toAlarmType(s: String?): AlarmType? = s?.let { enumValueOf<AlarmType>(it) }
}
