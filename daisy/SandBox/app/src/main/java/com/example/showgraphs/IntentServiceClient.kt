package com.example.showgraphs

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends a (noisy) speech transcript to the backend intent service and returns the
 * first-person plan the LLM infers from it.
 *
 * Talks to `POST {baseUrl}/infer` (backend/intent_service), which forwards the
 * transcript to Claude and returns `{"plan": "..."}`. From the Android emulator
 * the host machine is reachable at 10.0.2.2, so that is the default base URL;
 * override INTENT_SERVICE_URL in local.properties for a physical device on the LAN.
 *
 * Async, OkHttp-backed; callbacks fire on the main thread. Mirrors the request
 * pattern used by [com.example.showgraphs.voice.tts.DeepgramTts].
 */
class IntentServiceClient(
    baseUrl: String,
) {
    private val inferUrl = baseUrl.trimEnd('/') + "/infer"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private var pendingCall: Call? = null

    val isConfigured: Boolean get() = inferUrl.startsWith("http")

    /**
     * POST [transcript] to /infer. Exactly one of [onPlan] (the plan text) or
     * [onError] (a user-facing message) is invoked on the main thread. Starting a
     * new request cancels any in-flight one.
     */
    fun infer(
        transcript: String,
        context: String? = null,
        onPlan: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        pendingCall?.cancel()

        val payload = JSONObject().put("text", transcript)
        if (!context.isNullOrBlank()) payload.put("context", context)
        val body = payload.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url(inferUrl)
            .post(body)
            .build()

        val call = client.newCall(request)
        pendingCall = call
        Log.i(TAG, "infer -> $inferUrl : $transcript")
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                Log.e(TAG, "infer request failed: ${e.message}", e)
                mainHandler.post { onError("I couldn't reach the assistant service.") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyText = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        Log.e(TAG, "infer HTTP ${it.code}: $bodyText")
                        mainHandler.post { onError("The assistant service had a problem.") }
                        return
                    }
                    val plan = runCatching { JSONObject(bodyText).optString("plan") }
                        .getOrDefault("")
                    if (plan.isBlank()) {
                        Log.e(TAG, "infer returned no plan: $bodyText")
                        mainHandler.post { onError("I didn't get a plan back.") }
                    } else {
                        Log.i(TAG, "plan <- $plan")
                        mainHandler.post { onPlan(plan) }
                    }
                }
            }
        })
    }

    /** Cancel any in-flight request (e.g. the session ended). */
    fun cancel() {
        pendingCall?.cancel()
        pendingCall = null
    }

    companion object {
        private const val TAG = "DAISY_INTENT"
        private val JSON = "application/json".toMediaType()
    }
}
