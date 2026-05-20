package com.magizhchi.mobile.data

import kotlinx.serialization.Serializable
import retrofit2.http.*

@Serializable data class LoginReq(val shop_id: String, val username: String, val password: String)
@Serializable data class LoginResp(val access_token: String, val shop_id: String, val user_id: Long, val role: String)

@Serializable data class DeviceReq(val user_id: Long, val fcm_token: String, val device_label: String)

@Serializable data class Dashboard(
    val shop_id: String,
    val todays_bills: Long = 0,
    val total_customers: Long = 0,
    val advance_total: Double = 0.0
)

@Serializable data class Row(
    val row_pk: String? = null,
    val payload: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    val last_updated_at: String? = null
)

@Serializable data class NotificationItem(
    val notif_id: Long,
    val event_id: String,
    val title: String,
    val body: String,
    val table_name: String,
    val row_pk: String? = null,
    val created_at: String
)

interface Api {
    @POST("/v1/auth/mobile") suspend fun login(@Body req: LoginReq): LoginResp
    @POST("/v1/devices")     suspend fun registerDevice(@Body req: DeviceReq): Map<String, Boolean>
    @GET("/v1/data/dashboard") suspend fun dashboard(): Dashboard
    @GET("/v1/data/{table}") suspend fun list(
        @Path("table") table: String,
        @Query("q") q: String? = null,
        @Query("limit") limit: Int = 100
    ): List<Row>
    @GET("/v1/data/{table}/{rowPk}")
    suspend fun one(@Path("table") table: String, @Path("rowPk") rowPk: String): Row
    @GET("/v1/data/notifications") suspend fun notifications(@Query("limit") limit: Int = 50): List<NotificationItem>
}
