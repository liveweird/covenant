package ch.nokillswit.plugins

import ch.nokillswit.getOpenTelemetry
import io.ktor.http.*
import io.ktor.server.application.*
import io.opentelemetry.instrumentation.api.config.IncludeExclude
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender

fun Application.configureOpenTelemetry() {
    val openTelemetry = getOpenTelemetry(serviceName = "covenant")
    // Wire the Logback OTel appender to this SDK; flushes the pre-install buffer of boot logs.
    OpenTelemetryAppender.install(openTelemetry)

    install(KtorServerTelemetry) {
        setOpenTelemetry(openTelemetry)
        // The User-Agent lands on the span as `http.request.header.user-agent`. `requestHeaders`
        // replaced the deprecated `capturedRequestHeaders(vararg String)`; the include/exclude pair
        // is the same shape the SDK's own `otel.instrumentation.http.*` properties take.
        requestHeaders(IncludeExclude.builder().setIncluded(HttpHeaders.UserAgent).build())
        // No spanKindExtractor: these are HTTP SERVER spans and the plugin's default says so.
        // (The Ktor scaffold's template maps POST→PRODUCER/else→CLIENT, which mislabels
        // every span for trace consumers — deliberately removed.)
        attributesExtractor {
            onStart {
                attributes.put("start-time", System.currentTimeMillis())
            }
            onEnd {
                attributes.put("end-time", System.currentTimeMillis())
            }
        }
    }
}
