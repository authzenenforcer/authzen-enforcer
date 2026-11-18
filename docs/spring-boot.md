# Spring Boot

The `authzen-enforcer-spring-boot-starter` module configures an `AuthZenClient` and an `Enforcer`
from properties, and enforces handler methods of Spring MVC and Spring WebFlux annotated with
`@CheckPermission`. It requires Spring Boot 4.0 or later and Java 17 or later.

The starter is a preview, available since 0.2.0.

```xml
<dependency>
    <groupId>io.github.authzenenforcer</groupId>
    <artifactId>authzen-enforcer-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

The starter brings no Spring dependencies beyond `spring-boot-autoconfigure`: Spring MVC or Spring
WebFlux, and Spring Security if it is used, come from the application.

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
| `authzen.pdp.client-registration-id` | – | Registration of Spring Security's OAuth 2.0 client that obtains the PEP's token; see [Credentials](#credentials). |
| `authzen.enforcer.enforce-all` | `false` | Enforce every handler method, not only annotated ones. |
| `authzen.enforcer.exclude-paths` | – | Route patterns that `enforce-all` leaves out, such as `/public/**`. |
| `authzen.enforcer.error-status` | `403` | Status of a request without a decision: `403` or `503`. |
| `authzen.enforcer.fail-open` | `false` | Let requests through when the PDP is unavailable; see [Enforcement](enforcement.md). |
| `authzen.enforcer.anonymous.enabled` | `false` | Ask the PDP about anonymous callers instead of answering `401`. |
| `authzen.enforcer.anonymous.type` / `.id` | `anonymous` | Subject of anonymous callers. |
| `authzen.enforcer.subject.profile` | `default` | `keycloak` adds roles, client roles and groups, and type `client` for service accounts, for Keycloak as the identity provider and another PDP. With Keycloak as the PDP, keep `default`; see [Keycloak](pdps/keycloak.md#subject). |
| `authzen.enforcer.subject.id-claim` | `sub` | Claim that holds the subject id. |
| `authzen.enforcer.subject.type` | `user` | Subject type. |
| `authzen.enforcer.subject.properties.<name>` | – | Claim copied into a subject property, as a dotted path such as `realm_access.roles`. |
| `authzen.enforcer.resource.singularize` | `false` | Turn plural route segments into singular resource types. |
| `authzen.enforcer.resource.max-id-length` | `1024` | Longer resource ids from path variables or expressions are denied. |
| `authzen.enforcer.resource.type-names.<segment>` | – | Resource type of a route segment, such as `analyses: analysis`. |

A bean of the application replaces the default and the properties that configure it: an
`AuthZenClient`, `Enforcer`, `RequestInferrer`, `ActionMapper`, `ResourceInferrer`,
`SubjectResolver`, `RouteOverrides` or `EnforcementPolicy`. Overrides of single routes are
given as a `RouteOverrides` bean.

Metrics, traces and a health indicator are configured when their libraries are present; see
[Observability](#observability).

### Credentials

The client authenticates to the PDP with the credential of a `CredentialProvider` bean, if the
application defines one, and sends none otherwise; see [Credentials](credentials.md). The PEP
authenticates with an identity of its own, such as a service account. The starter does not forward
the caller's token to the PDP: the AuthZEN specification asks the PDP to authenticate the calling
PEP (section 11.2) and to trust it for the values it sends, the subject included (section 11.4).
A PDP that accepted the callers' tokens would let any caller ask it directly about any subject.

An application that uses Spring Security's OAuth 2.0 client (`spring-boot-starter-security-oauth2-client`)
names a client registration instead of writing a provider. The starter then obtains the PEP's token
with the registration's client credentials grant:

```yaml
authzen:
  pdp:
    url: https://pdp.example.com
    client-registration-id: pdp

spring:
  security:
    oauth2:
      client:
        registration:
          pdp:
            client-id: document-portal
            client-secret: ${PDP_CLIENT_SECRET}
            authorization-grant-type: client_credentials
        provider:
          pdp:
            token-uri: https://idp.example.com/realms/demo/protocol/openid-connect/token
```

- The token is cached until a minute before it expires. Then a single request obtains a new one,
  while the others wait for it without blocking a thread. If the PDP answers `401`, the token is
  dropped and the request is repeated once with a new one.
- The token is obtained on the thread that needs it first, which may be a thread of the HTTP
  client. If the token request fails, or takes longer than `authzen.pdp.request-timeout`, the
  requests waiting for it fail without being sent, and the next request starts another token
  request. The thread that sent the hung one stays blocked until the token client gives up, and
  Spring Security's default token client sets no timeout: give the application an
  `OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest>` bean whose `RestClient`
  has connect and read timeouts.
- The token is stored in the application's `OAuth2AuthorizedClientService`, under the principal
  `authzen-enforcer`. An `OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest>` bean
  of the application obtains it, if there is one, for example to authenticate the client with
  `private_key_jwt`. The application's `OAuth2AuthorizedClientManager` is not used: Spring
  Security's default one needs the current HTTP request, which the client's own threads do not
  have.
- Only bearer tokens are supported; a token of another type fails the request.
- The application does not start if the property names no registration, a registration of another
  grant, or if Spring Security's OAuth 2.0 client is missing: the PDP would reject every request. A
  `CredentialProvider` bean of the application takes precedence over the property, and the starter
  logs a warning.

In a Spring WebFlux application, Spring Boot creates the reactive OAuth 2.0 client instead, and the
starter uses it the same way: the registration comes from the `ReactiveClientRegistrationRepository`,
the token is stored in the `ReactiveOAuth2AuthorizedClientService`, and a
`ReactiveOAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest>` bean of the
application obtains it, if there is one. No thread waits for the token request. One that takes
longer than `authzen.pdp.request-timeout` is cancelled, which matters because Spring Security's
default reactive token client sets no response timeout. When the PDP rejects a token, the next
token request starts once the rejected token is removed from the service; a removal that takes
longer than the request timeout is cancelled and fails only that token request. Where Spring Boot creates
both clients, as in an application without a web server, the servlet one is used.

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
  Each attribute replaces only its own part of what is inferred: `@CheckPermission(resourceType =
  "folder")` on `GET /documents` asks to `list` `{type: "folder", id: "documents"}`; the id and the
  action are still inferred. See [Enforcement](enforcement.md#explicit-values-win).
- On a collection route, two explicit values deserve care, and are logged as warnings at startup:
  a `resourceType` makes the request look like one about a single resource of that type, so give
  collections a type that no single resource has; a `resourceId` taken from the request, such as
  `#request.parameter('folder')`, lets callers choose the collection, so use a literal such as
  `resourceId = "'documents'"`. Both together with an explicit `action` name a single resource,
  which is not warned about: `GET /documents/{id}/download` with `action = "read"`,
  `resourceType = "document"` and `resourceId = "#id"` is about the document. Without the action,
  the route still infers the action of a collection, `list`, and both warnings remain.
- `resourceId` and the values of `context` (`name=expression`) are SpEL expressions. They may read
  only:
  - path variables by name (`#id`);
  - `#request.method`, `#request.remoteAddr`, `#request.header('name')` and
    `#request.parameter('name')`, with a literal name, so that a caller cannot choose what is read.
    Headers and parameters that carry credentials (`Authorization`, `Proxy-Authorization`, `Cookie`,
    `DPoP`, `access_token`) read as `null`;
  - `#claims`, the caller's claims that the subject is inferred from, such as `#claims['sub']` or
    `#claims.realm_access.roles`. It is `null` for an anonymous caller, so on endpoints that
    anonymous callers reach, read it null-safely, such as `#claims?.['acr']`; otherwise the
    expression fails and the request is refused before the caller is asked to authenticate.

  Types, constructors, beans, other methods and assignments are not available, so an expression
  cannot put the caller's token or other credentials into an evaluation request, nor change state.
  An expression that uses anything else fails the startup. `#request` and `#claims` win over path
  variables of the same name. The request body is not available, because the check runs before it
  is read, except for the fields of a form (`application/x-www-form-urlencoded`), which
  `#request.parameter` reads with those of the query. Spring WebFlux reads such a form before the
  decision only for handlers whose expressions read parameters, and keeps it for the handler.
  A parameter with more than one value, repeated or both in the query and in the form, refuses the
  request, and so does a header that an expression reads and the request repeats: handlers read
  such values differently, so the value the PDP is asked about would not
  be the one the handler acts on. Spring WebFlux's `@RequestParam` reads only the query, while its
  form data and data binding prefer the body; Spring MVC joins the values for a `String` argument.
  An expression that reads a parameter of a multipart request refuses it too: a part that carries
  a file name is no parameter, yet a handler can read it by name, as with `@RequestPart`. A value that the application chooses itself, such as a custom API key header, is not
  recognized as a credential: do not put such values into the context.
- Handlers that use data binding, with a `@ModelAttribute` argument, also with `binding = false`
  since constructor binding ignores it, or an object argument that no other resolver takes, get
  their values from more sources than the PDP sees. The same goes for every handler of a
  controller whose `@ModelAttribute` methods, or those of a `@ControllerAdvice` that applies to it,
  bind such an argument. Data binding prefers
  parameters and multipart parts to path variables, and path variables to headers, and also binds
  names that only resemble a property, in another case of its first letter, with a `!` or `_`
  prefix, or as the head of an indexed or nested path such as `id[0]` or `id.value`. So that the
  handler acts on the values the PDP was asked about, a request to such a handler is refused when
  - a parameter or part resembles a path variable of its route, as `PUT /documents/{id}?id=8`
    would bind `8`;
  - a parameter or part is named like a bound argument: Spring MVC creates an argument
    `DocumentRef documentRef` from a parameter `documentRef` by type conversion, such as through a
    constructor that takes a string;
  - a parameter that an expression reads is resembled by another parameter, or is absent while a
    path variable or a header of the same name could fill it, as a header `Level` fills `level`
    (headers are bound under their name and under it without dashes);
  - a header that an expression reads could be replaced by a parameter, path variable or other
    header of the same name.

  Arguments are named as Spring names them, also when a base controller declares the handler
  method with a type variable, or an interface declares its `@ModelAttribute` name. Three sources
  remain the application's to watch: an `@InitBinder` method that changes the binder's field marker
  or default prefix (`_` and `!` are recognized), model attributes that `@SessionAttributes` or
  redirect flash attributes supply, and a single value with commas, such as `?ids=1,2`, which the
  PDP is asked about as written while a list or array argument receives it split.

  Handlers that take each value with `@PathVariable`, `@RequestParam`, `@RequestHeader` or
  `@RequestBody` are not affected. Arguments that a resolver of the application takes without one
  of these annotations count as bound, so such a handler is refused on these names as well. Spring
  WebFlux reads the form and the multipart parts of a request to a handler that binds data before
  the decision, as data binding would, and keeps them for the handler. It does so for callers that
  will be denied too, so limit multipart bodies with `spring.webflux.multipart.max-parts` and
  `spring.webflux.multipart.max-disk-usage-per-part`, which Spring Boot leaves unlimited.
- On a class, the annotation applies to all its handler methods. An annotation on the method wins
  attribute by attribute, and its context entries by name.
- It works as a meta-annotation, for example `@CheckPermission(action = "approve")` on an
  `@Approve` annotation of the application.

A request whose expression fails is denied with `403` without asking the PDP, and a warning is
logged once. The same goes for a resource id expression that gives neither a string nor a number,
or an id that path variables would not be accepted with either: empty, longer than
`authzen.enforcer.resource.max-id-length`, or with a control character. Invalid expressions and
context entries fail the startup.

### Which handlers are enforced

By default, only handler methods annotated with `@CheckPermission`, on the method or the class.
With `authzen.enforcer.enforce-all=true`, every handler method of Spring MVC or Spring WebFlux is
enforced, including the endpoints of Spring Boot Actuator, such as health probes, except:

- routes matching `authzen.enforcer.exclude-paths`. They are matched against the route pattern of
  the handler, such as `/public/{id}`, not against the request path, so that the decision does not
  depend on how a client spells the path;
- handlers annotated with `@SkipPermissionCheck`. On a method, it also exempts the method from a
  `@CheckPermission` on its class.

Exclude the Actuator endpoints that must stay reachable, such as `/actuator/health/**`. The
annotation nearest to the method wins; a method or class annotated with both does not start.
Only handler methods are enforced: static resources and functional routes are not. In Spring MVC,
error dispatches and the second dispatch of an asynchronous request to the handler that was let
through on the first one are not enforced again; an asynchronous dispatch that a filter starts
before any handler ran is enforced. `OPTIONS` requests are not enforced (CORS preflight), whether a
handler or Spring itself answers them.

Only handler methods of controllers are enforced. `@CheckPermission` on any other bean, such as a
service, on a method of a controller that is no handler, or in an application with neither Spring
MVC nor Spring WebFlux has no effect; such annotations are logged as warnings at startup. Annotations are read from the class
of the bean: a controller behind a JDK interface proxy is only enforced through annotations on the
interface. With a separate management port (`management.server.port`), the Actuator endpoints are
enforced like the others, but they are not part of the startup check.

At startup, every enforced route is checked; routes whose requests would always be refused, such
as `/{tenant}/documents`, are logged as warnings. Give their values explicitly, in the annotation
or a `RouteOverrides` bean.

## Responses

| Outcome | With Spring Security | Without Spring Security |
|---|---|---|
| Permitted, not enforced, failed open | The handler runs | The handler runs |
| Anonymous caller | Spring MVC: `AuthenticationCredentialsNotFoundException`; Spring WebFlux: `AccessDeniedException`. The entry point answers `401` with its challenge | `401` with `WWW-Authenticate: Bearer` |
| Denied, refused, failed with `403` | `AccessDeniedException`: the access denied handler answers | `403` |
| Failed with `503` | `503` | `503` |

The PDP's reason, if its response carries one, is not sent to the client. An
`EnforcementResultHandler` bean (Spring WebFlux: `ReactiveEnforcementResultHandler`) answers
instead of the default, for example to log the reason. A handler that neither throws nor sends an
error status does not let the request through: the status of the result is sent for it.

Spring Security's reactive exception handling handles only `AccessDeniedException`, and tells
anonymous callers from authenticated ones by the principal of the request: it answers `401` if
there is none. A `ReactiveClaimsExtractor` of the application that finds no claims for an
authenticated principal therefore gets `403`, not `401`. Without Spring Security, Spring WebFlux
answers with a `ResponseStatusException`, which the error handling of Spring Boot renders.

With Spring Security, the exceptions reach its filters only if no `@ExceptionHandler` of the
application handles them first; an `@ExceptionHandler(Exception.class)` must rethrow
`AccessDeniedException` and `AuthenticationException`.

## The caller's claims

With Spring Security, the subject is inferred from the claims of the `Authentication`:

- a JWT (resource server): its claims; timestamps such as `exp` are seconds since the epoch;
- an opaque token, or an OAuth 2.0 or OpenID Connect login: the attributes of the principal;
- any other authentication: only `sub`, set to the name of the principal.

In Spring WebFlux, the `Authentication` is that of the reactive security context. Anonymous
authentications have no claims. Without Spring Security, every caller is anonymous unless the
application defines a `ClaimsExtractor` bean (Spring WebFlux: `ReactiveClaimsExtractor`). Expressions see the same claims as
`#claims`, never the token itself.

## Spring WebFlux

In a reactive web application, the starter registers a handler adapter that the dispatcher picks
first for handler methods. It decides where a Spring MVC interceptor does: after the request is
mapped, so that it decides about the handler that runs, and before arguments are resolved and the
body is read. Its exceptions reach `@ExceptionHandler` methods and then Spring Security's filters,
as in Spring MVC.

Every decision starts on Reactor's bounded elastic scheduler, not on the event loop: the components
that the application plugs into it, such as a `CredentialProvider` whose `getCredential` obtains a
token, a `SubjectResolver` or a `PepListener`, may then block without stalling other requests. The
handler runs on the thread that completed the decision, usually one of the HTTP client's. The
`ReactiveClaimsExtractor` and `ReactiveEnforcementResultHandler` beans run on the request's thread
and must not block. The bounded elastic scheduler is shared with Spring Security and the
application, which may use it for other blocking work, such as password hashing: a credential
provider that blocks for long, for example while it obtains a token, holds one of its threads for
that time.

The handler adapter's order is `Ordered.HIGHEST_PRECEDENCE`. A handler adapter of the application
that supports handler methods and is sorted before it runs them without a decision; such an
adapter is logged as a warning at startup.

When the subscription is cancelled, for example because the client went away, the call to the PDP
is cancelled too, and listeners see a cancelled enforcement.

## Observability

The starter configures metrics and traces when their module of authzen-enforcer and their library
are present, and a health indicator on request. Add the modules you use; the starter does not bring
them along.

```xml
<dependency>
    <groupId>io.github.authzenenforcer</groupId>
    <artifactId>authzen-enforcer-micrometer</artifactId>
</dependency>
<dependency>
    <groupId>io.github.authzenenforcer</groupId>
    <artifactId>authzen-enforcer-opentelemetry</artifactId>
</dependency>
```

| Property | Default | Description |
|---|---|---|
| `authzen.metrics.enabled` | `true` | Record Micrometer metrics, when `authzen-enforcer-micrometer` is present and there is a `MeterRegistry` bean. |
| `authzen.metrics.resource-type-tag` / `.action-name-tag` | `false` | Tag `authzen.client.calls` with `resource.type` and `action.name`; only for few and fixed types and names. |
| `authzen.tracing.enabled` | `true` | Trace calls to the PDP, when `authzen-enforcer-opentelemetry` is present and there is an `OpenTelemetry` bean. |
| `authzen.tracing.resource-type-attribute` / `.action-name-attribute` | `true` | Give spans `authzen.resource.type` and `authzen.action.name`. |
| `management.health.authzen.enabled` | `false` | Add the health indicator `authzen`. |

- **Metrics:** a `MicrometerPepListener` bean records the meters listed in
  [Observability](observability.md#metrics-with-micrometer) for the client and the enforcer, and
  `AuthZenClientMetrics` and `EnforcerMetrics` beans expose their listener drops. With Spring Boot
  Actuator, the registry comes from `spring-boot-starter-actuator` and a registry such as
  `micrometer-registry-prometheus`. Tags that every meter of the application carries come from
  `management.metrics.tags.*`.
- **Traces:** a `PepTracer` bean, the `OpenTelemetryTracing` of the `OpenTelemetry` bean, such as
  the one of `spring-boot-starter-opentelemetry`, traces the calls and sends their trace context
  to the PDP; see [Observability](observability.md#tracing-with-opentelemetry). A `PepTracer` bean
  of the application replaces it.
- **Health:** the indicator reads the client's state without contacting the PDP, so a probe never
  causes a call. It is `DOWN` while calls deny at once: while the breaker of the authorization API
  is open, or after the last metadata refresh failed. A half-open breaker and stale metadata are
  `UP`. The details give the state of each breaker and the result and time of the last metadata
  refresh, with the class of its failure, never its message.

```json
{
  "status": "DOWN",
  "details": {
    "breakers": { "authorizationApi": "open", "metadataDiscovery": "closed" },
    "metadata": { "result": "default_paths", "time": "2026-11-18T10:15:30Z" }
  }
}
```

The indicator is opt-in and not part of the liveness and readiness groups: every instance shares
the PDP, so an outage of the PDP would take all of them out of service at once, while
enforcement already denies on its own. It does count towards the overall status of
`/actuator/health`, so let load balancers and orchestrators probe `/actuator/health/liveness` and
`/actuator/health/readiness`, not the overall status. Add it to a group of your own, or to
readiness, only if that is what you want. If the client's state cannot be read, the indicator is
`DOWN` with the class of the exception as `error`.

The beans of the application win: a `MicrometerPepListener`, `AuthZenClientMetrics`,
`EnforcerMetrics`, `PepTracer` or a bean named `authzenHealthIndicator` replaces the default. An
`AuthZenClient` or `Enforcer` bean of the application gets neither the listener nor the tracer;
register them in its builder.
