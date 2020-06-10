package me.gitlab.llamos.ktorsentry

import io.ktor.application.Application
import io.ktor.application.ApplicationCall
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.ApplicationFeature
import io.ktor.application.call
import io.ktor.application.featureOrNull
import io.ktor.application.log
import io.ktor.config.ApplicationConfig
import io.ktor.features.callId
import io.ktor.http.formUrlEncode
import io.ktor.request.path
import io.ktor.util.AttributeKey
import io.ktor.util.KtorExperimentalAPI
import io.ktor.util.pipeline.PipelineContext
import io.ktor.util.pipeline.PipelinePhase
import io.sentry.Sentry
import io.sentry.SentryClient
import org.slf4j.LoggerFactory

/**
 * This Application pipeline feature for Ktor provides a Sentry client
 * which determines the current context by the active ApplicationCall instance.
 * In order to use the provided ApplicationCall.sentry,
 * this class must be installed to the pipeline first via `install(KtorSentry)`.
 *
 * Configuration of this class can be provided via the standard pipeline feature configuration,
 * or through the pipeline configuration file in the `sentry` namespace.
 * Please reference either the source, or the project README for more details.
 *
 * In order to obtain the active ApplicationCall during context resolution,
 * the static Sentry API cannot be used.
 * Instead, please use ApplicationCall.sentry to obtain instances of SentryClient.
 */

@KtorExperimentalAPI
class KtorSentry(config: Configuration) {

    internal val logger = LoggerFactory.getLogger(this::class.java)

    private val ktorSentryInitPhase = PipelinePhase("KtorSentryInit")
    private val ktorSentryCleanupPhase = PipelinePhase("KtorSentryCleanup")
    private val ktorSentryContextAttributeKey = AttributeKey<KtorSentryContext>("KtorSentryContext")
    internal val sentryClient: SentryClient

    // flow for obtaining context is dependent on non-suspending calls to SentryClient.getContext().
    // Since Sentry actions are non-suspending,
    // the ThreadLocal instance is preserved between the storing in ApplicationCall#sentry (at the bottom of this file)
    // and the lookup in KtorSentryContext#getContext()
    internal val threadLocalStore: ThreadLocal<KtorSentryContext> = ThreadLocal()

    // config options
    private val autoCallId: Boolean
    private val autoRequestPath: Boolean

    init {
        if (config.dsn.isNullOrBlank())
            throw IllegalArgumentException("The Sentry DSN must be provided to the KtorSentry configuration or via the SENTRY_DSN environment variable.")

        val configMap = mapOf(
            "release" to config.release,
            "dist" to config.dist,
            "environment" to config.sentryEnvironment,
            "servername" to config.servername,
            "tags" to config.tags?.toList()?.joinToString(",") { "${it.first}:${it.second}" },
            "mdctags" to config.mdcTags?.toList()?.joinToString(","),
            "extra" to config.extra?.toList()?.joinToString(",") { "${it.first}:${it.second}" },
            "stacktrace.app.packages" to config.stackTraceAppPackages?.joinToString(","),
            "stacktrace.hidecommon" to config.stackTraceHideCommon?.toString(),
            "sample.rate" to config.sampleRate?.toString(),
            "uncaught.handler.enabled" to config.uncaughtHandlerEnabled?.toString(),
            "buffer.dir" to config.bufferDir,
            "buffer.size" to config.bufferSize?.toString(),
            "buffer.flushtime" to config.bufferFlushTime?.toString(),
            "buffer.shutdowntimeout" to config.bufferShutdownTimeout?.toString(),
            "buffer.gracefulshutdown" to config.bufferGracefulShutdown?.toString(),
            "async" to config.async?.toString(),
            "async.shutdowntimeout" to config.asyncShutdownTimeout?.toString(),
            "async.gracefulshutdown" to config.asyncGracefulShutdown?.toString(),
            "async.queuesize" to config.asyncQueueSize?.toString(),
            "async.threads" to config.asyncThreads?.toString(),
            "async.priority" to config.asyncPriority?.toString(),
            "compression" to config.compression?.toString(),
            "maxmessagelength" to config.maxMessageLength?.toString(),
            "timeout" to config.timeout?.toString(),
            "http.proxy.host" to config.httpProxyHost,
            "http.proxy.port" to config.httpProxyPort?.toString()
        )

        // other options could go here
        val params = configMap.toList().formUrlEncode()
        logger.debug("Created DSN parameters: $params")

        // create context-sensitive client
        val factory = KtorSentryClientFactory(this)
        val dsn = "${config.dsn}?$params"
        sentryClient = factory.createClient(dsn)

        // also create the static instance if requested
        if (config.initStaticSentryClient)
            Sentry.init(dsn)

        // also parse ktor-specific settings
        autoCallId = config.autoCallId
        autoRequestPath = config.autoRequestPath
    }

