package com.magizhchi.mobile.data

import com.magizhchi.mobile.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton
    fun json() = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Provides @Singleton
    fun client(store: TokenStore): OkHttpClient {
        val auth = Interceptor { chain ->
            val req = chain.request().newBuilder().apply {
                store.token?.let { addHeader("Authorization", "Bearer $it") }
            }.build()
            chain.proceed(req)
        }
        val log = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        return OkHttpClient.Builder().addInterceptor(auth).addInterceptor(log).build()
    }

    @Provides @Singleton
    fun api(client: OkHttpClient, json: Json): Api =
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(Api::class.java)
}
