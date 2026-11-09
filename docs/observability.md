# Observability

The client reports what it does through listeners. Two optional modules build on them: one
records Micrometer metrics, the other OpenTelemetry traces.

## Listening to events

A `PepListener` receives events for metrics, tracing and audit trails: every completed call
(`CallCompleted`), every HTTP exchange including retries (`AttemptCompleted`), circuit breaker
transitions and metadata refreshes. A failed call is reported too, even one that never reached
the PDP, so an audit trail also sees the denies the client decided on its own. A call its caller
cancelled is reported with the outcome `CANCELLED` once it has stopped; see
[Resilience](resilience.md#cancellation).

```java
AuthZenClient client = AuthZenClient.builder()
        .pdpUrl("https://pdp.example.com")
        .listener(new PepListener() {
            @Override
            public void onCallCompleted(CallCompleted<?, ?> event) {
                event.as(Operation.EVALUATION).ifPresent(evaluation -> audit.write(
                        evaluation.getRequestIds(),
                        evaluation.getRequest().getAction().getName(),
                        evaluation.getOutcome()));
            }
        })
        .build();
```

- A listener can neither change nor delay a decision. Events are delivered after the caller has
  its result, on a separate thread, one at a time and in order: the attempts of a call come
  before the call itself. Events of different calls, or of different metadata refreshes, are not
  ordered with each other, so an event may arrive after one that happened later; use the times the
  events carry where order matters. Use `listenerExecutor(...)` to
  pick the executor, for example `Executors.newVirtualThreadPerTaskExecutor()` on Java 21 or later.
  The client never calls that executor from a caller's thread, so even one that runs tasks inline
  cannot slow a caller down.
- Each listener has its own queue, bounded both in events (1024 by default) and in the bytes they
  retain, mostly PDP response bodies (64 MiB by default, `listenerQueueMaxBytes(...)`). A listener
  that cannot keep up loses events rather than holding memory: first `AttemptCompleted` events,
  from 80% on.
- Personal data is masked by default, as in `toString()`: the subject id and the values of all
  properties and of the context become `***`. `eventContent(EventContent.defaults().include(...))`
  shows more, and `exclude(...)` masks more. Batch options and page details (such as page tokens)
  always stay visible, so keep personal data out of them. Credentials and tokens are never part
  of an event.
- `client.listenerDrops()` tells how many events each listener lost, per event type.

## Metrics with Micrometer

`authzen-enforcer-micrometer` records the events as Micrometer meters:

```java
MicrometerPepListener metrics = MicrometerPepListener.builder(registry)
        .tag("pdp", "main")
        .build();
AuthZenClient client = AuthZenClient.builder()
        .pdpUrl("https://pdp.example.com")
        .listener(metrics)
        .build();
AuthZenClientMetrics.bind(client, registry, Tags.of("pdp", "main"));
```

| Meter | Type | Tags |
|---|---|---|
| `authzen.client.calls` | timer | `operation`, `outcome` (`permit`/`deny`/`completed`/`failed`/`cancelled`), `exception` |
| `authzen.client.attempts` | timer | `operation`, `status` (`invalid` outside 100–599), `exception`, `next` (`none`/`retry`/`refresh_credential`) |
| `authzen.client.breaker.transitions` | counter | `breaker`, `from`, `to` |
| `authzen.client.breaker.state` | gauge | `breaker`; 0 closed, 1 half-open, 2 open |
| `authzen.client.metadata.refreshes` | counter | `result` |
| `authzen.client.listener.dropped` | function counter | `listener`, `event` |

No tag carries an identifier such as a subject id, resource id or request id.
`resourceTypeTag(true)` and `actionNameTag(true)` add `resource.type` and `action.name` to
`authzen.client.calls`; turn them on only if your types and action names are few and fixed.
Clients that share a registry need one listener each, told apart by a tag such as `pdp`, and all
of them with the same tag keys and opt-in tags: Prometheus rejects a meter whose tag keys differ
from an earlier one with the same name. The
module does not bring Micrometer along: it uses the `micrometer-core` of your application, 1.10 or
newer, which a registry such as `micrometer-registry-prometheus` already provides.

## Tracing with OpenTelemetry

`authzen-enforcer-opentelemetry` records every call as a span and every HTTP exchange with the
PDP, retries included, as a child span, and sends the exchange's W3C trace context to the PDP in
`traceparent` and `tracestate`, so the PDP's own spans join the trace:

```java
AuthZenClient client = AuthZenClient.builder()
        .pdpUrl("https://pdp.example.com")
        .tracer(OpenTelemetryTracing.builder(openTelemetry).build())
        .build();
```

```
GET /documents/42                    (your server span)
└─ authzen evaluation   INTERNAL     authzen.outcome=deny
   ├─ POST              CLIENT       http.response.status_code=503
   │  └─ (PDP span)
   └─ POST              CLIENT       http.response.status_code=200, http.request.resend_count=1
      └─ (PDP span)
```

- Spans are started on the request path, which takes microseconds, and ended from the events on
  a listener thread. A failing tracer only means no trace headers; it never fails or changes a
  call.
- Only `traceparent` and `tracestate` are sent, never baggage. The metadata request is not traced.
- The pages of `searchAll*` are fetched later, on other threads; each page still goes under the
  span that was current where `searchAll*` was called.
- Spans carry no subject, resource id, property or context values, and no exception messages.
  `authzen.resource.type` and `authzen.action.name` can be turned off in the builder. `url.full`
  shows the PDP endpoint with its path, so keep secrets out of the PDP URL.
- A span whose events were dropped because the tracer could not keep up is not exported;
  `client.listenerDrops()` counts such drops.
- `PepTracer` is the SPI behind it, for other tracing systems. Every listener can read the spans
  from `CallCompleted.getCallSpan()`, for example to put the trace id in an audit record.
- The module uses the `opentelemetry-api` of your application, 1.19 or newer.
