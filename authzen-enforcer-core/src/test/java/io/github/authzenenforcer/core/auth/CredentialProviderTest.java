package io.github.authzenenforcer.core.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CredentialProviderTest {

    @ParameterizedTest
    @ValueSource(strings = {"abc", "a-b.c_d~e+f/g", "dG9rZW4=", "dG9r==",
            "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJl"})
    void acceptsRfc6750Tokens(String token) {
        assertEquals(token, AccessCredential.bearer(token).getToken());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "a b", "abc\r\nX-Injected: 1", "abc\n", "=abc", "a=b", "tök"})
    void rejectsTokensOutsideRfc6750(String token) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AccessCredential.bearer(token));
        assertFalse(!token.isBlank() && e.getMessage().contains(token));
    }

    @Test
    void rejectsNullToken() {
        NullPointerException e = assertThrows(NullPointerException.class, () -> AccessCredential.bearer(null));
        assertEquals("token must not be null", e.getMessage());
    }

    @Test
    void masksTheTokenInToString() {
        assertEquals("Bearer{token=***}", AccessCredential.bearer("secret-token").toString());
    }

    @Test
    void bearerProviderAsksTheSupplierEveryTime() {
        AtomicInteger calls = new AtomicInteger();
        CredentialProvider provider = CredentialProvider.bearer(() -> "token-" + calls.incrementAndGet());

        assertEquals("token-1", ((AccessCredential.Bearer) provider.getCredential()).getToken());
        assertEquals("token-2", ((AccessCredential.Bearer) provider.getCredential()).getToken());
    }

    @Test
    void staticBearerProviderReturnsTheSameCredential() {
        CredentialProvider provider = CredentialProvider.staticBearer("token");

        assertSame(provider.getCredential(), provider.getCredential());
        assertThrows(IllegalArgumentException.class, () -> CredentialProvider.staticBearer("bad token"));
    }

    @Test
    void returnsTheSynchronousCredentialAsACompletedStage() {
        AccessCredential credential = AccessCredential.bearer("token");
        CredentialProvider provider = () -> credential;

        assertSame(credential, provider.getCredentialAsync().toCompletableFuture().getNow(null));
    }

    @Test
    void throwsFromTheDefaultStageWhenTheProviderFails() {
        CredentialProvider provider = () -> {
            throw new IllegalStateException("token endpoint down");
        };

        assertThrows(IllegalStateException.class, provider::getCredentialAsync);
    }

    @Test
    void asyncProviderAsksTheSourceEveryTime() {
        AtomicInteger calls = new AtomicInteger();
        CredentialProvider provider = CredentialProvider.async(
                () -> CompletableFuture.completedFuture(AccessCredential.bearer("token-" + calls.incrementAndGet())));

        assertEquals("token-1", ((AccessCredential.Bearer) provider.getCredentialAsync().toCompletableFuture().join()).getToken());
        assertEquals("token-2", ((AccessCredential.Bearer) provider.getCredential()).getToken());
    }

    @Test
    void asyncProviderWaitsForTheSourceWhenCalledSynchronously() {
        CompletionStage<String> token = CompletableFuture.supplyAsync(() -> "token",
                CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS));
        // The documented shape: a token stage mapped to a credential, here without toCompletableFuture()
        CredentialProvider provider = CredentialProvider.async(
                () -> token.thenApply(AccessCredential::bearer).toCompletableFuture().minimalCompletionStage());

        assertEquals("token", ((AccessCredential.Bearer) provider.getCredential()).getToken());
    }

    @Test
    void asyncProviderRethrowsTheFailureOfTheSource() {
        IllegalStateException failure = new IllegalStateException("token endpoint down");
        CredentialProvider provider = CredentialProvider.async(() -> CompletableFuture.failedFuture(failure));

        assertSame(failure, assertThrows(IllegalStateException.class, provider::getCredential));
    }

    @Test
    void asyncProviderReturnsNullWithoutAStage() {
        CredentialProvider provider = CredentialProvider.async(() -> null);

        assertEquals(null, provider.getCredential());
    }

    @Test
    void asyncProviderRejectsANullSource() {
        NullPointerException e = assertThrows(NullPointerException.class, () -> CredentialProvider.async(null));
        assertEquals("source must not be null", e.getMessage());
    }
}
