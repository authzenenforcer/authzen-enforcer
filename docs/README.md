# authzen-enforcer documentation

authzen-enforcer is a Java SDK that acts as a Policy Enforcement Point (PEP) for the
[OpenID AuthZEN Authorization API 1.0](https://openid.net/wg/authzen/). These pages describe how
the client behaves; the Javadoc describes each type and method.

- [Getting started](getting-started.md): adding the dependency, the first decision, errors and
  threading.
- [Evaluations](evaluations.md): single decisions and several decisions in one call.
- [Search](search.md): which subjects, resources or actions the PDP permits, page by page or all
  at once.
- [Credentials](credentials.md): how the client authenticates to the PDP.
- [Endpoints and discovery](endpoints-and-discovery.md): metadata discovery, caching and endpoint
  overrides.
- [Resilience](resilience.md): timeouts, retries and circuit breakers.
- [Observability](observability.md): listeners, Micrometer metrics and OpenTelemetry tracing.
- [Security model](security-model.md): fail-closed behaviour, what the client sends and what it
  checks.
- [Enforcement](enforcement.md): deciding about an HTTP request, the outcomes and the policy,
  and how explicit values override what is inferred.
- [Spring Boot](spring-boot.md): the starter, its properties and `@CheckPermission` in Spring MVC.
- PDPs: [Keycloak](pdps/keycloak.md).
