package io.github.authzenenforcer.spring.internal;

import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Supplies the PEP's bearer token from a client registration of Spring Security's servlet OAuth 2.0
 * client, obtained with the client credentials grant; see {@link AbstractOAuth2ClientCredentials}.
 *
 * <p>A single caller obtains a new token on its own thread. A token request that takes longer than
 * the refresh timeout fails the callers that wait for it; the thread that sent it stays blocked
 * until the token client gives up.
 *
 * <p>The manager must not depend on an HTTP request of the application: the client also asks for
 * the credential on its own threads, to repeat a request.
 */
public final class OAuth2ClientCredentials extends AbstractOAuth2ClientCredentials {

    /** The principal the PEP's authorized client is stored under; one token serves every request. */
    public static final String PRINCIPAL = AbstractOAuth2ClientCredentials.PRINCIPAL;

    private final OAuth2AuthorizedClientManager manager;
    private final OAuth2AuthorizedClientService clients;

    /**
     * Creates the provider.
     *
     * @param registrationId the client registration, with the client credentials grant
     * @param manager        obtains and stores the authorized client
     * @param clients        stores the authorized client, so that a rejected one can be removed
     * @param refreshTimeout how long callers wait for a token request before another may start
     * @throws IllegalArgumentException if the refresh timeout is not positive
     */
    public OAuth2ClientCredentials(String registrationId, OAuth2AuthorizedClientManager manager,
            OAuth2AuthorizedClientService clients, Duration refreshTimeout) {
        this(registrationId, manager, clients, refreshTimeout, Clock.systemUTC());
    }

    OAuth2ClientCredentials(String registrationId, OAuth2AuthorizedClientManager manager,
            OAuth2AuthorizedClientService clients, Duration refreshTimeout, Clock clock) {
        super(registrationId, refreshTimeout, clock);
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.clients = Objects.requireNonNull(clients, "clients must not be null");
    }

    @Override
    CompletableFuture<OAuth2AuthorizedClient> authorize() {
        return CompletableFuture.completedFuture(manager.authorize(OAuth2AuthorizeRequest
                .withClientRegistrationId(registrationId())
                .principal(PRINCIPAL)
                .build()));
    }

    @Override
    CompletableFuture<OAuth2AuthorizedClient> loadStored() {
        return CompletableFuture.completedFuture(clients.loadAuthorizedClient(registrationId(), PRINCIPAL));
    }

    @Override
    CompletableFuture<Void> removeStored() {
        clients.removeAuthorizedClient(registrationId(), PRINCIPAL);
        return CompletableFuture.completedFuture(null);
    }
}
