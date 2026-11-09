# Keycloak

Keycloak acts as an AuthZEN PDP since version 26.7, as an experimental feature enabled with
`--features=authzen`. Its decisions come from the policies of a client with Authorization Services.
The interop tests of this project run against the Keycloak version in
`authzen-enforcer-interop-tests/compose.yaml`; see that module for the realm they use.

## Connecting

Configure the realm URL as the PDP URL. Keycloak publishes metadata for it, so discovery finds the
endpoints below `/authzen` in the realm:

```java
AuthZenClient client = AuthZenClient.builder()
        .pdpUrl("https://keycloak.example.com/realms/my-realm")
        .credentials(clientCredentialsProvider)
        .build();
```

The PEP authenticates with a bearer token, for example one it obtains with the client credentials
grant for a confidential client that has a service account and Authorization Enabled. The client
credentials provider in the interop tests shows one way to write such a provider; see
[Credentials](../credentials.md).

## Supported APIs

| API | Supported |
|---|---|
| Access Evaluation | Yes |
| Access Evaluations | Yes |
| Subject, Resource and Action Search | No |

## How requests map to Keycloak

| AuthZEN | Keycloak |
|---|---|
| `subject.type` | `user` or `client`; any other type is rejected with `400` |
| `subject.id` | A user id, or a username; prefixes `id:`, `username:` and `email:` select the lookup |
| `resource.type` | Resource type |
| `resource.id` | Resource name |
| `action.name` | Scope name |

An unknown subject, resource or scope is a deny (`200` with `"decision": false`), not an error.

## Behavior that differs from the specification

- Keycloak rejects unknown JSON fields with `400`. This includes `action.properties`, which the
  specification allows ([keycloak/keycloak#54542](https://github.com/keycloak/keycloak/issues/54542)).
- A `401` from Keycloak carries no `WWW-Authenticate` header, which the specification recommends
  ([keycloak/keycloak#54516](https://github.com/keycloak/keycloak/issues/54516)). The client does
  not rely on it.

## Other behavior

- Keycloak sends `Cache-Control: no-store` with its metadata. The client therefore fetches it again
  about once a minute and cannot fall back on expired metadata: when a refresh cannot reach
  Keycloak, requests fail (deny). See
  [Endpoints and discovery](../endpoints-and-discovery.md#how-discovery-works).
