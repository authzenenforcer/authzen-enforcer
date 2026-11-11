package io.github.authzenenforcer.spring;

import io.github.authzenenforcer.core.auth.AccessCredential;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.enforce.EnforcementPolicy;
import io.github.authzenenforcer.core.enforce.Enforcer;
import io.github.authzenenforcer.core.enforce.SubjectResolver;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.spring.servlet.AuthZenWebMvcAutoConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuthZenAutoConfiguration.class));

    @Test
    void failsToStartWithoutThePdpUrl() {
        runner.run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(rootMessage(context.getStartupFailure()).contains("authzen.pdp.url"));
        });
    }

    @Test
    void createsTheClientAndTheEnforcerFromProperties() {
        runner.withPropertyValues("authzen.pdp.url=https://pdp.example.com", "authzen.enforcer.error-status=503")
                .run(context -> {
                    assertNotNull(context.getBean(AuthZenClient.class));
                    assertEquals(503, context.getBean(Enforcer.class).getPolicy().getErrorStatus());
                });
    }

    @Test
    void startsWithTheApplicationsClientWithoutTheUrl() {
        AuthZenClient client = AuthZenClient.builder().pdpUrl("https://pdp.example.com").build();
        runner.withBean(AuthZenClient.class, () -> client).run(context -> {
            assertFalse(context.getStartupFailure() != null);
            assertSame(client, context.getBean(AuthZenClient.class));
        });
    }

    @Test
    void failsToStartWithTwoCredentialProviders() {
        runner.withPropertyValues("authzen.pdp.url=https://pdp.example.com")
                .withBean("first", CredentialProvider.class, () -> () -> AccessCredential.bearer("a"))
                .withBean("second", CredentialProvider.class, () -> () -> AccessCredential.bearer("b"))
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void mapsClaimsToSubjectProperties() {
        runner.withPropertyValues("authzen.pdp.url=https://pdp.example.com",
                        "authzen.enforcer.subject.id-claim=oid", "authzen.enforcer.subject.type=person",
                        "authzen.enforcer.subject.properties.roles=realm_access.roles")
                .run(context -> {
                    Subject subject = context.getBean(SubjectResolver.class).resolve(Map.of("oid", "o-1",
                            "realm_access", Map.of("roles", List.of("admin"))));
                    assertEquals("person", subject.getType());
                    assertEquals("o-1", subject.getId());
                    assertEquals(Map.of("roles", List.of("admin")), subject.getProperties());
                });
    }

    @Test
    void appliesTheKeycloakProfile() {
        runner.withPropertyValues("authzen.pdp.url=https://pdp.example.com",
                        "authzen.enforcer.subject.profile=keycloak")
                .run(context -> {
                    Subject subject = context.getBean(SubjectResolver.class).resolve(Map.of("sub", "s-1",
                            "client_id", "billing", "groups", List.of("ops")));
                    assertEquals("client", subject.getType());
                    assertEquals(Map.of("groups", List.of("ops")), subject.getProperties());
                });
    }

    @Test
    void refusesAnEmptyClaimPath() {
        runner.withPropertyValues("authzen.pdp.url=https://pdp.example.com",
                        "authzen.enforcer.subject.properties.roles=realm_access..roles")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void letsBeansOfTheApplicationReplaceTheProperties() {
        EnforcementPolicy policy = EnforcementPolicy.builder().errorStatus(503).build();
        runner.withPropertyValues("authzen.pdp.url=https://pdp.example.com")
                .withBean(EnforcementPolicy.class, () -> policy)
                .run(context -> assertSame(policy, context.getBean(Enforcer.class).getPolicy()));
    }

    @Test
    void failsToStartWithAHandlerAnnotatedToCheckAndToSkip() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class, AuthZenAutoConfiguration.class,
                        AuthZenWebMvcAutoConfiguration.class))
                .withUserConfiguration(Contradiction.class)
                .withPropertyValues("authzen.pdp.url=https://pdp.example.com")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(rootMessage(context.getStartupFailure()).contains("both"));
                });
    }

    @Test
    void failsToStartWithAnInvalidExpression() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class, AuthZenAutoConfiguration.class,
                        AuthZenWebMvcAutoConfiguration.class))
                .withUserConfiguration(InvalidExpression.class)
                .withPropertyValues("authzen.pdp.url=https://pdp.example.com")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(rootMessage(context.getStartupFailure()).contains("expression"));
                });
    }

    private static String rootMessage(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }

    @RestController
    static class Contradiction {

        @CheckPermission
        @SkipPermissionCheck
        @GetMapping("/things")
        String things() {
            return "things";
        }
    }

    @RestController
    static class InvalidExpression {

        @CheckPermission(context = "ip=#request.(")
        @GetMapping("/things")
        String things() {
            return "things";
        }
    }
}
