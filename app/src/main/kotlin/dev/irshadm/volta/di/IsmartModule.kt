package dev.irshadm.volta.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.irshadm.volta.core.ismart.MgIsmartClient
import dev.irshadm.volta.data.ismart.CAR_PREFS
import dev.irshadm.volta.data.ismart.CAR_VIN
import dev.irshadm.volta.data.ismart.SecureStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object IsmartModule {

    @Provides
    @Singleton
    fun secureStore(@ApplicationContext context: Context) = SecureStore(context)

    @Provides
    @Singleton
    fun ismartClient(@ApplicationContext context: Context, http: OkHttpClient, secure: SecureStore): MgIsmartClient {
        val carPrefs = context.getSharedPreferences(CAR_PREFS, Context.MODE_PRIVATE)
        return MgIsmartClient(
            http = http.newBuilder().readTimeout(40, TimeUnit.SECONDS).build(),
            credentials = { secure.credentials },
            sessions = secure,
            preferredVin = { carPrefs.getString(CAR_VIN, null) },
        )
    }
}
