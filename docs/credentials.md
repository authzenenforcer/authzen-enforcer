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

The client asks the provider before every request, including a repeated one, and the time the
provider takes counts toward the request timeout: a credential that does not arrive within it fails
the request without sending it. If the provider throws or supplies no credential, the request fails
without being sent as well.

`getCredential()` is called synchronously, on the thread that starts or repeats the request, which
may be a thread of the HTTP client. It should return quickly from a cache and refresh the token
shortly before it expires. A call that blocks also blocks that thread, and the request timeout
cannot interrupt it. While a circuit breaker tests whether a failing PDP has recovered, it lets a
single request through; if that request blocks in the provider, every other request is rejected
until it returns. If the token source is asynchronous, write an
[asynchronous provider](#asynchronous-providers) instead.

If the PDP answers `401`, the client calls `invalidate(rejected)` on the provider and repeats the
request once with a fresh credential. Concurrent requests may report the same rejected credential,
so a provider should only drop its token if `rejected` is still the current one.

A provider for the client credentials grant could look like this, where `CachedToken` and
`fetchToken()` are your own code that calls the token endpoint of your authorization server. Only
one caller refreshes the token at a time; callers that need a token meanwhile wait for its result
instead of sending token requests of their own:

```java
final class ClientCredentialsTokens implements CredentialProvider {

    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final ReentrantLock refresh = new ReentrantLock();
    private volatile CachedToken current;

    @Override
    public AccessCredential getCredential() {
        CachedToken token = current;
        if (isUsable(token)) {
            return token.credential();
        }
        refresh.lock();
        try {
            // Another caller may have refreshed the token while this one waited
            token = current;
            if (!isUsable(token)) {
                token = fetchToken();
                current = token;
            }
            return token.credential();
        } finally {
            refresh.unlock();
        }
    }

    @Override
    public void invalidate(AccessCredential rejected) {
        refresh.lock();
        try {
            if (current != null && current.credential() == rejected) {
                current = null;
            }
        } finally {
            refresh.unlock();
        }
    }

    private static boolean isUsable(CachedToken token) {
        return token != null && !token.expiresWithin(EXPIRY_MARGIN);
    }
}
```

The waiting callers block until the token endpoint answers, so give the token request a timeout of
its own. The example uses `ReentrantLock` rather than `synchronized`, which on Java 21 to 23 would
pin a virtual thread to its carrier while the token request runs.

Providers must be thread-safe.

## Asynchronous providers

When the token source returns a `CompletionStage`, such as the reactive OAuth client of a
framework, a provider that blocks for it would hold a thread that must not block, for example an
event loop thread. Create the provider with `CredentialProvider.async` instead; the client then
waits for the stage without blocking a thread:

```java
AuthZenClient client = AuthZenClient.builder()
        .pdpUrl("https://pdp.example.com")
        .credentials(CredentialProvider.async(() -> tokenSource.currentToken()
                .thenApply(AccessCredential::bearer)))
        .build();
```

To refresh the token after the PDP answered `401`, implement `CredentialProvider` and override
`getCredentialAsync()` and `invalidate(rejected)`; `getCredential()` may then wait for the result of
`getCredentialAsync()`, since the client does not call it. The client never cancels or completes the
provider's stage, even when it gives up waiting, so a provider may hand the same stage to every
request while a single token refresh is in progress. It registers at most one callback on each
stage, so requests that gave up waiting leave nothing behind on a stage that never completes. Hand
out the stage itself: a new view of it per request, such as `thenApply(...)` or
`minimalCompletionStage()`, is a new stage with a callback of its own.
Still, give the token request a timeout of its own: until it completes, every request fails once
its request timeout passes.

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
