# authzen-enforcer

[![CI](https://github.com/authzenenforcer/authzen-enforcer/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/authzenenforcer/authzen-enforcer/actions/workflows/ci.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=authzenenforcer_authzen-enforcer&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=authzenenforcer_authzen-enforcer)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=authzenenforcer_authzen-enforcer&metric=coverage)](https://sonarcloud.io/summary/new_code?id=authzenenforcer_authzen-enforcer)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=authzenenforcer_authzen-enforcer&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=authzenenforcer_authzen-enforcer)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=authzenenforcer_authzen-enforcer&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=authzenenforcer_authzen-enforcer)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=authzenenforcer_authzen-enforcer&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=authzenenforcer_authzen-enforcer)

A Java SDK that acts as a **Policy Enforcement Point (PEP)** for the
[OpenID AuthZEN Authorization API](https://openid.net/wg/authzen/). It sends AuthZEN-compliant
requests to a Policy Decision Point (PDP) and returns the decision to your application.

> **Status:** early development. There is no release yet, and APIs may change.
>
> This is an independent project. It is not affiliated with or endorsed by the OpenID Foundation.

## Goals

- **Zero runtime dependencies** in the core module. It runs on Java 17+ and uses only the JDK.
- **Minimal configuration.** You provide the PDP URL and a credential. Endpoints are discovered
  from `/.well-known/authzen-configuration`.
- **Full AuthZEN API surface:** Access Evaluation, Access Evaluations (batch), and the Subject,
  Resource and Action Search APIs.
- **Bearer and DPoP (RFC 9449)** authentication towards the PDP. The SDK never contacts an
  authorization server. You supply the token, plus the signing key for DPoP.
- **Fail-closed by default.** Any error results in a deny.
- **Framework integrations:** a Spring Boot starter and a Quarkus extension, both with a
  `@CheckPermission` annotation that can infer the action and resource from the HTTP method
  and the route.

## Usage

The core module currently supports the Access Evaluation, Access Evaluations and Search APIs with
Bearer authentication and metadata discovery.

```java
AuthZenClient client = AuthZenClient.builder()
        .pdpUrl("https://pdp.example.com")
        .credentials(CredentialProvider.bearer(tokenSource::currentToken))
        .build();

EvaluationResponse response = client.evaluate(EvaluationRequest.of(
        Subject.of("user", "alice"),
        Action.of("read"),
        Resource.of("document", "123")));

if (response.getDecision()) {
    // allowed
}
```

A deny is a regular response with `getDecision()` returning `false`. If no decision can be
obtained, for example because the PDP is unreachable, times out or rejects the PEP's credentials,
`evaluate` throws an `AuthZenException`. Treat it as a deny. `evaluateAsync` returns a
`CompletionStage` that completes the same way. Code you hand the stage to cannot complete it with a
made-up decision; call `toCompletableFuture()` for `join()`.

The client is thread-safe. Create one per PDP and share it.

### Several decisions in one call

`evaluations` sends an Access Evaluations (batch) request. The top-level subject, action, resource
and context are defaults; an item replaces any of them as a whole. The request is sent as built, and
the PDP applies the defaults.

```java
EvaluationsResponse response = client.evaluations(EvaluationsRequest.builder()
        .subject(Subject.of("user", "alice"))
        .action(Action.of("read"))
        .evaluation(EvaluationItem.builder().resource(Resource.of("document", "a")).build())
        .evaluation(EvaluationItem.builder().action(Action.of("update")).resource(Resource.of("document", "b")).build())
        .semantic(EvaluationsSemantic.DENY_ON_FIRST_DENY)
        .build());

if (response.allPermitted()) {
    // every item allowed
}
```

The decisions come back in request order. With `DENY_ON_FIRST_DENY` or `PERMIT_ON_FIRST_PERMIT`, the
PDP stops at the deciding item, and the items after it count as denies. `isPermitted(i)`,
`allPermitted()` and `anyPermitted()` never treat a missing decision as a permit. If the answer has
more decisions than the request had items, or fewer under `EXECUTE_ALL` (the default), the call fails
with a `PdpProtocolException`.

The endpoint comes from `access_evaluations_endpoint` in the metadata. If the PDP publishes metadata
without it, the PDP does not offer this API: the call fails without contacting it, and the
application can use `evaluate` for each item instead. Without metadata, the default path
`/access/v1/evaluations` is used, and `evaluationsEndpoint(URI)` sets the endpoint explicitly.

### Searching for subjects, resources and actions

The Search APIs ask which subjects, resources or actions the PDP permits. The entity searched for is
given by its type only, since its identifier is what the search finds.

```java
// Which accounts may Alice read?
SearchResponse<Resource> accounts = client.searchResources(ResourceSearchRequest.builder()
        .subject(Subject.of("user", "alice"))
        .action(Action.of("can_read"))
        .resourceType("account")
        .page(PageRequest.ofLimit(50))
        .build());

// Who may read account 123?
SearchResponse<Subject> readers = client.searchSubjects(
        SubjectSearchRequest.of("user", Action.of("can_read"), Resource.of("account", "123")));

// What may Alice do with account 123?
SearchResponse<Action> actions = client.searchActions(
        ActionSearchRequest.of(Subject.of("user", "alice"), Resource.of("account", "123")));
```

A response holds one page. If `getNextPageToken()` is present, ask for the next page with
`request.withPageToken(token)`, which keeps the rest of the request unchanged, as the specification
requires. A result is not an access decision: the PDP is expected to permit it in an evaluation, but
this is not guaranteed, so enforce access with `evaluate`.

`searchAllSubjects`, `searchAllResources` and `searchAllActions` walk all pages for you. They return a
lazy `Stream`: a page is fetched only once the results before it are consumed, so `limit(10)` stops
sending requests after the page that holds the tenth result. The `...Async` variants return a
`java.util.concurrent.Flow.Publisher` that fetches pages as its subscriber asks for results; Reactor
adapts it with `JdkFlowAdapter.flowPublisherToFlux(publisher)` and Mutiny with
`Multi.createFrom().publisher(publisher)`. Cancelling stops the search, but a page request that is
already sent runs until the PDP answers or the request timeout passes.

```java
try (Stream<Resource> accounts = client.searchAllResources(request)) {
    accounts.filter(account -> account.getId().startsWith("eu-")).forEach(this::show);
}
```

A PDP decides how many pages a search takes, so the client limits a search to 100 pages and 10,000
results by default. A search that goes beyond a limit, or a PDP that hands out a page token a second
time, fails with an `AuthZenException`; the results are never cut off silently. Set other limits with
`pagination(PaginationOptions)` on the builder, or for a single search with the overloads that take
`PaginationOptions`. Pagination is no snapshot: if the data changes while paging, a result can appear
twice or not at all, and the client passes the results on as the PDP returns them.

The endpoints come from `search_subject_endpoint`, `search_resource_endpoint` and
`search_action_endpoint` in the metadata. As with batch evaluations, a PDP that publishes metadata
without one of them does not offer that search, and the call fails without contacting it. Without
metadata, the default paths below `/access/v1/search/` are used; `searchSubjectEndpoint(URI)`,
`searchResourceEndpoint(URI)` and `searchActionEndpoint(URI)` set them explicitly.

### Credentials

The SDK never talks to an authorization server. Your `CredentialProvider` supplies the token before
every request, and should cache it until shortly before it expires. If the PDP answers `401`, the
client calls `invalidate()` on the provider and repeats the request once with a fresh credential.
With the client credentials grant, a provider requests a token from your authorization server,
caches it, and drops it in `invalidate()`.

### Endpoints

The client discovers the endpoints from the metadata the PDP publishes. The PDP URL is also the
PDP's identifier: for `https://pdp.example.com/tenant1`, the metadata is fetched from
`https://pdp.example.com/.well-known/authzen-configuration/tenant1`, and its
`policy_decision_point` must be exactly that URL. Keycloak, for example, serves AuthZEN below
`/authzen` in each realm, and the realm URL is all you need to configure:

```java
AuthZenClient client = AuthZenClient.builder()
        .pdpUrl("https://keycloak.example.com/realms/my-realm")
        .credentials(clientCredentialsProvider)
        .build();
```

Discovery happens on the first request, not when the client is built. The metadata request carries
no credentials. Discovery follows these rules:

- The result is kept for as long as the caching headers of the metadata response say, within
  `minTtl` (one minute) and `maxTtl` (one day): `Cache-Control: max-age`, otherwise `Expires`
  minus `Date`, minus `Age`. Without such headers it is kept for `defaultTtl` (one hour).
  `no-store`, `no-cache` and `max-age=0` keep it for `minTtl`. Keycloak sends these, so its
  metadata is fetched about once a minute.
- `no-store`, `no-cache` and `must-revalidate` forbid using expired metadata at all (RFC 9111
  section 4.2.4), and `stale-if-error=N` shortens `maxStale` (see the next rule) to `N` seconds.
  Keycloak sends these, so with Keycloak requests fail as soon as a refresh cannot reach it.
  `honorCacheHeaders(false)` ignores all caching headers, and `maxTtl` then has no effect.
- If a refresh cannot reach the PDP, gets a server error or keeps being rate-limited (`429`), the
  previous result stays in use for at most `maxStale` (one hour) after it expired. The refresh is
  tried again every `minTtl` (one minute, at least one second). After `maxStale`, requests fail until
  a refresh succeeds. Any other failure drops the previous result at once.
- If the PDP answers `404`, it publishes no metadata, and the client uses the default paths of the
  specification below the PDP URL, such as `/access/v1/evaluation`. A `404` that replaces published
  metadata is kept only for `minTtl`, since it may come from a proxy during a rollout.
- If the metadata is invalid, it is for another `policy_decision_point`, or it lists an `http`
  endpoint for an `https` PDP, the request fails (fail-closed). Other errors fail it too, and the
  next request tries again.
- `client.metadata()` returns the discovered metadata, for example to check whether the PDP supports
  the Access Evaluations API.

The durations are set with `DiscoveryOptions`:

```java
AuthZenClient client = AuthZenClient.builder()
        .pdpUrl("https://pdp.example.com")
        .discovery(DiscoveryOptions.builder()
                .defaultTtl(Duration.ofMinutes(30))
                .maxStale(Duration.ofMinutes(15))
                .build())
        .build();
```

If a PDP publishes no metadata and serves the API at another path, set the endpoint with
`evaluationEndpoint(URI)`. `discovery(false)` turns discovery off.

### When the PDP keeps failing

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
  still used within `maxStale` while the breaker is open.

```java
AuthZenClient client = AuthZenClient.builder()
        .pdpUrl("https://pdp.example.com")
        .circuitBreaker(CircuitBreakerOptions.builder()
                .failureThreshold(5)
                .maxOpenDuration(Duration.ofMinutes(1))
                .build())
        .build();
```

`CircuitBreakerOptions.disabled()` turns the breakers off.

### Listening to events

A `PepListener` receives events for metrics, tracing and audit trails: every completed call
(`CallCompleted`), every HTTP exchange including retries (`AttemptCompleted`), circuit breaker
transitions and metadata refreshes. A failed call is reported too, even one that never reached
the PDP, so an audit trail also sees the denies the client decided on its own.

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
  its result, on a separate thread, one at a time and in order. Use `listenerExecutor(...)` to
  pick the executor, for example `Executors.newVirtualThreadPerTaskExecutor()` on Java 21 or later.
- Each listener has its own bounded queue (1024 events by default). A listener that cannot keep up
  loses events rather than holding memory: first `AttemptCompleted` events, from 80% on.
- Personal data is masked by default, as in `toString()`: the subject id and the values of all
  properties and of the context become `***`. `eventContent(EventContent.defaults().include(...))`
  shows more, and `exclude(...)` masks more. Credentials and tokens are never part of an event.

### Trying it locally

There is no release yet. To use the SDK in another project, install it into your local Maven
repository with `mvn install` and depend on the snapshot:

```xml
<dependency>
    <groupId>io.github.authzenenforcer</groupId>
    <artifactId>authzen-enforcer-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Planned

Framework integrations will add a `@CheckPermission` annotation, for example with Spring Boot:

```java
@CheckPermission                     // action = "read", resource = {type: "document", id: <id>}
@GetMapping("/documents/{id}")
public Document getDocument(@PathVariable String id) { ... }
```

## Modules

| Artifact | Java | Status | Description |
|---|---|---|---|
| `authzen-enforcer-core` | 17+ | In development | PDP client, model, Bearer. No dependencies. DPoP is planned. |
| `authzen-enforcer-spring-boot-starter` | 17+ | Planned | Spring Boot 3 auto-configuration and `@CheckPermission` |
| `authzen-enforcer-quarkus` | 17+ | Planned | Quarkus 3 extension |
| `authzen-enforcer-bom` | – | In development | Version alignment |

Group ID: `io.github.authzenenforcer`

## License

Apache License 2.0
