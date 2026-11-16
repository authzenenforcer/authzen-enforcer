package io.github.authzenenforcer.spring;

import io.github.authzenenforcer.core.auth.AccessCredential;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.spring.internal.OAuth2ClientCredentials;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;

import java.util.List;
import java.util.UUID;

import static io.github.authzenenforcer.spring.AutoConfigurationTest.rootMessage;
import static io.github.authzenenforcer.spring.AutoConfigurationTest.warnings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The PEP's token from Spring Security's OAuth 2.0 client. */
class OAuth2ClientConfigurationTest {

    private static final FakePdp PDP = new FakePdp();
    private static final String REGISTRATION = "spring.security.oauth2.client.registration.pdp.";

    private final FakeTokenEndpoint tokens = new FakeTokenEndpoint();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class,
                    AuthZenAutoConfiguration.class))
            .withPropertyValues("authzen.pdp.url=" + PDP.url(), "authzen.pdp.discovery=false",
                    "authzen.pdp.client-registration-id=pdp",
                    REGISTRATION + "client-id=pep",
                    // Only the fake token endpoint sees it
                    REGISTRATION + "client-secret=" + UUID.randomUUID(),
                    REGISTRATION + "authorization-grant-type=client_credentials",
                    "spring.security.oauth2.client.provider.pdp.token-uri=" + tokens.tokenUri());

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
            assertInstanceOf(OAuth2ClientCredentials.class, context.getBean(CredentialProvider.class));

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
        OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> responseClient =
                grant -> OAuth2AccessTokenResponse.withToken("from-the-application")
                        .tokenType(OAuth2AccessToken.TokenType.BEARER)
                        .expiresIn(300)
                        .build();

        runner.withBean(OAuth2AccessTokenResponseClient.class, () -> responseClient).run(context -> {
            AccessCredential credential = context.getBean(CredentialProvider.class).getCredential();

            assertEquals("from-the-application", ((AccessCredential.Bearer) credential).getToken());
            assertEquals(0, tokens.requests().size());
        });
    }

    @Test
    void letsTheApplicationsCredentialProviderWinAndWarns() {
        CredentialProvider own = () -> AccessCredential.bearer("own");

        List<String> warnings = warnings(AuthZenAutoConfiguration.class, () -> runner
                .withBean(CredentialProvider.class, () -> own)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertSame(own, context.getBean(CredentialProvider.class));
                }));

        assertEquals(List.of("authzen.pdp.client-registration-id is ignored: the CredentialProvider bean of the"
                + " application supplies the PEP's credential"), warnings);
    }

    @Test
    void failsToStartWithAnUnknownRegistration() {
        runner.withPropertyValues("authzen.pdp.client-registration-id=other").run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(rootMessage(context.getStartupFailure()).contains("names 'other', but there is no such"
                    + " registration"));
        });
    }

    @Test
    void failsToStartWithoutRegistrations() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class,
                        AuthZenAutoConfiguration.class))
                .withPropertyValues("authzen.pdp.url=" + PDP.url(), "authzen.pdp.client-registration-id=pdp")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(rootMessage(context.getStartupFailure()).contains("no such registration"));
                });
    }

    @Test
    void failsToStartWithARegistrationOfAnotherGrant() {
        runner.withPropertyValues(REGISTRATION + "authorization-grant-type=authorization_code",
                        REGISTRATION + "redirect-uri=http://localhost/callback",
                        "spring.security.oauth2.client.provider.pdp.authorization-uri=http://localhost/authorize")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(rootMessage(context.getStartupFailure()).contains("must use the client_credentials"
                            + " grant, not authorization_code"));
                });
    }

    @Test
    void failsToStartWithABlankRegistration() {
        runner.withPropertyValues("authzen.pdp.client-registration-id= ").run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(rootMessage(context.getStartupFailure()).contains("must name a registration"));
        });
    }

    @Test
    void failsToStartWithoutSpringSecuritysOAuth2Client() {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.security.oauth2.client"))
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(rootMessage(context.getStartupFailure())
                            .contains("add spring-boot-starter-security-oauth2-client"));
                });
    }

    private static EvaluationRequest request() {
        return EvaluationRequest.of(Subject.of("user", "alice"), Action.of("read"), Resource.of("document", "d-1"));
    }
}
