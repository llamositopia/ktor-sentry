package me.gitlab.llamos.ktorsentry

import io.ktor.util.KtorExperimentalAPI
import io.sentry.context.ContextManager

/**
 * A ContextManager for Sentry that provides contexts based on Ktor ApplicationCall instances.
 * Each ApplicationCall receives its own Context instance.
 * Contexts are created during the ApplicationCall pipeline,
 * between the Setup and Monitoring phases,
 * and are destroyed after the Fallback phase.
 */

@KtorExperimentalAPI
class KtorSentryContextManager(private val sentryFeature: KtorSentry) : ContextManager {

    override fun clear() {
        sentryFeature.resetContext(context)
    }

    override fun getContext(): KtorSentryContext {
        return sentryFeature.threadLocalStore.get()
    }
}
