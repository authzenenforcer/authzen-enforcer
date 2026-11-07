# Credentials

The SDK never talks to an authorization server. Obtaining and refreshing tokens is up to the
application, typically by delegating to the OAuth client of its framework. The client asks a
`CredentialProvider` for the credential before every request:

```java
AuthZenClient client = AuthZenClient.builder()
        .pdpUrl("https://pdp.example.com")
        .credentials(CredentialProvider.bearer(tokenSource::currentToken))
        .build();
```

Without a provider, requests carry no `Authorization` header. The metadata request never carries
credentials.

## Writing a provider

The provider is called synchronously on the request path, and no request timeout covers it, so it
should return quickly from a cache and refresh the token shortly before it expires. A call that
blocks also blocks the request. While a circuit breaker tests whether a failing PDP has recovered,
it lets a single request through; if that request blocks in the provider, every other request is
rejected until it returns. If the provider throws, the request fails without being sent.

If the PDP answers `401`, the client calls `invalidate(rejected)` on the provider and repeats the
request once with a fresh credential. Concurrent requests may report the same rejected credential,
so a provider should only drop its token if `rejected` is still the current one.

A provider for the client credentials grant could look like this, where `CachedToken` and
`fetchToken()` are your own code that calls the token endpoint of your authorization server:

```java
final class ClientCredentialsTokens implements CredentialProvider {

    private final AtomicReference<CachedToken> current = new AtomicReference<>();

    @Override
    public AccessCredential getCredential() {
        CachedToken token = current.get();
        if (token == null || token.expiresWithin(Duration.ofSeconds(30))) {
            // Concurrent callers may fetch at the same time; the last token wins
            token = fetchToken();
            current.set(token);
        }
        return token.credential();
    }

    @Override
    public void invalidate(AccessCredential rejected) {
        current.updateAndGet(token -> token != null && token.credential() == rejected ? null : token);
    }
}
```

Providers must be thread-safe.

## Static tokens

`CredentialProvider.staticBearer(token)` always presents the same token. It is meant for
development and tests only: access tokens expire, often within minutes, and this provider cannot
refresh them.

## Plain HTTP

When credentials are configured and a PDP endpoint uses plain `http` on a host other than the
loopback interface, the client logs a warning when it is built, since the token would travel
unencrypted. Metadata that lists an `http` endpoint for an `https` PDP is rejected; see
[Security model](security-model.md).

## DPoP

DPoP-bound tokens (RFC 9449) are planned. Only bearer tokens are supported today.
