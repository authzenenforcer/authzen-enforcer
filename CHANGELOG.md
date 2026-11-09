# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Before 1.0, a minor release may change
the API.

## [Unreleased]

### Added

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
