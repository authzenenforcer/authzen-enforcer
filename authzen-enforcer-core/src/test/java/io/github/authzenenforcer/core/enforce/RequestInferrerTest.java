package io.github.authzenenforcer.core.enforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.internal.client.TestLoggerFinder;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.Context;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestInferrerTest {

    private static final Map<String, Object> ALICE = Map.of("sub", "alice-id", "realm_access", Map.of("roles", "editor"));

    private static RequestDescription.Builder request(String method, String pattern) {
        return RequestDescription.builder().method(method).routePattern(pattern).claims(ALICE);
    }

    private static EvaluationRequest evaluate(RequestInferrer inferrer, RequestDescription request, PermissionSpec spec) {
        Inference inference = inferrer.infer(request, spec);
        assertEquals(Inference.Kind.EVALUATE, inference.getKind(), () -> inference.getReason().orElse(""));
        return inference.getRequest().orElseThrow();
    }

    @Test
    void infersEverythingFromTheRequest() {
        EvaluationRequest evaluation = evaluate(RequestInferrer.defaults(),
                request("GET", "/documents/{id}").pathVariable("id", "42").build(), PermissionSpec.none());

        assertEquals(EvaluationRequest.of(Subject.of("user", "alice-id"), Action.of("read"), Resource.of("documents", "42")),
                evaluation);
    }

    @Test
    void listsACollection() {
        EvaluationRequest evaluation = evaluate(RequestInferrer.defaults(), request("GET", "/documents").build(),
                PermissionSpec.none());

        assertEquals(Action.of("list"), evaluation.getAction());
        assertEquals(Resource.of("collection", "documents"), evaluation.getResource());
    }

    @Test
    void prefersTheEndpointOverTheRouteOverrideOverInference() {
        RequestInferrer inferrer = RequestInferrer.builder()
                .overrides(RouteOverrides.builder()
                        .route("/documents/{id}", PermissionSpec.builder()
                                .action("view").resourceType("document").subjectType("member")
                                .context("source", "config").context("channel", "web")
                                .build())
                        .build())
                .build();
        RequestDescription request = request("GET", "/documents/{id}").pathVariable("id", "42").build();

        EvaluationRequest fromOverride = evaluate(inferrer, request, PermissionSpec.none());
        EvaluationRequest fromEndpoint = evaluate(inferrer, request, PermissionSpec.builder()
                .action("preview").subjectType("guest").resourceId("7").context("source", "annotation").build());

        assertEquals(Action.of("view"), fromOverride.getAction());
        assertEquals(Resource.of("document", "42"), fromOverride.getResource());
        assertEquals(Subject.of("member", "alice-id"), fromOverride.getSubject());
        assertEquals(Optional.of(Context.of(Map.of("source", "config", "channel", "web"))), fromOverride.getContext());

        assertEquals(Action.of("preview"), fromEndpoint.getAction());
        assertEquals(Resource.of("document", "7"), fromEndpoint.getResource());
        assertEquals(Subject.of("guest", "alice-id"), fromEndpoint.getSubject());
        assertEquals(Optional.of(Context.of(Map.of("source", "annotation", "channel", "web"))), fromEndpoint.getContext());
    }

    @Test
    void keepsTheSubjectPropertiesWhenTheTypeIsExplicit() {
        RequestInferrer inferrer = RequestInferrer.builder().subjectResolver(SubjectResolver.keycloak().build()).build();

        EvaluationRequest evaluation = evaluate(inferrer, request("GET", "/documents").build(),
                PermissionSpec.builder().subjectType("member").build());

        assertEquals(Subject.builder().type("member").id("alice-id").property("roles", "editor").build(),
                evaluation.getSubject());
    }

    @Test
    void needsNoInferenceForAnExplicitResource() {
        EvaluationRequest evaluation = evaluate(RequestInferrer.defaults(),
                request("POST", "/{tenant}/reports/{a}/{b}").build(),
                PermissionSpec.builder().resourceType("report").resourceId("r1").build());

        assertEquals(Resource.of("report", "r1"), evaluation.getResource());
        assertEquals(Action.of("create"), evaluation.getAction());
    }

    @Test
    void namesWhatACollectionHoldsWithAnExplicitType() {
        EvaluationRequest evaluation = evaluate(RequestInferrer.defaults(),
                request("GET", "/tenants/{t}/documents").pathVariable("t", "acme").build(),
                PermissionSpec.builder().resourceType("document").build());

        assertEquals("collection", evaluation.getResource().getType());
        assertEquals("document", evaluation.getResource().getId());
        assertEquals(Map.of("type", "tenants", "id", "acme"), evaluation.getResource().getProperties().get("parent"));
        assertEquals(Action.of("list"), evaluation.getAction());
    }

    @Test
    void protectsAnActionRouteWithAnExplicitAction() {
        RequestDescription request = request("POST", "/documents/{id}/cancel").pathVariable("id", "42").build();

        Inference refused = RequestInferrer.defaults().infer(request, PermissionSpec.none());
        EvaluationRequest evaluation = evaluate(RequestInferrer.defaults(), request,
                PermissionSpec.builder().action("cancel").build());

        assertEquals(Inference.Kind.REFUSED, refused.getKind());
        assertTrue(refused.isMisconfiguration());
        assertEquals(Action.of("cancel"), evaluation.getAction());
        assertEquals(Resource.of("documents", "42"), evaluation.getResource());
    }

    @Test
    void protectsAnActionRouteWithARouteOverride() {
        RequestInferrer inferrer = RequestInferrer.builder()
                .overrides(RouteOverrides.builder()
                        .route("POST", "/documents/{id}/cancel", PermissionSpec.builder().action("cancel").build())
                        .build())
                .build();

        EvaluationRequest evaluation = evaluate(inferrer,
                request("POST", "/documents/{id}/cancel").pathVariable("id", "42").build(), PermissionSpec.none());

        assertEquals(Action.of("cancel"), evaluation.getAction());
    }

    @Test
    void refusesInvalidInputWithoutNamingIt() {
        Inference inference = RequestInferrer.defaults()
                .infer(request("GET", "/documents/{id}").pathVariable("id", "4\n2").build(), PermissionSpec.none());

        assertEquals(Inference.Kind.REFUSED, inference.getKind());
        assertFalse(inference.isMisconfiguration());
        assertFalse(inference.getReason().orElseThrow().contains("4\n2"));
        assertTrue(inference.getRequest().isEmpty());
    }

    @Test
    void refusesAMethodWithoutAnActionAsInvalidInput() {
        Inference inference = RequestInferrer.defaults()
                .infer(request("PROPFIND", "/documents/{id}").pathVariable("id", "42").build(), PermissionSpec.none());

        assertEquals(Inference.Kind.REFUSED, inference.getKind());
        assertFalse(inference.isMisconfiguration(), "the caller chooses the method");
    }

    @Test
    void refusesClaimsWithoutASubject() {
        Inference inference = RequestInferrer.defaults().infer(RequestDescription.builder()
                .method("GET").routePattern("/documents").claims(Map.of("scope", "openid")).build(), PermissionSpec.none());

        assertEquals(Inference.Kind.REFUSED, inference.getKind());
    }

    @Test
    void doesNotEnforceCorsPreflightsUnlessTheActionIsExplicit() {
        RequestDescription preflight = request("OPTIONS", "/documents/{id}").pathVariable("id", "42").build();

        assertEquals(Inference.Kind.NOT_ENFORCED,
                RequestInferrer.defaults().infer(preflight, PermissionSpec.none()).getKind());
        assertEquals(Action.of("describe"), evaluate(RequestInferrer.defaults(), preflight,
                PermissionSpec.builder().action("describe").build()).getAction());
    }

    @ParameterizedTest
    @ValueSource(strings = {"options", "Options"})
    void refusesOtherSpellingsOfAnExemptMethod(String method) {
        Inference inference = RequestInferrer.defaults()
                .infer(request(method, "/documents/{id}").pathVariable("id", "42").build(), PermissionSpec.none());

        assertEquals(Inference.Kind.REFUSED, inference.getKind(), "never let an unknown method through unchecked");
    }

    @Test
    void deniesAnonymousCallersWithoutAskingThePdp() {
        RequestDescription anonymous = RequestDescription.builder().method("GET").routePattern("/documents").build();

        Inference inference = RequestInferrer.defaults().infer(anonymous, PermissionSpec.none());

        assertEquals(Inference.Kind.UNAUTHENTICATED, inference.getKind());
        assertTrue(inference.getRequest().isEmpty());
    }

    @Test
    void asksAboutAnonymousCallersAsTheConfiguredSubject() {
        RequestInferrer inferrer = RequestInferrer.builder().anonymousSubject(Subject.of("anonymous", "anonymous")).build();
        RequestDescription anonymous = RequestDescription.builder().method("GET").routePattern("/documents").build();

        assertEquals(Subject.of("anonymous", "anonymous"), evaluate(inferrer, anonymous, PermissionSpec.none()).getSubject());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/documents/{id}", "/documents", "/tenants/{t}/documents"})
    void acceptsInferableRoutesAtStartup(String pattern) {
        assertEquals(Optional.empty(), RequestInferrer.defaults().check("GET", pattern, PermissionSpec.none()));
    }

    @Test
    void reportsRoutesThatWillBeRefusedAtStartup() {
        RequestInferrer inferrer = RequestInferrer.defaults();

        assertTrue(inferrer.check("POST", "/documents/{id}/cancel", PermissionSpec.none()).isPresent());
        assertTrue(inferrer.check("GET", "/{tenant}/documents", PermissionSpec.none()).isPresent());
        assertTrue(inferrer.check("PROPFIND", "/documents", PermissionSpec.none()).isPresent());
        assertEquals(Optional.empty(), inferrer.check("POST", "/documents/{id}/cancel",
                PermissionSpec.builder().action("cancel").build()));
        assertEquals(Optional.empty(), inferrer.check("GET", "/{tenant}/documents",
                PermissionSpec.builder().resourceType("document").resourceId("all").build()));
        assertEquals(Optional.empty(), inferrer.check("OPTIONS", "/{tenant}/documents", PermissionSpec.none()));
    }

    @Test
    void reportsRoutesAtStartupThatTheOverridesFix() {
        RequestInferrer inferrer = RequestInferrer.builder()
                .overrides(RouteOverrides.builder()
                        .route("POST", "/documents/{id}/cancel", PermissionSpec.builder().action("cancel").build())
                        .build())
                .build();

        assertEquals(Optional.empty(), inferrer.check("POST", "/documents/{id}/cancel", PermissionSpec.none()));
    }

    @Test
    void warnsOnceAboutAMisconfiguredRouteAndNeverAboutInvalidInput() {
        RequestInferrer inferrer = RequestInferrer.defaults();
        RequestDescription misconfigured = request("POST", "/documents/{id}/cancel").pathVariable("id", "42").build();
        RequestDescription invalid = request("GET", "/documents/{id}").pathVariable("id", "").build();
        TestLoggerFinder.clearRecords();

        for (int i = 0; i < 3; i++) {
            inferrer.infer(misconfigured, PermissionSpec.none());
            inferrer.infer(invalid, PermissionSpec.none());
        }

        List<String> warnings = TestLoggerFinder.records().stream()
                .filter(record -> record.startsWith("WARNING " + RequestInferrer.class.getName()))
                .toList();
        assertEquals(1, warnings.size(), String.valueOf(warnings));
        assertTrue(warnings.get(0).contains("/documents/{id}/cancel"));
    }

    @Test
    void keepsWarningAboutMisconfigurationsWhateverMethodsCallersSend() {
        RequestInferrer inferrer = RequestInferrer.defaults();
        TestLoggerFinder.clearRecords();

        for (int i = 0; i < 1100; i++) {
            inferrer.infer(request("X" + i, "/documents/{id}").pathVariable("id", "42").build(), PermissionSpec.none());
            inferrer.infer(request(i % 2 == 0 ? "post" : "Post" + i, "/documents/{id}/cancel").pathVariable("id", "42").build(),
                    PermissionSpec.none());
        }
        inferrer.infer(request("GET", "/{tenant}/documents").build(), PermissionSpec.none());

        List<String> warnings = TestLoggerFinder.records().stream()
                .filter(record -> record.startsWith("WARNING " + RequestInferrer.class.getName()))
                .toList();
        assertEquals(2, warnings.size(), String.valueOf(warnings));
        assertTrue(warnings.get(0).contains("/documents/{id}/cancel"));
        assertTrue(warnings.get(1).contains("/{tenant}/documents"), "a later misconfiguration is still a warning");
    }

    @Test
    void describesARequestWithoutItsValues() {
        String text = request("GET\nFORGED", "/documents/{id}").pathVariable("id", "secret-42").build().toString();

        assertFalse(text.contains("\n"), text);
        assertFalse(text.contains("secret-42"), text);
        assertFalse(text.contains("alice-id"), text);
        assertTrue(text.contains("id") && text.contains("sub"), text);
    }
}
