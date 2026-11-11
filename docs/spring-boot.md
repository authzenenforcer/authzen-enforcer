# Spring Boot

The `authzen-enforcer-spring-boot-starter` module configures an `AuthZenClient` and an `Enforcer`
from properties, and enforces handler methods of Spring MVC annotated with `@CheckPermission`. It
requires Spring Boot 4.0 or later and Java 17 or later. WebFlux is not supported yet.

```xml
<dependency>
    <groupId>io.github.authzenenforcer</groupId>
    <artifactId>authzen-enforcer-spring-boot-starter</artifactId>
    <version>${authzen-enforcer.version}</version>
</dependency>
```

The starter brings no Spring dependencies beyond `spring-boot-autoconfigure`: Spring MVC, and
Spring Security if it is used, come from the application.

## Configuration

Only the PDP's base URL is required. Without it, the application does not start: starting would
leave every protected endpoint without a decision.

```yaml
authzen:
  pdp:
    url: https://pdp.example.com
```

| Property | Default | Description |
|---|---|---|
| `authzen.pdp.url` | – | Base URL of the PDP. Required, unless the application defines an `AuthZenClient` bean. |
| `authzen.pdp.connect-timeout` | `5s` | Timeout for establishing a connection. |
| `authzen.pdp.request-timeout` | `5s` | Timeout of a call, including its retries. |
| `authzen.pdp.max-retries` | client default | Retries of a call that failed transiently, 0 to 10. |
| `authzen.pdp.discovery` | `true` | Whether to discover the endpoints from the PDP's metadata. |
| `authzen.enforcer.enforce-all` | `false` | Enforce every handler method, not only annotated ones. |
| `authzen.enforcer.exclude-paths` | – | Route patterns that `enforce-all` leaves out, such as `/public/**`. |
| `authzen.enforcer.error-status` | `403` | Status of a request without a decision: `403` or `503`. |
| `authzen.enforcer.fail-open` | `false` | Let requests through when the PDP is unavailable; see [Enforcement](enforcement.md). |
| `authzen.enforcer.anonymous.enabled` | `false` | Ask the PDP about anonymous callers instead of answering `401`. |
| `authzen.enforcer.anonymous.type` / `.id` | `anonymous` | Subject of anonymous callers. |
| `authzen.enforcer.subject.profile` | `default` | `keycloak` adds roles, client roles and groups, and type `client` for service accounts. |
| `authzen.enforcer.subject.id-claim` | `sub` | Claim that holds the subject id. |
| `authzen.enforcer.subject.type` | `user` | Subject type. |
| `authzen.enforcer.subject.properties.<name>` | – | Claim copied into a subject property, as a dotted path such as `realm_access.roles`. |
| `authzen.enforcer.resource.singularize` | `false` | Turn plural route segments into singular resource types. |
| `authzen.enforcer.resource.max-id-length` | `1024` | Longer resource ids from path variables are denied. |
| `authzen.enforcer.resource.type-names.<segment>` | – | Resource type of a route segment, such as `analyses: analysis`. |

A bean of the application replaces the default and the properties that configure it: an
`AuthZenClient`, `Enforcer`, `RequestInferrer`, `ActionMapper`, `ResourceInferrer`,
`SubjectResolver`, `RouteOverrides` or `EnforcementPolicy`. Overrides of single routes are
given as a `RouteOverrides` bean.

### Credentials

The client authenticates to the PDP with the credential of a `CredentialProvider` bean, if the
application defines one, and sends none otherwise; see [Credentials](credentials.md). The PEP
authenticates with an identity of its own, such as a service account. The starter does not forward
the caller's token to the PDP: the AuthZEN specification asks the PDP to authenticate the calling
PEP (section 11.2) and to trust it for the values it sends, the subject included (section 11.4).
A PDP that accepted the callers' tokens would let any caller ask it directly about any subject.

A `JsonCodec` bean replaces the internal JSON codec, and every `PepListener` bean is registered
with both the client and the enforcer.

## @CheckPermission

