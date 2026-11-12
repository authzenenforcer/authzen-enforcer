# Enforcement

The `io.github.authzenenforcer.core.enforce` package decides whether an HTTP request may proceed.
It is the framework-independent part of the planned Spring Boot and Quarkus integrations: they
describe the request, the package infers the subject, action and resource, asks the PDP and tells
them how to answer. You can also use it directly.

```java
Enforcer enforcer = Enforcer.builder(client)
        .inferrer(RequestInferrer.builder()
                .subjectResolver(SubjectResolver.keycloak().build())
                .build())
        .build();

EnforcementResult result = enforcer.enforce(RequestDescription.builder()
        .method("GET")
        .routePattern("/documents/{id}")   // the pattern the framework matched, not the raw URI
        .pathVariable("id", "42")
        .claims(jwtClaims)                 // the caller's token claims; none for an anonymous caller
        .build(), PermissionSpec.none());

if (!result.isAllowed()) {
    response.sendError(result.getStatus().getAsInt());
}
```

## Outcomes

| Outcome | Allowed | Status | When |
|---|---|---|---|
| `PERMITTED` | yes | | the PDP permitted the request |
| `DENIED` | no | 403 | the PDP denied the request |
| `NOT_ENFORCED` | yes | | the method is exempt, such as a CORS preflight (`OPTIONS`) |
| `UNAUTHENTICATED` | no | 401 | the caller is anonymous and no anonymous subject is configured |
| `REFUSED` | no | 403 | nothing could be inferred safely; `getReason()` says why |
| `FAILED` | no | 403 or 503 | no decision: the PDP failed, its circuit breaker is open, or an inference component threw |
| `FAILED_OPEN` | yes | | the PDP is unavailable, and the policy fails open |

The enforcer never throws for a request it cannot decide; every failure is a result. The PDP's
response, including any reason in its context, is available through `getResponse()` for logs and
hooks, but is not meant to reach the client.

A 401 response must carry a `WWW-Authenticate` challenge (RFC 9110, section 15.5.2). Its scheme
depends on how callers authenticate, which only the framework knows, so the framework integrations
hand an unauthenticated request to the framework's own authentication, such as Spring Security's
authentication entry point, instead of writing the response themselves. When you use the enforcer
directly, add the challenge yourself, for example `WWW-Authenticate: Bearer`.

### Policy

`EnforcementPolicy` decides how requests without a decision are answered:

```java
Enforcer.builder(client)
        .policy(EnforcementPolicy.builder()
                .errorStatus(503)      // default 403
                .build())
        .build();
```

`failOpen(true)` lets requests through when the PDP is unavailable: it cannot be reached, does not
answer in time, answers with a `5xx` status or keeps answering `429`, or its circuit breaker is
open (a `PdpCommunicationException`). It applies to the whole application and only to an
unavailable PDP: a deny, a refused inference, an anonymous caller and a failing inference
component still deny, and so do failures that are not transient, such as a rejected PEP
credential (`401`/`403`), an invalid PDP response or a failing credential provider. Failing open
on those would let every request through until someone fixed the configuration. The enforcer logs a warning when it is built and, at most
once a minute, when it lets a request through without a decision, and reports each such request as
`FAILED_OPEN` to its listeners; alert on the `failed_open` outcome of the `authzen.enforcements`
meter.

### Cancellation

`enforceAsync(...)` returns a stage that cancels the call to the PDP when it is cancelled, as
`AuthZenClient.evaluateAsync(...)` does; Reactor's `Mono.fromCompletionStage` and Mutiny's
`Uni.createFrom().completionStage` cancel it when their subscriber cancels. An interrupted
`enforce(...)` stops the call the same way. A cancelled request gets no result: the caller gets a
`CancellationException` and must not let the request through.

### Startup check

`enforcer.check(method, routePattern, spec)` tells, before any request arrives, whether requests to
a route will be refused. Framework integrations call it for every protected route at startup and
log what it returns as a warning.

### Events

`Enforcer.Builder.listener(...)` registers a `PepListener` that gets an `EnforcementCompleted`
event for every request: its method, route pattern, outcome, reason or failure, and duration. The
event carries no subject or resource; the client's `CallCompleted` event reports the evaluation.

## Inference

A `RequestInferrer` turns the request into an evaluation request; the enforcer uses one, and you
can use one directly:

```java
RequestInferrer inferrer = RequestInferrer.builder()
        .subjectResolver(SubjectResolver.keycloak().build())
        .build();

Inference inference = inferrer.infer(RequestDescription.builder()
        .method("GET")
        .routePattern("/documents/{id}")   // the pattern the framework matched, not the raw URI
        .pathVariable("id", "42")
        .claims(jwtClaims)                 // the caller's token claims; none for an anonymous caller
        .build(), PermissionSpec.none());

if (inference.getKind() == Inference.Kind.EVALUATE) {
    EvaluationResponse response = client.evaluate(inference.getRequest().orElseThrow());
}
```

## Explicit values win

Whatever is inferred can be set explicitly. Each value comes from the first of:

1. the endpoint's own values, a `PermissionSpec` (for example from an annotation);
2. a `RouteOverrides` entry for the route, for example from configuration;
3. inference.

```java
RouteOverrides overrides = RouteOverrides.builder()
        .route("POST", "/documents/{id}/cancel", PermissionSpec.builder().action("cancel").build())
        .route("/reports/{id}", PermissionSpec.builder().resourceType("report").build())
        .build();
```

A route is matched by its pattern exactly as the framework declares it; values for a method win
over values for every method.

