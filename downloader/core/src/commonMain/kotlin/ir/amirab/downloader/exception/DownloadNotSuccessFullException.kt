package ir.amirab.downloader.exception

import java.io.IOException

/** [retryAfterSeconds] is the server's Retry-After header in seconds, when it sent one. */
class UnSuccessfulResponseException(
    val code: Int,
    val msg: String,
    val retryAfterSeconds: Long? = null,
) : IOException(
    "$code | $msg"
)
