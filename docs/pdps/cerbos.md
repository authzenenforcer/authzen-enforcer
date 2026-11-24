# Cerbos

[Cerbos](https://www.cerbos.dev/) implements part of the AuthZEN Authorization API next to its own
API. Its decisions come from resource policies, which grant actions to the roles of a principal.
Unlike a PDP that looks the subject up, Cerbos evaluates the roles that the request carries, so the
PEP has to send them. This page describes Cerbos 0.58.0, the version it was checked against, with
its default configuration; see the
[Cerbos documentation](https://docs.cerbos.dev/cerbos/latest/api/index.html) for the API. The
interop tests of this project run against the Cerbos version in
`authzen-enforcer-interop-tests/compose.yaml`, with the policies in that module, including the roles
and attributes described below.

## Connecting

Configure the address of the Cerbos server's HTTP API as the PDP URL. Cerbos publishes metadata, so
discovery finds the endpoints:

```java
AuthZenClient client = AuthZenClient.builder()
        .pdpUrl("https://cerbos.example.com:3592")
        .build();
```

In its default configuration, Cerbos does not authenticate the calling PEP on these endpoints: it
answers without credentials and ignores an `Authorization` header. The specification recommends
that a PDP authenticate its PEPs, since anyone who can call the PDP can probe its policies, and
the PDP trusts the subject that the PEP sends. Restrict who can reach the Cerbos API, for example
with TLS and network policies or a proxy in front of it.

## Supported APIs

| API | Supported |
|---|---|
| Access Evaluation | Yes |
| Access Evaluations | Yes, with all three `evaluations_semantic` values |
| Subject, Resource and Action Search | No (`404`) |

To filter a list, Cerbos offers a query plan on its own API; see "Filtering lists" in
[What the application is responsible for](../security-model.md#what-the-application-is-responsible-for).

## How requests map to Cerbos

| AuthZEN | Cerbos |
|---|---|
| `subject.id` | Principal id |
| `subject.properties."cerbos.roles"` | Principal roles |
| Other `subject.properties` | Principal attributes |
| `resource.type` | Resource kind |
| `resource.id` | Resource id |
| `resource.properties` | Resource attributes |
| `action.name` | Action |

`cerbos.roles` is the name of one property, with a dot in it: a nested object
`{"cerbos": {"roles": [...]}}` is an ordinary attribute to Cerbos, not the roles. A request without
roles, an unknown resource kind or an action that no rule allows is a deny (`200` with
`"decision": false`), not an error.

## Subject

Send the caller's roles in `cerbos.roles`. With the Spring Boot starter, when the roles are
Spring Security's granted authorities:

```yaml
authzen:
  enforcer:
    subject:
      authorities:
        property: cerbos.roles
        prefix: ROLE_
```

When they are in a claim of the token instead, map the claim, for example Keycloak's realm roles:

```yaml
authzen:
  enforcer:
    subject:
      properties:
        cerbos.roles: realm_access.roles
```

Without the starter, `RoleMapping.toProperty("cerbos.roles")` adds roles to a subject, and
`SubjectResolver.claims().property("cerbos.roles", "realm_access", "roles")` copies a claim. See
[Enforcement](../enforcement.md#subject) and
[Spring Boot](../spring-boot.md#the-callers-roles).

Other subject properties become principal attributes, which conditions of the policies can read.
Map only those the policies need.

## Other behavior

- Cerbos rejects a request with an unknown top-level JSON field with `400`. The client sends only
  the fields of the specification; `action.properties` is accepted.
- Cerbos does not echo `X-Request-ID`. The client does not rely on it.
- The metadata carries no caching headers, so the client keeps it for `defaultTtl`, one hour; see
  [Endpoints and discovery](../endpoints-and-discovery.md#how-discovery-works).
