package me.gitlab.llamos.ktorsentry

import io.ktor.util.KtorExperimentalAPI
import io.sentry.DefaultSentryClientFactory
import io.sentry.context.ContextManager
import io.sentry.dsn.Dsn

/**
 * Creates SentryClient instances that use the KtorSentryContextManager.
 */
@KtorExperimentalAPI
class KtorSentryClientFactory(private val sentryFeature: KtorSentry) : DefaultSentryClientFactory() {

    /**
     * Returns a new KtorSentryContextManager using the KtorSentry instance provided to the constructor.
     */
    override fun getContextManager(dsn: Dsn?): ContextManager {
        return KtorSentryContextManager(sentryFeature)
    }
}