Each value replaces only its own part of what is inferred. An explicit resource type replaces the
type and an explicit resource id the id, on collections as on single resources; whether the
resource is a collection, and so the action, and its enclosing resources still come from the route:

| Route | Explicit values | Resource | Action |
|---|---|---|---|
| `GET /documents` | none | `{type: "collection", id: "documents"}` | `list` |
| `GET /documents` | type `folder` | `{type: "folder", id: "documents"}` | `list` |
| `GET /documents` | id `document` | `{type: "collection", id: "document"}` | `list` |
| `GET /documents/{id}` | type `document` | `{type: "document", id: <id>}` | `read` |

An explicit type on a collection drops the `collection` type, which is what tells the PDP that the
request is about a collection: `{type: "folder", id: "documents"}` is also what a single resource
`documents` of the type `folder` would be asked about. Give collections a type that no single
resource has. And name a collection with a literal id, not one from the request, so that callers
cannot choose which collection is asked about. The Spring Boot starter warns about both at startup.

With both a type and an id, the resource is taken as given even on a route that cannot be inferred
from, such as `/{tenant}/documents`. An invalid path variable is refused in any case, whether the
explicit values use it or not. Each inference step is also a replaceable component:
`ActionMapper`, `ResourceInferrer` and `SubjectResolver`.

## Action

| Method | Single resource | Collection |
|---|---|---|
| `GET`, `HEAD` | `read` | `list` |
| `POST` | `create` | `create` |
| `PUT`, `PATCH` | `update` | `update` |
| `DELETE` | `delete` | `delete` |
| `OPTIONS` | not enforced (CORS preflight) | not enforced |

A `POST` is not always a create, as in `/search`; set the action of such endpoints explicitly.
`ActionMapper.builder()` changes the dictionary. A method without an action is refused.

Actions are looked up in any case, but only the exact token `OPTIONS` is exempt. HTTP method
tokens are case-sensitive, and a framework passes an unknown token such as `options` on to a
handler mapped without a method; it is enforced and, having no action, refused.

## Resource

The resource is inferred from the route pattern, never from the raw URI, so path normalization
cannot change it. The type is the static segment before a path variable, the id the variable's
value:

| Route | Resource |
|---|---|
| `GET /api/v1/documents/{id}` | `{type: "documents", id: "42"}` |
| `GET /documents` | `{type: "collection", id: "documents"}`, action `list` |
| `GET /tenants/{t}/documents` | `{type: "collection", id: "documents"}`, parent `tenants` |
| `DELETE /orgs/{o}/projects/{p}/documents/{id}` | `{type: "documents", id: "42"}` |

Enclosing resources go into `properties`: `parent` holds the nearest as `{type, id}`, `ancestors`
all of them from the outermost in. Only the segment before each variable, or the last one of a
collection, names a type; other segments are left out. So `/admin/users/{id}` and `/users/{id}`
ask the PDP the same question. Where such routes need different decisions, set their resource
type explicitly or with a route override. A collection is never named with a `*` wildcard, which a PDP
could read as "every resource".

Segments are used as they are. `ResourceInferrer.builder().singularize(true)` turns plurals into
singular types (`documents` → `document`) with conservative rules that get irregular words wrong
(`movies` → `movy`); `typeName("analyses", "analysis")` names a type for a segment, with or
without singularizing.

### Routes that are refused

Where the resource cannot be inferred safely, the request is denied without asking the PDP, and a
warning is logged once:

- a variable that does not follow a static segment: `/{tenant}/documents`, `/documents/{id}/{version}`;
- more than one static segment after the last variable: `/documents/{id}/comments/recent`;
- wildcards, catch-all variables and segments that mix text and variables: `/files/**`,
  `/files/{*path}`, `/documents/{id}.json`;
- a variable whose type would be the collection type, as in `/collection/{id}` (or
  `/collections/{id}` when singularizing): a caller choosing the id `documents` would otherwise
  name the same resource as `GET /documents`. Name its type with `typeName(...)`.

One static segment after the last variable is ambiguous: `/tenants/{t}/documents` names a nested
collection, `/documents/{id}/cancel` an action. For `GET` and `HEAD` it is read as a nested
collection. For other methods it is refused unless the action is set explicitly; the resource is
then the one of the variable, `{type: "documents", id: "42"}` with action `cancel`.

`RequestInferrer.check(method, routePattern, spec)` finds these routes before any request arrives;
the framework integrations call it at startup and log a warning for each.

### Path variables

A path variable that is empty, contains a control character or is longer than 1024 characters
(`maxIdLength`) is refused, and the request is denied. Ids are not normalized otherwise. Since
callers control them, such refusals are logged at debug level only, and never with the value.

## Subject

`SubjectResolver.claims()` takes the id from the `sub` claim and the type `user`.
`SubjectResolver.keycloak()` adds the properties `roles` (`realm_access.roles`), `client_roles`
(`resource_access`) and `groups`, and the type `client` for service accounts. Keycloak marks their
tokens, those of the client credentials grant, with a `client_id` claim; the username is not used,
since a user may be able to choose one such as `service-account-x`. Outside the Keycloak profile,
use `serviceAccountType(...)` only with an issuer like that: JWT access tokens following RFC 9068
carry `client_id` in every token. Claims with personal
data are only sent when mapped:

```java
SubjectResolver.keycloak()
        .property("username", "preferred_username")
        .property("email", "email")
        .build();
```

A caller without claims is denied without asking the PDP, unless
`RequestInferrer.Builder.anonymousSubject(...)` names a subject to ask about.

## Context

Nothing is added to the context by default, so neither personal data such as the client IP nor
members that could collide with resource properties are sent unasked. The context holds the members
of the `PermissionSpec` and the route override.
