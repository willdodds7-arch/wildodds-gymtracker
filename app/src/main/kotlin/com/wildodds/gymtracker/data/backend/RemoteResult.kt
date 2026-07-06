package com.wildodds.gymtracker.data.backend

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import java.io.IOException

/**
 * One shared outcome type for every Supabase call site (auth, sync, analytics, account lifecycle),
 * so callers pattern-match a single shape instead of catching library-specific exceptions everywhere.
 */
sealed class RemoteResult<out T> {
  data class Success<T>(val value: T) : RemoteResult<T>()
  data class Failure(val error: RemoteError) : RemoteResult<Nothing>()
}

/**
 * Coarse-grained remote failure categories. [Offline] is the one every call site must check for
 * first (Rule 1: a dropped connection never blocks a workout — it should mean "queue for later",
 * not "show an error").
 */
sealed class RemoteError {
  data object Offline : RemoteError()
  data class Unauthorized(val message: String) : RemoteError()
  data class RateLimited(val retryAfterSeconds: Int?) : RemoteError()
  data class ServerError(val statusCode: Int?, val message: String) : RemoteError()
  data class Unknown(val message: String) : RemoteError()
}

/** Runs [block], mapping any thrown exception into a [RemoteResult.Failure] instead of propagating it. */
suspend fun <T> runRemote(block: suspend () -> T): RemoteResult<T> =
  try {
    RemoteResult.Success(block())
  } catch (e: IOException) {
    RemoteResult.Failure(RemoteError.Offline)
  } catch (e: HttpRequestException) {
    RemoteResult.Failure(RemoteError.Offline)
  } catch (e: RestException) {
    RemoteResult.Failure(e.toRemoteError())
  } catch (e: Exception) {
    RemoteResult.Failure(RemoteError.Unknown(e.message ?: e::class.simpleName.orEmpty()))
  }

private fun RestException.toRemoteError(): RemoteError = when (statusCode) {
  401, 403 -> RemoteError.Unauthorized(message ?: "Unauthorized")
  429 -> RemoteError.RateLimited(null)
  in 500..599 -> RemoteError.ServerError(statusCode, message ?: "Server error")
  else -> RemoteError.Unknown(message ?: "HTTP $statusCode")
}
