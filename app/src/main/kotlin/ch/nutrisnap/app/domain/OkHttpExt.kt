package ch.nutrisnap.app.domain

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cancellable Ersatz für das blockierende [Call.execute].
 *
 * Blockierendes execute() ignoriert Coroutine-Cancel und withTimeout: abgebrochene Race-
 * Verlierer hielten ihren coroutineScope bis zum OkHttp-Timeout (bis ~28 s) offen, und
 * die Stufen-Budgets (withTimeoutOrNull) griffen nicht. Hier bricht ein Cancel den Call
 * sofort ab. [read] läuft auf dem OkHttp-Thread und muss den Body selbst konsumieren.
 */
suspend fun <T> Call.await(read: (Response) -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use(read) }
            if (!cont.isActive) return
            result.fold(
                onSuccess = { cont.resume(it) },
                onFailure = { cont.resumeWithException(it) }
            )
        }
    })
}
