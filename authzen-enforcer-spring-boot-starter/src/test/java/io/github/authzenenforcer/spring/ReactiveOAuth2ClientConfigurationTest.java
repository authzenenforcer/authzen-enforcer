package io.github.authzenenforcer.spring;

import io.github.authzenenforcer.core.auth.AccessCredential;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.spring.internal.OAuth2ClientCredentials;
import io.github.authzenenforcer.spring.internal.ReactiveOAuth2ClientCredentials;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.reactive.ReactiveOAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.security.oauth2.client.endpoint.ReactiveOAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static io.github.authzenenforcer.spring.AutoConfigurationTest.rootMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The PEP's token from Spring Security's reactive OAuth 2.0 client. */
class ReactiveOAuth2ClientConfigurationTest {

    private static final FakePdp PDP = new FakePdp();
    private static final String REGISTRATION = "spring.security.oauth2.client.registration.pdp.";

    private final FakeTokenEndpoint tokens = new FakeTokenEndpoint();

    private final String[] properties = {"authzen.pdp.url=" + PDP.url(), "authzen.pdp.discovery=false",
            "authzen.pdp.client-registration-id=pdp",
            REGISTRATION + "client-id=pep",
            // Only the fake token endpoint sees it
            REGISTRATION + "client-secret=" + UUID.randomUUID(),
            REGISTRATION + "authorization-grant-type=client_credentials",
            "spring.security.oauth2.client.provider.pdp.token-uri=" + tokens.tokenUri()};

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class,
                    ReactiveOAuth2ClientAutoConfiguration.class, AuthZenAutoConfiguration.class))
            .withPropertyValues(properties);

    @AfterEach
    void reset() {
        tokens.close();
        PDP.reset();
    }

    @AfterAll
    static void stop() {
        PDP.close();
    }

    @Test
    void sendsTheTokenOfTheRegistration() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertInstanceOf(ReactiveOAuth2ClientCredentials.class, context.getBean(CredentialProvider.class));

            assertTrue(context.getBean(AuthZenClient.class).evaluate(request()).getDecision());

            assertEquals(List.of("Bearer t1"), PDP.authorizations());
            assertEquals(1, tokens.requests().size());
        });
    }

    @Test
    void obtainsANewTokenWhenThePdpRejectsTheCurrentOne() {
        PDP.authorize("Bearer t2"::equals);

        runner.run(context -> {
            assertTrue(context.getBean(AuthZenClient.class).evaluate(request()).getDecision());

            assertEquals(List.of("Bearer t1", "Bearer t2"), PDP.authorizations());
            assertEquals(2, tokens.requests().size());
        });
    }

    @Test
    void obtainsTheTokenWithTheApplicationsTokenResponseClient() {
        ReactiveOAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> responseClient =
                grant -> Mono.just(OAuth2AccessTokenResponse.withToken("from-the-application")
                        .tokenType(OAuth2AccessToken.TokenType.BEARER)
                        .expiresIn(300)
                        .build());

        runner.withBean(ReactiveOAuth2AccessTokenResponseClient.class, () -> responseClient).run(context -> {
            AccessCredential credential = context.getBean(CredentialProvider.class).getCredential();

            assertEquals("from-the-application", ((AccessCredential.Bearer) credential).getToken());
            assertEquals(0, tokens.requests().size());
        });
    }

    @Test
    void failsToStartWithAnUnknownRegistration() {
        runner.withPropertyValues("authzen.pdp.client-registration-id=other").run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(rootMessage(context.getStartupFailure()).contains("no such registration"));
        });
    }

    @Test
    void failsToStartWithARegistrationOfAnotherGrant() {
        runner.withPropertyValues(REGISTRATION + "authorization-grant-type=authorization_code",
                        REGISTRATION + "redirect-uri=https://app.example.com/callback",
                        "spring.security.oauth2.client.provider.pdp.authorization-uri=https://as.example.com/auth")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(rootMessage(context.getStartupFailure()).contains("client_credentials grant"));
                });
    }

    @Test
    void prefersTheServletClientWhereSpringBootCreatesBoth() {
        // Outside web applications, Spring Boot creates the servlet and the reactive repositories
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class,
                        ReactiveOAuth2ClientAutoConfiguration.class, AuthZenAutoConfiguration.class))
                .withPropertyValues(properties)
                .run(context -> assertInstanceOf(OAuth2ClientCredentials.class,
                        context.getBean(CredentialProvider.class)));
    }

    private static EvaluationRequest request() {
        return EvaluationRequest.builder()
                .subject(Subject.of("user", "alice"))
                .action(Action.of("read"))
                .resource(Resource.of("document", "1"))
                .build();
    }
}
