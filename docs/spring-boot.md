# Spring Boot

The `authzen-enforcer-spring-boot-starter` module configures an `AuthZenClient` and an `Enforcer`
from properties, and enforces handler methods of Spring MVC and Spring WebFlux annotated with
`@CheckPermission`. It requires Spring Boot 4.0 or later and Java 17 or later.

The starter is a preview, available since 0.2.0. The auto-configuration of metrics follows; until
then, bind metrics as described in [Observability](observability.md).

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
token request starts once the rejected token is removed from the service. Where Spring Boot creates
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
  request: handlers read such parameters differently, so the value the PDP is asked about would not
  be the one the handler acts on. Spring WebFlux's `@RequestParam` reads only the query, while its
  form data and data binding prefer the body; Spring MVC joins the values for a `String` argument.
  An expression that reads a parameter of a multipart request refuses it too: a part that carries
  a file name is no parameter, yet a handler can read it by name, as with `@RequestPart`. A value that the application chooses itself, such as a custom API key header, is not
  recognized as a credential: do not put such values into the context.
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
