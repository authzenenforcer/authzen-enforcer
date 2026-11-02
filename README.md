# authzen-enforcer

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

The core module currently supports the Access Evaluation API with Bearer authentication and
metadata discovery.

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
`CompletableFuture` that completes the same way.

The client is thread-safe. Create one per PDP and share it.

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
no credentials. The result is kept for an hour (`metadataRefreshInterval`). If a refresh cannot reach
the PDP, the previous result stays in use. Discovery follows these rules:

- If the PDP answers `404`, it publishes no metadata, and the client uses the default paths of the
  specification below the PDP URL, such as `/access/v1/evaluation`.
- If the metadata is invalid, it is for another `policy_decision_point`, or it lists an `http`
  endpoint for an `https` PDP, the request fails (fail-closed). Other errors fail it too, and the
  next request tries again.
- `client.metadata()` returns the discovered metadata, for example to check whether the PDP supports
  the Access Evaluations API.

If a PDP publishes no metadata and serves the API at another path, set the endpoint with
`evaluationEndpoint(URI)`. `discovery(false)` turns discovery off.

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