The interceptor asks the PDP before an annotated handler runs. Whatever the annotation does not
give is inferred, as described in [Enforcement](enforcement.md): the action from the HTTP method,
the resource from the route pattern, and the subject from the authenticated caller.

```java
@CheckPermission                                  // read on {type: "documents", id: <id>}
@GetMapping("/documents/{id}")
Document get(@PathVariable String id) { ... }

@CheckPermission(action = "cancel", resourceType = "order", resourceId = "#id",
        context = "ip=#request.remoteAddr")
@PostMapping("/orders/{id}/cancel")
void cancel(@PathVariable String id) { ... }
```

- `action`, `resourceType` and `subjectType` are literal values; an empty value means "not given".
- `resourceId` and the values of `context` (`name=expression`) are SpEL expressions. They see the
  path variables by name (`#id`), `#request` (the `HttpServletRequest`) and `#authentication`
  (the Spring Security `Authentication`, or `null`). They are evaluated read-only: properties and
  methods can be used, types, constructors and beans cannot. `#request` and `#authentication` win
  over path variables of the same name. The request body is not available, because the check runs
  before it is read.
- On a class, the annotation applies to all its handler methods. An annotation on the method wins
  attribute by attribute, and its context entries by name.
- It works as a meta-annotation, for example `@CheckPermission(action = "approve")` on an
  `@Approve` annotation of the application.

A request whose expression fails, or whose resource id expression gives neither a string nor a
number, is denied with `403` without asking the PDP, and a warning is logged once. Invalid
expressions and context entries fail the startup.

### Which handlers are enforced

By default, only handler methods annotated with `@CheckPermission`, on the method or the class.
With `authzen.enforcer.enforce-all=true`, every handler method of Spring MVC is enforced, including
the endpoints of Spring Boot Actuator, such as health probes, except:

- routes matching `authzen.enforcer.exclude-paths`. They are matched against the route pattern of
  the handler, such as `/public/{id}`, not against the request path, so that the decision does not
  depend on how a client spells the path;
- handlers annotated with `@SkipPermissionCheck`. On a method, it also exempts the method from a
  `@CheckPermission` on its class.

Exclude the Actuator endpoints that must stay reachable, such as `/actuator/health/**`. The
annotation nearest to the method wins; a method or class annotated with both does not start.
Only handler methods are enforced: static resources and functional routes are not. Error dispatches
and the second dispatch of an asynchronous request to the handler that was let through on the first one
are not enforced again; an asynchronous dispatch that a filter starts before any handler ran is
enforced. `OPTIONS` requests are
not enforced (CORS preflight).

At startup, every enforced route is checked; routes whose requests would always be refused, such
as `/{tenant}/documents`, are logged as warnings. Give their values explicitly, in the annotation
or a `RouteOverrides` bean.

## Responses

| Outcome | With Spring Security | Without Spring Security |
|---|---|---|
| Permitted, not enforced, failed open | The handler runs | The handler runs |
| Anonymous caller | `AuthenticationCredentialsNotFoundException`: the entry point answers `401` with its challenge | `401` with `WWW-Authenticate: Bearer` |
| Denied, refused, failed with `403` | `AccessDeniedException`: the access denied handler answers | `403` |
| Failed with `503` | `503` | `503` |

The PDP's reason, if its response carries one, is not sent to the client. An
`EnforcementResultHandler` bean answers instead of the default, for example to log the reason. A
handler that neither throws nor sends an error status does not let the request through: the status
of the result is sent for it.

With Spring Security, the exceptions reach its filters only if no `@ExceptionHandler` of the
application handles them first; an `@ExceptionHandler(Exception.class)` must rethrow
`AccessDeniedException` and `AuthenticationException`.

## The caller's claims

With Spring Security, the subject is inferred from the claims of the `Authentication`:

- a JWT (resource server): its claims; timestamps such as `exp` are seconds since the epoch;
- an opaque token, or an OAuth 2.0 or OpenID Connect login: the attributes of the principal;
- any other authentication: only `sub`, set to the name of the principal.

Anonymous authentications have no claims. Without Spring Security, every caller is anonymous
unless the application defines a `ClaimsExtractor` bean.
