package com.phdrive.companion.data.remote

class SessionExpiredException(message: String = "Sessão Supabase expirada ou não autenticada") : Exception(message)
