KtorSentry is a pipeline feature for the Ktor HTTP server framework.
It provides ApplicationCall-based contexts, and some utilities based on common Ktor logging.

Installation
---

KtorSentry is hosted within the Maven repository of this GitLab project.
You can add it to your project via the following settings for gradle,
or the equivalent options in your build tool of choice:
```

```

Usage
---

KtorSentry needs to be installed to the pipeline in order to function:
```kotlin
// within Application.module
install(KtorSentry)
```

Once installed, the feature exposes the SentryClient as a KProperty on PipelineContext,
and can be accessed within any `PipelineContext<Unit, ApplicationCall>` as just `sentry`.
Be sure to import the property with `import me.gitlab.llamos.ktorsentry.sentry`.

Example usage:
```kotlin
import me.gitlab.llamos.ktorsentry.sentry
// ...
get("/") {
    sentry.context.recordBreadcrumb(BreadcrumbBuilder().setMessage("User called /").build())
    try {
        unsafeFunction()
    } catch (e: Exception) {
        sentry.sendException(e)
    }
}
```

**Important**: Due to the nature of suspend functions, 
using the default Thread-based context switching is not suitable for Ktor applications.
Therefore, the static Sentry API cannot be used for ApplicationCall-based contexts.
However, you may have a need for the static API for tasks that do not have an ApplicationCall associated with them.
Using the static Sentry API will fallback to the default implementation,
so be sure to configure it with a DSN and other options as outlined in 
[the official documentation](https://docs.sentry.io/clients/java/config/).
If you instead wish for the Sentry client to be initialized with the same options as the KtorSentry instance,
without the ApplicationCall-based contexts,
this can be done by providing the `sentry.ktor.initstatic=true` configuration option.
This is disabled by default.

Configuration
---

As a Ktor application, KtorSentry has access to your project-wide configuration.
It can be entirely configured via your `application.conf`.
Here is an example containing the basic settings:

```hocon
sentry {
  dsn = "https://long_dsn_key.ingest.sentry.io/project_id"
  stacktrace {
    packages = [ "me.gitlab.llamos.ktorsentry" ]
  }
  environment {
    name = "dev"
    name = "${?SENTRY_ENV}"
    server = ${?HOSTNAME}
  }
}
```

Alternatively, KtorSentry can be configured by the Ktor Pipeline Feature configuration style.
Note the usage of `sentryEnvironment` instead of `environment` 
to prevent namespace collision with the pipeline environment:
```kotlin
// within Application.module
install(KtorSentry) {
    dsn = "https://long_dsn_key.ingest.sentry.io/project_id"
    stackTraceAppPackages = listOf("me.gitlab.llamos.ktorsentry")
    sentryEnvironment = "dev"
}
```

Although most settings follow the same naming convention as the ones laid out in
[the official Sentry documentation](https://docs.sentry.io/clients/java/config/),
there are a few changes to both the HOCON format, and the installation config format.
See the source within `src/main/kotlin/me/gitlab/llamos/ktorsentry/KtorSentry.kt` for more details.

KtorSentry Tagging
---

KtorSentry also provides some default tagging options.
These can be enabled or disabled within the `sentry.ktor.tags` and `sentry.ktor.extra` configuration sections.
See the source within `src/main/kotlin/me/gitlab/llamos/ktorsentry/KtorSentry.kt` for more configuration details.

* CallId: Adds the `KtorCallId` extra to all contexts, if `CallId` installed on the pipeline. Enabled by default.
* RequestPath: Adds the `KtorRequestPath` extra to all contexts. Enabled by default.
* More to come in later versions, submit a merge request if you have ideas!
