package ch.nutrisnap.app.di

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import ch.nutrisnap.app.data.db.HealthConnectDao
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.health.HealthConnectManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HealthConnectModule {

    @Provides
    @Singleton
    fun provideHealthConnectClient(@ApplicationContext context: Context): HealthConnectClient =
        HealthConnectClient.getOrCreate(context)

    @Provides
    @Singleton
    fun provideHealthConnectManager(client: HealthConnectClient): HealthConnectManager =
        HealthConnectManager(client)

    @Provides
    fun provideHealthConnectDao(db: NutriDatabase): HealthConnectDao =
        db.healthConnectDao()
}
