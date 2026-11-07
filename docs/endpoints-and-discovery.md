# Endpoints and discovery

## The PDP URL

The client discovers the endpoints from the metadata the PDP publishes. The PDP URL is also the
PDP's identifier: for `https://pdp.example.com/tenant1`, the metadata is fetched from
`https://pdp.example.com/.well-known/authzen-configuration/tenant1`, and its
`policy_decision_point` must be exactly that URL. Terminating slashes of the PDP URL are removed
first. Keycloak, for example, serves AuthZEN below `/authzen` in each realm, and the realm URL is all
you need to configure:

```java
AuthZenClient client = AuthZenClient.builder()
        .pdpUrl("https://keycloak.example.com/realms/my-realm")
        .credentials(clientCredentialsProvider)
        .build();
```

The PDP URL and every endpoint must be an absolute `https` or `http` URL without user info, query or
fragment.

## How discovery works

Discovery happens on the first request, not when the client is built. The metadata request carries
no credentials. Discovery follows these rules:

- The result is kept for as long as the caching headers of the metadata response say, within
  `minTtl` (one minute) and `maxTtl` (one day): `Cache-Control: max-age`, otherwise `Expires`
  minus `Date`, minus `Age`. Without such headers it is kept for `defaultTtl` (one hour).
  `no-store`, `no-cache` and `max-age=0` keep it for `minTtl`. Keycloak sends these, so its
  metadata is fetched about once a minute.
- `no-store`, `no-cache` and `must-revalidate` forbid using expired metadata at all (RFC 9111
  section 4.2.4), and `stale-if-error=N` shortens `maxStale` (see the next rule) to `N` seconds.
  Keycloak sends these, so with Keycloak requests fail as soon as a refresh cannot reach it.
  `honorCacheHeaders(false)` ignores all caching headers, and `maxTtl` then has no effect.
- If a refresh cannot reach the PDP, gets a server error or keeps being rate-limited (`429`), the
  previous result stays in use for at most `maxStale` (one hour) after it expired. The refresh is
  tried again every `minTtl` (one minute, at least one second). After `maxStale`, requests fail until
  a refresh succeeds. Any other failure drops the previous result at once.
- If the PDP answers `404`, it publishes no metadata, and the client uses the default paths of the
  specification below the PDP URL, such as `/access/v1/evaluation`. A `404` that replaces published
  metadata is kept only for `minTtl`, since it may come from a proxy during a rollout.
- If the metadata is invalid, it is for another `policy_decision_point`, or it lists an `http`
  endpoint for an `https` PDP, the request fails (fail-closed). Other errors fail it too, and the
  next request tries again.
- Signed metadata (`signed_metadata`) is not supported.
- `client.metadata()` returns the discovered metadata, for example to check whether the PDP supports
  the Access Evaluations API.

Discovery has its own circuit breaker; see [Resilience](resilience.md#circuit-breakers).

## Discovery options

The durations are set with `DiscoveryOptions`:

```java
AuthZenClient client = AuthZenClient.builder()
        .pdpUrl("https://pdp.example.com")
        .discovery(DiscoveryOptions.builder()
                .defaultTtl(Duration.ofMinutes(30))
                .maxStale(Duration.ofMinutes(15))
                .build())
        .build();
```

`discovery(false)` turns discovery off; endpoints that are not overridden then use the default
paths below the PDP URL.

## Default paths

| API | Metadata parameter | Default path |
|---|---|---|
| Access Evaluation | `access_evaluation_endpoint` | `/access/v1/evaluation` |
| Access Evaluations | `access_evaluations_endpoint` | `/access/v1/evaluations` |
| Subject Search | `search_subject_endpoint` | `/access/v1/search/subject` |
| Resource Search | `search_resource_endpoint` | `/access/v1/search/resource` |
| Action Search | `search_action_endpoint` | `/access/v1/search/action` |

The default paths are used only when the PDP publishes no metadata or discovery is off. If the PDP
publishes metadata that leaves out an optional API, the PDP does not offer that API, and calling it
fails without contacting the PDP.

## Overriding endpoints

If a PDP publishes no metadata and serves an API at another path, set the endpoint explicitly with
`evaluationEndpoint(URI)`, `evaluationsEndpoint(URI)`, `searchSubjectEndpoint(URI)`,
`searchResourceEndpoint(URI)` or `searchActionEndpoint(URI)`. An override replaces only its own
endpoint, also when discovery is on; the other APIs still use the discovered endpoint or the
default path, which may be on another host.
