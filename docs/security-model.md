# Security model

## Fail-closed

The client returns a decision only when the PDP gave one in a valid response. Every other outcome
throws an `AuthZenException`, which the application treats as a deny: an unreachable PDP, a
timeout, an open circuit breaker, a `4xx` or `5xx` status, invalid metadata or an invalid response.

The client has no fail-open mode. An application that must allow access while the PDP is down
decides that itself when it catches the exception, and should log and count those decisions.
Listeners see such failed calls too; see [Observability](observability.md).

The `Enforcer` fails closed as well. Its `EnforcementPolicy` can fail open, for the whole
application, but only while the PDP is unavailable (a `PdpCommunicationException`); a rejected
PEP credential, an invalid response and every other failure still deny. It logs a warning when
it is built and whenever it lets a request through, and reports each such request; see
[Enforcement](enforcement.md#policy).

A deny from the PDP is not an error: it is a `200` response with `"decision": false`. A `401` or
`403` means that the PEP itself may not ask the PDP, not that the subject was denied; it throws a
`PdpAuthenticationException`.

## What the client checks

Responses:

- Only `200` is accepted as an answer (and `404` for the metadata request, meaning no metadata).
- The body must be valid UTF-8 and valid JSON, at most 8 MiB, nested at most 64 levels deep.
- An evaluation response must contain `decision` as a JSON boolean. A batch response must not
  contain more decisions than the request had items, or fewer under `EXECUTE_ALL`; a missing
  decision is never a permit.
- If the response echoes `X-Request-ID`, it must be the value the client sent, so that a mixed-up
  response is never taken as the answer. A missing echo is accepted, since the specification only
  recommends it.

URLs and metadata:

- The PDP URL and every endpoint must be absolute `https` or `http` URLs without user info, query
  or fragment.
- The metadata's `policy_decision_point` must equal the PDP URL exactly.
- Metadata that lists an `http` endpoint for an `https` PDP is rejected, since it would send
  credentials unencrypted. With credentials and a plain `http` endpoint on a host other than the
  loopback interface, the client logs a warning.
- Redirects are never followed, so credentials cannot be sent to another host. An HTTP client
  passed with `httpClient(...)` must not follow them either.

## What the client sends

| Header | When | Value |
|---|---|---|
| `Authorization` | A credential provider is configured; never on the metadata request | `Bearer <token>` |
| `X-Request-ID` | Every request | A fresh value per HTTP exchange, retries included |
| `User-Agent` | Every request | `authzen-enforcer/<version>` |
| `Accept`, `Content-Type` | Every request (`Content-Type` with a body) | `application/json` |
| `traceparent`, `tracestate` | A tracer is configured; not on the metadata request | W3C Trace Context; baggage is never sent |

The request body is the AuthZEN request as built. Data that the specification has no field for
belongs in the `properties` of the subject, resource or action, or in the context.

## Personal data and secrets

- Tokens and other credentials are never logged and never part of a listener event. The
  `toString()` of a credential does not reveal it.
- The `toString()` of the model types masks values that may hold personal data: the subject id and
  the values of all properties and of the context become `***`; names stay visible. Resource ids
  and action names stay visible.
- Listener events are masked the same way by default; see [Observability](observability.md).
- Metric tags and span attributes carry no identifiers. `url.full` on spans shows the PDP endpoint,
  so keep secrets out of the PDP URL.

## What the application is responsible for

- **Obtaining and protecting the token.** The SDK never talks to an authorization server; see
  [Credentials](credentials.md).
- **Validating request values.** The model follows the specification and does not reject what it
  allows, such as an empty id. Values that come from users, such as path variables, should be
  validated before they become part of a request.
- **Enforcing with evaluations.** A search result is not an access decision; check access with
  `evaluate` before acting on it.