    class Configuration(config: ApplicationConfig) {
        // standard dsn
        var dsn: String? = config.propertyOrNull("sentry.dsn")?.getString() ?: System.getenv("SENTRY_DSN")

        // Sentry options
        var release: String? = config.propertyOrNull("sentry.environment.release")?.getString()
        var dist: String? = config.propertyOrNull("sentry.environment.dist")?.getString()
        var sentryEnvironment: String? = config.propertyOrNull("sentry.environment.name")?.getString()
        var servername: String? = config.propertyOrNull("sentry.environment.server")?.getString()
        var tags: Map<String, String>? = config.propertyOrNull("sentry.tags")?.getList()?.map { it.split(":")[0] to it.split(":")[1] }?.toMap()
        var mdcTags: List<String>? = config.propertyOrNull("sentry.mdctags")?.getList()
        var extra: Map<String, String>? = config.propertyOrNull("sentry.extra")?.getList()?.map { it.split(":")[0] to it.split(":")[1] }?.toMap()
        var stackTraceAppPackages: List<String>? = config.propertyOrNull("sentry.stacktrace.packages")?.getList()
        var stackTraceHideCommon: Boolean? = config.propertyOrNull("sentry.stacktrace.hidecommon")?.getString()?.toBoolean()
        var sampleRate: Float? = config.propertyOrNull("sentry.sample.rate")?.getString()?.toFloat()
        var uncaughtHandlerEnabled: Boolean? = config.propertyOrNull("sentry.uncaught.handler.enabled")?.getString()?.toBoolean()
        var bufferDir: String? = config.propertyOrNull("sentry.buffer.dir")?.getString()
        var bufferSize: Int? = config.propertyOrNull("sentry.buffer.size")?.getString()?.toInt()
        var bufferFlushTime: Long? = config.propertyOrNull("sentry.buffer.flushtime")?.getString()?.toLong()
        var bufferShutdownTimeout: Long? = config.propertyOrNull("sentry.buffer.shutdowntimeout")?.getString()?.toLong()
        var bufferGracefulShutdown: Boolean? = config.propertyOrNull("sentry.buffer.gracefulshutdown")?.getString()?.toBoolean()
        var async: Boolean? = config.propertyOrNull("sentry.async.enabled")?.getString()?.toBoolean()
        var asyncShutdownTimeout: Long? = config.propertyOrNull("sentry.async.shutdowntimeout")?.getString()?.toLong()
        var asyncGracefulShutdown: Boolean? = config.propertyOrNull("sentry.async.gracefulshutdown")?.getString()?.toBoolean()
        var asyncQueueSize: Int? = config.propertyOrNull("sentry.async.queuesize")?.getString()?.toInt()
        var asyncThreads: Int? = config.propertyOrNull("sentry.async.threads")?.getString()?.toInt()
        var asyncPriority: Long? = config.propertyOrNull("sentry.async.priority")?.getString()?.toLong()
        var compression: Boolean? = config.propertyOrNull("sentry.compression")?.getString()?.toBoolean()
        var maxMessageLength: Long? = config.propertyOrNull("sentry.maxmessagelength")?.getString()?.toLong()
        var timeout: Long? = config.propertyOrNull("sentry.timeout")?.getString()?.toLong()
        var httpProxyHost: String? = config.propertyOrNull("sentry.proxy.host")?.getString()
        var httpProxyPort: Int? = config.propertyOrNull("sentry.proxy.port")?.getString()?.toInt()

