package io.github.authzenenforcer.spring;

import io.github.authzenenforcer.core.auth.AccessCredential;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.enforce.EnforcementPolicy;
import io.github.authzenenforcer.core.enforce.Enforcer;
import io.github.authzenenforcer.core.enforce.SubjectResolver;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.spring.internal.HandlerAdapterOrderCheck;
import io.github.authzenenforcer.spring.internal.ProtectedRoutesCheck;
import io.github.authzenenforcer.spring.internal.UnenforcedAnnotationsCheck;
import io.github.authzenenforcer.spring.reactive.AuthZenWebFluxAutoConfiguration;
import io.github.authzenenforcer.spring.servlet.AuthZenWebMvcAutoConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.core.PriorityOrdered;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerAdapter;
import org.springframework.web.reactive.HandlerResult;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void warnsAboutAnnotationsOfApplicationsWithoutWebEnforcement() {
        List<String> warnings = warnings(() -> runner.withPropertyValues("authzen.pdp.url=https://pdp.example.com")
                .withUserConfiguration(Service.class)
                .run(context -> assertNull(context.getStartupFailure())));

        assertEquals(List.of("@CheckPermission on " + Service.class.getName() + ".approve is not enforced: this application"
                + " has no Spring MVC or Spring WebFlux enforcement"), warnings);
    }

    @Test
    void warnsAboutAnnotatedBeansThatAreNotControllers() {
        List<String> warnings = warnings(() -> new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class, AuthZenAutoConfiguration.class,
                        AuthZenWebMvcAutoConfiguration.class))
                .withUserConfiguration(Service.class, Controller.class)
                .withPropertyValues("authzen.pdp.url=https://pdp.example.com")
                .run(context -> assertNull(context.getStartupFailure())));

        assertEquals(List.of(
                "@CheckPermission on " + Service.class.getName() + ".approve is not enforced: only handler methods"
                        + " of controllers are enforced",
                "@CheckPermission on " + Controller.class.getName() + ".helper is not enforced: only handler methods"
                        + " of controllers are enforced"), warnings);
    }

    @Test
    void warnsAboutExplicitValuesThatBlurACollection() {
        List<String> warnings = warnings(ProtectedRoutesCheck.class, () -> new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class, AuthZenAutoConfiguration.class,
                        AuthZenWebMvcAutoConfiguration.class))
                .withUserConfiguration(Collections.class)
                .withPropertyValues("authzen.pdp.url=https://pdp.example.com")
                .run(context -> assertNull(context.getStartupFailure())));

        String handlers = Collections.class.getName();
        assertEquals(List.of(
                handlers + ".chosen takes the id of the collection /folders from the request, so callers choose which"
                        + " collection the PDP is asked about. Use a literal id, such as resourceId = \"'documents'\"",
                handlers + ".named sets the type of the collection /drawers: the PDP is asked about {type: <that type>,"
                        + " id: <collection>}, as about a single resource of that type with that id. Give collections"
                        + " a type that no single resource has",
                handlers + ".typed sets the type of the collection /papers: the PDP is asked about {type: <that type>,"
                        + " id: <collection>}, as about a single resource of that type with that id. Give collections"
                        + " a type that no single resource has",
                handlers + ".versions sets the type of the collection /papers/{id}/versions: the PDP is asked about"
                        + " {type: <that type>, id: <collection>}, as about a single resource of that type with that id."
                        + " Give collections a type that no single resource has",
                handlers + ".versions takes the id of the collection /papers/{id}/versions from the request, so callers"
                        + " choose which collection the PDP is asked about. Use a literal id, such as resourceId ="
                        + " \"'documents'\""),
                warnings.stream().sorted().toList());
    }

    @Test
    void checksWebFluxRoutesAtStartupAsSpringMvcRoutes() {
        reactive().withUserConfiguration(InvalidExpression.class).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(rootMessage(context.getStartupFailure()).contains("expression"));
        });
        reactive().withUserConfiguration(Contradiction.class).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(rootMessage(context.getStartupFailure()).contains("both"));
        });

        List<String> warnings = warnings(ProtectedRoutesCheck.class, () -> reactive()
                .withUserConfiguration(Collections.class)
                .run(context -> assertNull(context.getStartupFailure())));
        assertEquals(5, warnings.size());
        assertTrue(warnings.stream().allMatch(warning -> warning.startsWith(Collections.class.getName())), warnings::toString);
    }

    @Test
    void warnsAboutAnnotatedBeansThatAreNotWebFluxControllers() {
        List<String> warnings = warnings(() -> reactive()
                .withUserConfiguration(Service.class, Controller.class)
                .run(context -> assertNull(context.getStartupFailure())));

        assertEquals(List.of(
                "@CheckPermission on " + Service.class.getName() + ".approve is not enforced: only handler methods"
                        + " of controllers are enforced",
                "@CheckPermission on " + Controller.class.getName() + ".helper is not enforced: only handler methods"
                        + " of controllers are enforced"), warnings);
    }

    @Test
    void warnsAboutAHandlerAdapterOrderedBeforeEnforcement() {
        List<String> warnings = warnings(HandlerAdapterOrderCheck.class, () -> reactive()
                .withUserConfiguration(Controller.class)
                .withBean(HandlerAdapter.class, FirstAdapter::new)
                .run(context -> assertNull(context.getStartupFailure())));

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).startsWith("The handler adapter " + FirstAdapter.class.getName() + " runs "),
                warnings.get(0));

        assertTrue(warnings(HandlerAdapterOrderCheck.class, () -> reactive()
                .withUserConfiguration(Controller.class)
                .run(context -> assertNull(context.getStartupFailure()))).isEmpty());
    }

    /** Runs handler methods ahead of every ordered adapter. */
    static class FirstAdapter implements HandlerAdapter, PriorityOrdered {

        @Override
        public int getOrder() {
            return 0;
        }

        @Override
        public boolean supports(Object handler) {
            return handler instanceof HandlerMethod;
        }

        @Override
        public Mono<HandlerResult> handle(ServerWebExchange exchange, Object handler) {
            return Mono.empty();
        }
    }

    private static ReactiveWebApplicationContextRunner reactive() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class, AuthZenAutoConfiguration.class,
                        AuthZenWebFluxAutoConfiguration.class))
                .withPropertyValues("authzen.pdp.url=https://pdp.example.com");
    }

    private static List<String> warnings(Runnable run) {
        return warnings(UnenforcedAnnotationsCheck.class, run);
    }

    // System.Logger writes to java.util.logging when no other logger finder is installed
    static List<String> warnings(Class<?> source, Runnable run) {
        Logger logger = Logger.getLogger(source.getName());
        List<String> warnings = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == java.util.logging.Level.WARNING) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            run.run();
        } finally {
            logger.removeHandler(handler);
        }
        return warnings;
    }

    static String rootMessage(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }

    static class Service {

        @CheckPermission(action = "approve")
        void approve() {
            // Not a handler method
        }
    }

    @RestController
    static class Controller {

        @CheckPermission
        @GetMapping("/documents/{id}")
        String document() {
            return "document";
        }

        @CheckPermission(action = "audit")
        String helper() {
            return "not a handler";
        }
    }

    @RestController
    static class Collections {

        @CheckPermission(resourceType = "belge")
        @GetMapping("/papers")
        String typed() {
            return "papers";
        }

        @CheckPermission(resourceId = "#request.parameter('folder')")
        @GetMapping("/folders")
        String chosen() {
            return "folders";
        }

        @CheckPermission(resourceId = "'documents'")
        @GetMapping("/documents")
        String literal() {
            return "documents";
        }

        // A literal id keeps it a collection, which the type makes look like a single resource
        @CheckPermission(resourceType = "folder", resourceId = "'drawers'")
        @GetMapping("/drawers")
        String named() {
            return "drawers";
        }

        // Without an explicit action, the shape still makes it a collection: list on {paper, <id>}
        @CheckPermission(resourceType = "paper", resourceId = "#id")
        @GetMapping("/papers/{id}/versions")
        String versions() {
            return "versions";
        }

        // A nested collection by its shape, but with the action the explicit values name a single resource
        @CheckPermission(action = "read", resourceType = "paper", resourceId = "#id")
        @GetMapping("/papers/{id}/download")
        String download() {
            return "paper";
        }

        @CheckPermission(resourceType = "belge")
        @GetMapping("/papers/{id}")
        String single() {
            return "paper";
        }
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
