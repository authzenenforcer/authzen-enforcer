# Enforcement inference

The `io.github.authzenenforcer.core.enforce` package turns an HTTP request into an AuthZEN
evaluation request. It is the framework-independent part of the planned Spring Boot and Quarkus
integrations: they describe the request, the package infers the subject, action and resource, and
the client asks the PDP. You can also use it directly.

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
over values for every method. Each inference step is also a replaceable component:
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
all of them from the outermost in. A collection is never named with a `*` wildcard, which a PDP
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
  `/files/{*path}`, `/documents/{id}.json`.

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
(`resource_access`) and `groups`, and the type `client` for service accounts. Claims with personal
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
