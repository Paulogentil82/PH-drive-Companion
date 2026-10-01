package com.phdrive.companion.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PasswordLoginRequest(
    val email: String,
    val password: String
)

typealias LoginRequestDto = PasswordLoginRequest

@Serializable
data class RefreshTokenRequestDto(
    @SerialName("refresh_token")
    val refreshToken: String
)

@Serializable
data class AuthUserDto(
    val id: String,
    val email: String? = null,
    val role: String? = null,
    val aud: String? = null
)

@Serializable
data class AuthTokenResponseDto(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("token_type")
    val tokenType: String? = null,
    @SerialName("expires_in")
    val expiresIn: Long? = null,
    @SerialName("expires_at")
    val expiresAt: Long? = null,
    val user: AuthUserDto? = null
)

@Serializable
data class AuthErrorResponseDto(
    val error: String? = null,
    @SerialName("error_description")
    val errorDescription: String? = null,
    @SerialName("error_code")
    val errorCode: String? = null,
    val msg: String? = null,
    val message: String? = null,
    val code: String? = null
)