        // KtorSentry options
        var initStaticSentryClient = config.propertyOrNull("sentry.ktor.initstatic")?.getString()?.toBoolean() ?: false
        var autoCallId = config.propertyOrNull("sentry.ktor.extra.callid")?.getString()?.toBoolean() ?: true
        var autoRequestPath = config.propertyOrNull("sentry.ktor.extra.path")?.getString()?.toBoolean() ?: true
    }

    companion object Feature : ApplicationFeature<Application, Configuration, KtorSentry> {

        override val key: AttributeKey<KtorSentry> = AttributeKey("KtorSentry")

        override fun install(pipeline: Application, configure: Configuration.() -> Unit): KtorSentry {
            // providing the pipeline configuration to the Configuration object allows application config-file use
            val config = Configuration(pipeline.environment.config).apply(configure)
            val feature = KtorSentry(config)

            // create context before monitoring phase (in case a logging feature logs to sentry)
            pipeline.insertPhaseBefore(ApplicationCallPipeline.Monitoring, feature.ktorSentryInitPhase)
            pipeline.intercept(feature.ktorSentryInitPhase) { feature.createContext(call) }

            // destroy context after fallback phase (in case fallback uses sentry logging)
            pipeline.insertPhaseAfter(ApplicationCallPipeline.Fallback, feature.ktorSentryCleanupPhase)
            pipeline.intercept(feature.ktorSentryCleanupPhase) { feature.destroyContext(call) }

            return feature
        }
    }

    private fun ApplicationCall.callIdLogText() = if (callId != null) "for call id [$callId]" else ""

    private fun createContext(call: ApplicationCall) = call.apply {
        // create blank context
        val context = KtorSentryContext(this)

        // add CallId
        if (autoCallId && callId != null)
            context.addExtra("KtorCallId", callId)

        // add call request path
        if (autoRequestPath)
            context.addExtra("KtorRequestPath", request.path())

        // store in call attributes
        attributes.put(ktorSentryContextAttributeKey, context)
        logger.trace("Created KtorSentryContext ${callIdLogText()}")
    }

    private fun destroyContext(call: ApplicationCall) = call.apply {
        // remove from call attributes, no destruction needed
        attributes.remove(ktorSentryContextAttributeKey)
        logger.trace("Destroyed KtorSentryContext ${callIdLogText()}")
    }

    internal fun getContext(call: ApplicationCall): KtorSentryContext {
        return call.attributes[ktorSentryContextAttributeKey]
    }

    internal fun resetContext(context: KtorSentryContext) = context.call.apply {
        logger.debug("Context reset requested ${callIdLogText()}")
        destroyContext(this)
        createContext(this)
    }
}

@KtorExperimentalAPI
@Suppress("unused")
val PipelineContext<Unit, ApplicationCall>.sentry: SentryClient
    get() {
        // ensure that KtorSentry is installed on the pipeline
        val feature = call.application.featureOrNull(KtorSentry)
        if (feature == null) {
            call.application.log.warn("Attempted to use context-sensitive Sentry client, but KtorSentry is not installed on the pipeline")
            return Sentry.getStoredClient()
        }

        // place the current context in the threadlocal storage (see threadLocalStore definition for more info)
        feature.logger.trace("Stored KtorSentryContext in ThreadLocal storage for call with ID [${call.callId}]")
        feature.threadLocalStore.set(feature.getContext(call))
        return feature.sentryClient
    }
