# authzen-enforcer

A Java SDK that acts as a **Policy Enforcement Point (PEP)** for the
[OpenID AuthZEN Authorization API](https://openid.net/wg/authzen/). It sends AuthZEN-compliant
requests to a Policy Decision Point (PDP) and returns the decision to your application.

> **Status:** early development. There is no release yet, and APIs may change.
>
> This is an independent project. It is not affiliated with or endorsed by the OpenID Foundation.

## Goals

- **Zero runtime dependencies** in the core module. It runs on Java 11+ and uses only the JDK.
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

## Planned usage

```java
AuthZenClient client = AuthZenClient.builder()
        .pdpUrl("https://pdp.example.com")
        .credentials(() -> AccessCredential.bearer(tokenSource.get()))
        .build();

EvaluationResponse response = client.evaluate(EvaluationRequest.builder()
        .subject(Subject.of("user", "alice"))
        .resource(Resource.of("document", "123"))
        .action(Action.of("read"))
        .build());

if (response.decision()) {
    // allowed
}
```

With Spring Boot:

```java
@CheckPermission                     // action = "read", resource = {type: "document", id: <id>}
@GetMapping("/documents/{id}")
public Document getDocument(@PathVariable String id) { ... }
```

## Modules

| Artifact | Java | Description |
|---|---|---|
| `authzen-enforcer-core` | 11+ | PDP client, model, Bearer/DPoP. No dependencies. |
| `authzen-enforcer-spring-boot-starter` | 17+ | Spring Boot 3 auto-configuration and `@CheckPermission` |
| `authzen-enforcer-quarkus` | 17+ | Quarkus 3 extension |
| `authzen-enforcer-bom` | – | Version alignment |

Group ID: `io.github.authzenenforcer`

## License

Apache License 2.0
