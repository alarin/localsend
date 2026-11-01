package org.localsend.localsend_app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel

/// Payload extracted from a direct-share intent, waiting for the Dart side to fetch it.
class DirectSharePayload(
    val fingerprint: String?,
    val text: String?,
    val files: List<Map<String, Any>>,
)

/// Holds the state shared between the transparent activity, the foreground service
/// and the headless Flutter engine.
object DirectShareHost {
    const val CHANNEL = "org.localsend.localsend_app/direct_share"
    const val EXTRA_FINGERPRINT = "org.localsend.localsend_app.extra.FINGERPRINT"

    var engine: FlutterEngine? = null
    var pendingPayload: DirectSharePayload? = null
    var service: DirectShareService? = null

    fun attachEngine(engine: FlutterEngine) {
        this.engine = engine
        MethodChannel(engine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getPayload" -> {
                    val payload = pendingPayload
                    if (payload == null) {
                        result.error("NO_PAYLOAD", "No share payload available", null)
                    } else {
                        result.success(
                            mapOf(
                                "fingerprint" to payload.fingerprint,
                                "text" to payload.text,
                                "files" to payload.files,
                            )
                        )
                    }
                }

                "updateNotification" -> {
                    service?.updateNotification(
                        title = call.argument<String>("title") ?: "LocalSend",
                        text = call.argument<String>("text") ?: "",
                    )
                    result.success(null)
                }

                "done" -> {
                    val success = call.argument<Boolean>("success") ?: false
                    val message = call.argument<String>("message")
                    service?.finishWithResult(success, message)
                    result.success(null)
                }

                else -> result.notImplemented()
            }
        }
    }

    fun detachEngine(engine: FlutterEngine) {
        if (this.engine === engine) {
            this.engine = null
        }
    }
}

/// Invisible share target for direct share (per-device shortcuts in the system share sheet).
/// It extracts the shared content, spins up a headless Flutter engine that performs the
/// actual send, and finishes immediately - no UI ever becomes visible.
class DirectShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fingerprint = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
            ?: intent.getStringExtra(DirectShareHost.EXTRA_FINGERPRINT)

        DirectShareHost.pendingPayload = DirectSharePayload(
            fingerprint = fingerprint,
            text = intent.getStringExtra(Intent.EXTRA_TEXT),
            files = extractFiles(intent),
        )

        // Keeps the process alive for the duration of the transfer. Started while this
        // activity is in the foreground, which Android 12+ requires.
        val serviceIntent = Intent(this, DirectShareService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        var engine = DirectShareHost.engine
        if (engine == null) {
            engine = FlutterEngine(applicationContext)
            DirectShareHost.attachEngine(engine)
            engine.dartExecutor.executeDartEntrypoint(
                DartExecutor.DartEntrypoint(
                    FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                    "package:localsend_app/direct_share_main.dart",
                    "directShareMain",
                )
            )
        }

        finish()
    }

    private fun extractFiles(intent: Intent): List<Map<String, Any>> {
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) uris.add(uri)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (list != null) uris.addAll(list)
            }
        }

        return uris.map { uri ->
            val (name, size) = queryNameAndSize(uri)
            mapOf(
                "uri" to uri.toString(),
                "name" to name,
                "size" to size,
            )
        }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size: Long = -1
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {
        }
        if (name == null) {
            name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        }
        return name to if (size >= 0) size else 0
    }
}
