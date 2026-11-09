# Resilience

Whatever goes wrong, the client either returns the PDP's decision or throws an `AuthZenException`,
which the application treats as a deny. The settings below decide how long it waits and how hard it
tries before giving up.

## Timeouts

| Setting | Default | Covers |
|---|---|---|
| `connectTimeout(Duration)` | 5 seconds | Establishing a connection. |
| `requestTimeout(Duration)` | 5 seconds | One request, from asking the credential provider for the credential until the complete response body is received. |

When a request times out, whatever follows, such as retries, listeners and the continuations of
the caller's stage, runs on the default asynchronous pool of `CompletableFuture`, not on the single
thread the JDK shares for every timeout in the JVM: the common `ForkJoinPool`, or a new thread per
task where the common pool has only one thread, as on machines with one or two processors. Keep
blocking work out of such continuations, or move it to an executor of your own with
`thenApplyAsync(..., executor)` and similar methods.

To configure TLS client certificates or a proxy, pass your own `java.net.http.HttpClient` with
`httpClient(...)`. It must not follow redirects, so that credentials cannot be sent to another host,
and it cannot be combined with `connectTimeout(...)`: set the connect timeout on the HTTP client
instead.

## Retries

AuthZEN requests have no side effects, so repeating them is safe. By default a request is repeated
up to three times; `maxRetries(int)` sets a value between 0 and 10, and `0` disables retries.

Only failures that are likely to be transient are retried:

- connection failures, such as a refused connection, a connect timeout or a connection closed by
  the server;
- HTTP `429`, `502`, `503` and `504`.

Request timeouts are not retried, since that would multiply the latency of a slow PDP. Neither are
TLS errors, other statuses or invalid responses, which would fail the same way again.

Retries wait an exponentially growing, randomized delay of at most two seconds. A `Retry-After`
header on `429` or `503` is honoured; if it asks for more than two seconds, the request is not
retried. No retry starts once the request timeout has passed since the first attempt, which bounds
the total latency.

Every attempt carries a fresh `X-Request-ID`. The single repetition after a `401`, with a fresh
credential, does not count as a retry; see [Credentials](credentials.md#writing-a-provider).

## Circuit breakers

The client keeps one circuit breaker for discovery and one for the Authorization API, which
evaluations, batch evaluations and searches share. After three failures in a row, a breaker opens.
Requests then fail at once with a `PdpCommunicationException` (deny), without contacting the PDP,
and the exception's cause is the last real failure. After about five seconds, one request goes
through as a probe. If it succeeds, the breaker closes. If it fails, the breaker opens again for
twice as long, up to five minutes. Each open period is randomized between half and all of its
length, so that many clients do not probe a recovering PDP at once.

- For the Authorization API, only an unreachable PDP, a timeout, a `5xx` or a lasting `429` counts.
  Any other answer, such as `400` or `403`, shows that the PDP is up.
- For discovery, every failure counts, including invalid metadata. Previously discovered metadata is
  still used within `maxStale` while the breaker is open; see
  [Endpoints and discovery](endpoints-and-discovery.md#how-discovery-works).

A call that has used up its retries counts as one failure.

```java
AuthZenClient client = AuthZenClient.builder()
        .pdpUrl("https://pdp.example.com")
        .circuitBreaker(CircuitBreakerOptions.builder()
                .failureThreshold(5)
                .maxOpenDuration(Duration.ofMinutes(1))
                .build())
        .build();
```

`CircuitBreakerOptions.disabled()` turns the breakers off. Breaker transitions are reported to
listeners and as metrics; see [Observability](observability.md).

## Cancellation

A caller that gives up stops the call. Cancelling the stage that an `...Async` method returns, or
the future its `toCompletableFuture()` returns, aborts the HTTP exchange in progress; neither a
retry nor a wait for a credential starts afterwards. A thread interrupted while it waits in a
blocking method cancels its call the same way, and cancelling the subscription of a search
publisher cancels the page request in flight.

Reactive libraries cancel the stage when their subscriber cancels, for example after a timeout of
the caller's:

```java
Mono.fromCompletionStage(client.evaluateAsync(request))
        .timeout(Duration.ofMillis(300));

Uni.createFrom().completionStage(client.evaluateAsync(request))
        .ifNoItem().after(Duration.ofMillis(300)).fail();
```

So a PDP that is already struggling gets no further retries for callers that have left. Requests
sent before the cancel are not undone, and the PDP may still have seen them. A cancelled call does
not count as a PDP failure for the circuit breaker, and listeners see it with the outcome
`CANCELLED` once it has stopped. A cancelled stage completes with a
`java.util.concurrent.CancellationException` rather than an `AuthZenException`; treat it as a deny
as well.

A probe of a half-open breaker that its caller cancels does not settle the breaker either: the next
request becomes the probe. If callers give up sooner than the request timeout, for example with a
`Mono.timeout` shorter than it, each of them can start a probe in turn. The client still lets only
one probe through at a time, but a PDP that already received an aborted request may still process
it. Keep caller timeouts at or above the request timeout where the breaker matters, or set the
request timeout below them.

The holder of the stage cannot complete it: `complete` and similar methods throw
`UnsupportedOperationException`, so code the stage is handed to cannot make up a decision.
