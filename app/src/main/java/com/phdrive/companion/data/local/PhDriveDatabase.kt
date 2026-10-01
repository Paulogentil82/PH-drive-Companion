package com.phdrive.companion.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.phdrive.companion.model.LocalTrip
import com.phdrive.companion.model.LocalTripPoint
import com.phdrive.companion.model.Vehicle
import com.phdrive.companion.model.VehicleBluetoothBinding

@Database(
    entities = [
        LocalTrip::class,
        LocalTripPoint::class,
        Vehicle::class,
        VehicleBluetoothBinding::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class PhDriveDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao
    abstract fun tripPointDao(): TripPointDao
    abstract fun vehicleDao(): VehicleDao
    abstract fun vehicleBluetoothBindingDao(): VehicleBluetoothBindingDao

    companion object {
        @Volatile
        private var instance: PhDriveDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vehicles ADD COLUMN brand TEXT")
                db.execSQL("ALTER TABLE vehicles ADD COLUMN version TEXT")
                db.execSQL("ALTER TABLE vehicles ADD COLUMN year INTEGER")
                db.execSQL("ALTER TABLE vehicles ADD COLUMN fuelType TEXT")
                db.execSQL("ALTER TABLE vehicles ADD COLUMN type TEXT")
                db.execSQL("ALTER TABLE vehicles ADD COLUMN active INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `vehicle_bluetooth_bindings` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `userId` TEXT NOT NULL,
                        `vehicleId` TEXT NOT NULL,
                        `bluetoothDeviceName` TEXT NOT NULL,
                        `bluetoothDeviceAddressHash` TEXT NOT NULL,
                        `bluetoothDeviceAddressMasked` TEXT NOT NULL,
                        `bluetoothDeviceAddress` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `active` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_vehicle_bluetooth_bindings_vehicleId` ON `vehicle_bluetooth_bindings` (`vehicleId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_vehicle_bluetooth_bindings_bluetoothDeviceAddressHash` ON `vehicle_bluetooth_bindings` (`bluetoothDeviceAddressHash`)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vehicles ADD COLUMN isAutoAutomationEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trips ADD COLUMN startMode TEXT NOT NULL DEFAULT 'MANUAL'")
            }
        }
        
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Do nothing: fuel_entries table exists if migrating from 4->5, 
                // but we remove it from entities to stop accessing it.
            }
        }

        fun getInstance(context: Context): PhDriveDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhDriveDatabase::class.java,
                    "phdrive_companion.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }

        fun setInstanceForTesting(db: PhDriveDatabase?) {
            instance = db
        }
    }
}

