# authzen-enforcer

[![CI](https://github.com/authzenenforcer/authzen-enforcer/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/authzenenforcer/authzen-enforcer/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.authzenenforcer/authzen-enforcer-core)](https://central.sonatype.com/artifact/io.github.authzenenforcer/authzen-enforcer-core)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=authzenenforcer_authzen-enforcer&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=authzenenforcer_authzen-enforcer)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=authzenenforcer_authzen-enforcer&metric=coverage)](https://sonarcloud.io/summary/new_code?id=authzenenforcer_authzen-enforcer)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=authzenenforcer_authzen-enforcer&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=authzenenforcer_authzen-enforcer)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=authzenenforcer_authzen-enforcer&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=authzenenforcer_authzen-enforcer)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=authzenenforcer_authzen-enforcer&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=authzenenforcer_authzen-enforcer)

A Java SDK that acts as a **Policy Enforcement Point (PEP)** for the
[OpenID AuthZEN Authorization API](https://openid.net/wg/authzen/). It sends AuthZEN-compliant
requests to a Policy Decision Point (PDP) and returns the decision to your application.

> **Status:** early development. Releases before 1.0 may change the API; see the
> [changelog](CHANGELOG.md).
>
> This is an independent project. It is not affiliated with or endorsed by the OpenID Foundation.

## Goals

- **Zero runtime dependencies** in the core module. It runs on Java 17+ and uses only the JDK.
- **Minimal configuration.** You provide the PDP URL and a credential. Endpoints are discovered
  from `/.well-known/authzen-configuration`.
- **Full AuthZEN API surface:** Access Evaluation, Access Evaluations (batch), and the Subject,
  Resource and Action Search APIs.
- **Bearer and DPoP (RFC 9449)** authentication towards the PDP. The SDK never contacts an
  authorization server. You supply the token, plus the signing key for DPoP. Bearer is available;
  DPoP is planned.
- **Fail-closed by default.** Any error results in a deny.
- **Framework integrations:** a Spring Boot starter (a preview for Spring MVC) and a Quarkus
  extension (planned), both with a `@CheckPermission` annotation that can infer the action and
  resource from the HTTP method and the route. Without them, the client is used directly from
  application code.

## Quick start

The core module supports the Access Evaluation, Access Evaluations and Search APIs with Bearer
authentication and metadata discovery.

```xml
<dependency>
    <groupId>io.github.authzenenforcer</groupId>
    <artifactId>authzen-enforcer-core</artifactId>
    <version>0.2.0</version>
</dependency>
```

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
obtained, for example because the PDP is unreachable, `evaluate` throws an `AuthZenException`.
Treat it as a deny.

## Documentation

- [Getting started](docs/getting-started.md)
- [Evaluations](docs/evaluations.md), single and batch
- [Search](docs/search.md), including pagination
- [Credentials](docs/credentials.md)
- [Endpoints and discovery](docs/endpoints-and-discovery.md)
- [Resilience](docs/resilience.md): timeouts, retries and circuit breakers
- [Observability](docs/observability.md): listeners, Micrometer and OpenTelemetry
- [Security model](docs/security-model.md)
- [Enforcement](docs/enforcement.md): from an HTTP request to a decision
- [Spring Boot](docs/spring-boot.md): the starter and `@CheckPermission` in Spring MVC
- PDP notes: [Keycloak](docs/pdps/keycloak.md)

## Spring Boot

The starter asks the PDP before every Spring MVC handler annotated with `@CheckPermission`. What
the annotation does not give is inferred from the request:

```xml
<dependency>
    <groupId>io.github.authzenenforcer</groupId>
    <artifactId>authzen-enforcer-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

```yaml
authzen:
  pdp:
    url: https://pdp.example.com
```

```java
@CheckPermission                     // action = "read", resource = {type: "documents", id: <id>}
@GetMapping("/documents/{id}")
public Document getDocument(@PathVariable String id) { ... }
```

The subject comes from the caller's Spring Security authentication. The starter is a preview: it
supports Spring MVC on Spring Boot 4; WebFlux, an adapter for Spring Security's OAuth 2.0 client
and the auto-configuration of metrics follow. See [Spring Boot](docs/spring-boot.md).

## Modules

| Artifact | Java | Status | Description |
|---|---|---|---|
| `authzen-enforcer-core` | 17+ | Available | PDP client, model, Bearer, enforcement of HTTP requests. No dependencies. DPoP is planned. |
| `authzen-enforcer-spring-boot-starter` | 17+ | Preview | Spring Boot 4 auto-configuration and `@CheckPermission` for Spring MVC; WebFlux is planned |
| `authzen-enforcer-quarkus` | 17+ | Planned | Quarkus 3 extension |
| `authzen-enforcer-micrometer` | 17+ | Available | Micrometer metrics for the client |
| `authzen-enforcer-opentelemetry` | 17+ | Available | OpenTelemetry tracing for the client |
| `authzen-enforcer-bom` | – | Available | Version alignment |

Group ID: `io.github.authzenenforcer`

## License

Apache License 2.0
