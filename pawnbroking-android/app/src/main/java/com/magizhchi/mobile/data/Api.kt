package com.magizhchi.mobile.data

import kotlinx.serialization.Serializable
import retrofit2.http.*

// Legacy password login — still in API for backwards-compat but app uses OTP.
@Serializable data class LoginReq(val shop_id: String, val username: String, val password: String)

// OTP flow — what the backend actually uses for end users.
@Serializable data class SendOtpReq(val email: String, val shop_id: String? = null)
@Serializable data class SendOtpResp(val message: String = "Verification code sent.")
@Serializable data class VerifyOtpReq(val email: String, val code: String, val shop_id: String? = null)

@Serializable data class LoginResp(
    val access_token: String? = null,   // null for multi-shop response (selector mode)
    val shop_id: String? = null,
    val user_id: Long? = null,
    val role: String? = null,
    val email: String? = null,
    val display_name: String? = null,
    val shops_count: Int? = null,
    val selector_token: String? = null, // present when user has 2+ shops
    val shops: List<ShopOption>? = null
)

@Serializable data class ShopOption(val shop_id: String, val label: String)

@Serializable data class SelectShopReq(val shop_id: String)

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
    // Legacy password login (still works but UI no longer uses it).
    @POST("/v1/auth/mobile") suspend fun login(@Body req: LoginReq): LoginResp

    // OTP-based login.
    @POST("/v1/auth/box/send-otp") suspend fun sendOtp(@Body req: SendOtpReq): SendOtpResp
    @POST("/v1/auth/box/verify")   suspend fun verifyOtp(@Body req: VerifyOtpReq): LoginResp

    // Multi-shop selector → access token exchange.
    @POST("/v1/auth/box/select-shop")
    suspend fun selectShop(
        @retrofit2.http.Header("Authorization") bearer: String,
        @Body req: SelectShopReq
    ): LoginResp

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
