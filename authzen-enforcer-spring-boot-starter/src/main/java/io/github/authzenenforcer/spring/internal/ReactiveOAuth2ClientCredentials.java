package io.github.authzenenforcer.spring.internal;

import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Supplies the PEP's bearer token from a client registration of Spring Security's reactive OAuth 2.0
 * client, obtained with the client credentials grant; see {@link AbstractOAuth2ClientCredentials}.
 *
 * <p>No thread waits for a token request. One that takes longer than the refresh timeout is
 * cancelled, which matters because Spring Security's default reactive token client has no response
 * timeout of its own.
 *
 * <p>The manager must not depend on an HTTP request of the application: the client also asks for
 * the credential on its own threads, to repeat a request.
 */
public final class ReactiveOAuth2ClientCredentials extends AbstractOAuth2ClientCredentials {

    private final ReactiveOAuth2AuthorizedClientManager manager;
    private final ReactiveOAuth2AuthorizedClientService clients;

    /**
     * Creates the provider.
     *
     * @param registrationId the client registration, with the client credentials grant
     * @param manager        obtains and stores the authorized client
     * @param clients        stores the authorized client, so that a rejected one can be removed
     * @param refreshTimeout how long callers wait for a token request before it is cancelled
     * @throws IllegalArgumentException if the refresh timeout is not positive
     */
    public ReactiveOAuth2ClientCredentials(String registrationId, ReactiveOAuth2AuthorizedClientManager manager,
            ReactiveOAuth2AuthorizedClientService clients, Duration refreshTimeout) {
        this(registrationId, manager, clients, refreshTimeout, Clock.systemUTC());
    }

    ReactiveOAuth2ClientCredentials(String registrationId, ReactiveOAuth2AuthorizedClientManager manager,
            ReactiveOAuth2AuthorizedClientService clients, Duration refreshTimeout, Clock clock) {
        super(registrationId, refreshTimeout, clock);
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.clients = Objects.requireNonNull(clients, "clients must not be null");
    }

    // Cancelling these futures cancels their subscriptions
    @Override
    CompletableFuture<OAuth2AuthorizedClient> authorize() {
        return manager.authorize(OAuth2AuthorizeRequest
                .withClientRegistrationId(registrationId())
                .principal(PRINCIPAL)
                .build()).toFuture();
    }

    @Override
    CompletableFuture<OAuth2AuthorizedClient> loadStored() {
        return clients.<OAuth2AuthorizedClient>loadAuthorizedClient(registrationId(), PRINCIPAL).toFuture();
    }

    @Override
    CompletableFuture<Void> removeStored() {
        return clients.removeAuthorizedClient(registrationId(), PRINCIPAL).toFuture();
    }
}
