# Getting started

## Requirements

Java 17 or newer. The core module, `authzen-enforcer-core`, has no dependencies besides the JDK.

## Adding the dependency

There is no release yet. To use the SDK in another project, install it into your local Maven
repository with `mvn install` in this repository and depend on the snapshot:

```xml
<dependency>
    <groupId>io.github.authzenenforcer</groupId>
    <artifactId>authzen-enforcer-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## The first decision

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

The PDP URL is the only required setting. The client finds the endpoints in the metadata the PDP
publishes; see [Endpoints and discovery](endpoints-and-discovery.md). Where the token comes from is
up to you; see [Credentials](credentials.md).

## Denies and errors

A deny is a regular response with `getDecision()` returning `false`. If no decision can be
obtained, for example because the PDP is unreachable, times out or rejects the PEP's credentials,
`evaluate` throws an `AuthZenException`. Treat it as a deny.

| Exception | Meaning |
|---|---|
| `PdpCommunicationException` | The PDP could not be reached, did not answer in time, answered `5xx` or kept answering `429`, or a circuit breaker is open. |
| `PdpAuthenticationException` | The PDP answered `401` or `403`: the PEP itself is not allowed to ask. It is not an access decision about the subject. |
| `PdpProtocolException` | The PDP answered, but not as the specification requires, such as an unexpected status or a body that is not a valid response. |
| `AuthZenException` | The base type of all of these, and thrown itself for other failures, such as a credential provider that returns no credential. |

All of them are unchecked.

## Asynchronous calls

Every call has an `...Async` variant. `evaluateAsync` returns a `CompletionStage` that completes
the same way `evaluate` returns or throws. Code you hand the stage to cannot complete it with a
made-up decision; call `toCompletableFuture()` for `join()`.

## One client per PDP

The client is thread-safe and holds the HTTP connections, the discovered metadata and the circuit
breakers. Create one per PDP and share it.
