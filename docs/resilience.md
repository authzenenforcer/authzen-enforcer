# Resilience

Whatever goes wrong, the client either returns the PDP's decision or throws an `AuthZenException`,
which the application treats as a deny. The settings below decide how long it waits and how hard it
tries before giving up.

## Timeouts

| Setting | Default | Covers |
|---|---|---|
| `connectTimeout(Duration)` | 5 seconds | Establishing a connection. |
| `requestTimeout(Duration)` | 5 seconds | One request, from asking the credential provider for the credential until the complete response body is received. |

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
