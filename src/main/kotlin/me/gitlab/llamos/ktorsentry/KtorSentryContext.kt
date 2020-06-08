package me.gitlab.llamos.ktorsentry

import io.ktor.application.ApplicationCall
import io.sentry.context.Context

/**
 * A Sentry Context that is tied to an ApplicationCall.
 */
data class KtorSentryContext(internal val call: ApplicationCall) : Context()
