# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Before 1.0, a minor release may change
the API.

## [Unreleased]

### Added

- `authzen-enforcer-spring-boot-starter` for Spring Boot 4 (in development; WebFlux and the
  OAuth 2.0 client adapter follow). It configures an `AuthZenClient` and an `Enforcer` from
  `authzen.*` properties; only `authzen.pdp.url` is required, and beans of the application replace
  the defaults. A handler interceptor enforces Spring MVC handlers annotated with
  `@CheckPermission`, or all of them with `authzen.enforcer.enforce-all`, except
  `@SkipPermissionCheck` handlers and excluded route patterns. `resourceId` and `context` take SpEL
  expressions that may read only path variables, a view of the request without its credentials and
  the caller's claims; anything else fails the startup. The subject comes from the Spring Security `Authentication`
  (JWT claims, token or login attributes), or from a `ClaimsExtractor` bean. Denials go through
  Spring Security's exception handling (`401` with its challenge, `403`), or are sent as a status
  without it; an `EnforcementResultHandler` bean replaces that. Invalid annotations fail the
  startup; routes that cannot be inferred, and annotations that nothing enforces (beans that are not
  controllers, applications without Spring MVC), are logged as warnings. The PEP authenticates to the
  PDP with a `CredentialProvider` bean; callers' tokens are not forwarded.
- `EnforcementResult.refused(String)` for framework integrations that refuse a request
  themselves, for example when an expression of the endpoint fails.
- `Enforcer`: decides whether an HTTP request may proceed, synchronously or with a cancellable
  `enforceAsync(...)`. It infers the evaluation request, asks the PDP and returns an
  `EnforcementResult` with the outcome (`PERMITTED`, `DENIED`, `NOT_ENFORCED`, `UNAUTHENTICATED`,
  `REFUSED`, `FAILED`, `FAILED_OPEN`) and the HTTP status to answer with (401, 403, or 503 by
  choice). It never throws for a request it cannot decide. `EnforcementPolicy` sets the status for
  requests without a decision and an application-wide fail-open, which only covers an unavailable
  PDP (`PdpCommunicationException`) and is logged and reported.
- `PepListener.onEnforcementCompleted(...)` with the `EnforcementCompleted` event, and the
  `authzen.enforcements` timer in Micrometer, tagged by outcome.
- `EnforcerMetrics` in Micrometer: the `authzen.enforcer.listener.dropped` counter of the
  enforcement events each listener of an `Enforcer` did not get.
- Enforcement inference (`io.github.authzenenforcer.core.enforce`): `RequestInferrer` turns an
  HTTP request, described by method, route pattern, path variables and the caller's claims, into an
  evaluation request. It infers the action from the method, the resource from the route pattern
  and the subject from the claims, with a Keycloak profile. Explicit values of the endpoint win
  over `RouteOverrides`, which win over inference, and each replaces only its own part: an explicit
  resource type replaces the type, also of a collection, and the action is still inferred. Routes it cannot infer from safely, and invalid
  path variables, are denied without asking the PDP, and `check(...)` reports such routes at
  startup.
- Asynchronous credential providers: `CredentialProvider.getCredentialAsync()` and
  `CredentialProvider.async(...)`, so that a token source returning a `CompletionStage` does not
  block a thread.
- Cancellation: cancelling the stage of an `...Async` method, or the future its
  `toCompletableFuture()` returns, aborts the HTTP exchange in progress, and no retry or credential
  wait starts afterwards. Reactor's `Mono.fromCompletionStage` and Mutiny's
  `Uni.createFrom().completionStage` cancel it when their subscriber cancels, for example after a
  timeout. An interrupted blocking call and a cancelled search subscription stop the same way.
- `Outcome.CANCELLED` for calls their caller cancelled, also as the `outcome` tag in Micrometer
  and the `authzen.outcome` attribute in OpenTelemetry.

### Changed

- The failure of a timed-out exchange or credential wait is no longer completed on the JDK's
  shared delay thread, so code that follows it cannot hold up timeouts elsewhere in the JVM.
- The request timeout now also covers supplying the credential. A request whose credential does not
  arrive within it fails without being sent.

### Fixed

- Cancelling the stage of an `...Async` method threw `UnsupportedOperationException`, which
  Reactor reported through `onErrorDropped`, and the call went on. The stage can now be cancelled;
  its holder still cannot complete it.

## [0.1.0] - 2026-11-07

First release.

### Added

- `authzen-enforcer-core`, a PDP client for the OpenID AuthZEN Authorization API 1.0 that runs on
  Java 17 or newer and depends on the JDK only:
  - Access Evaluation and Access Evaluations (batch) APIs, with all three evaluation semantics.
  - Subject, Resource and Action Search APIs, page by page or over all pages as a lazy `Stream` or
    a `Flow.Publisher`, with page and result limits.
  - Synchronous calls and asynchronous calls returning a `CompletionStage`.
  - Endpoint discovery from `/.well-known/authzen-configuration`, cached according to the caching
    headers within configurable bounds, with stale use while the PDP cannot be reached and the
    default paths when the PDP publishes no metadata.
  - Bearer credentials from a `CredentialProvider`, refreshed once after a `401`.
  - Retries of transient failures with jittered backoff and `Retry-After`, and circuit breakers
    for discovery and for the Authorization API.
  - Fail-closed behavior: any failure to obtain a decision throws an `AuthZenException`.
  - `PepListener` events for calls, attempts, breaker transitions and metadata refreshes, with
    personal data masked by default, and the `PepTracer` SPI for distributed tracing.
  - A built-in JSON codec, replaceable through `JsonCodec`.
- `authzen-enforcer-micrometer`, which records the client's events as Micrometer meters.
- `authzen-enforcer-opentelemetry`, which records calls and HTTP exchanges as OpenTelemetry spans
  and propagates the W3C trace context to the PDP.
- `authzen-enforcer-bom` for aligning the versions of the modules.

[Unreleased]: https://github.com/authzenenforcer/authzen-enforcer/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/authzenenforcer/authzen-enforcer/releases/tag/v0.1.0
