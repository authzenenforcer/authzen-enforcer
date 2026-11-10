package io.github.authzenenforcer.core.enforce;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.model.Resource;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ResourceInferrerTest {

    private static RequestDescription request(String method, String pattern, Map<String, String> variables) {
        return RequestDescription.builder().method(method).routePattern(pattern).pathVariables(variables).build();
    }

    private static InferredResource infer(ResourceInferrer inferrer, String method, String pattern,
            Map<String, String> variables) {
        return inferrer.infer(request(method, pattern, variables), false);
    }

    @Test
    void takesTheTypeFromTheSegmentBeforeTheVariable() {
        InferredResource inferred = infer(ResourceInferrer.defaults(), "GET", "/api/v1/documents/{docId}",
                Map.of("docId", "42"));

        assertEquals(Resource.of("documents", "42"), inferred.getResource());
        assertFalse(inferred.isCollection());
    }

    @Test
    void namesACollectionByItsSegment() {
        InferredResource inferred = infer(ResourceInferrer.defaults(), "POST", "/api/v1/documents", Map.of());

        assertEquals(Resource.of("collection", "documents"), inferred.getResource());
        assertTrue(inferred.isCollection());
    }

    @Test
    void carriesTheEnclosingResources() {
        InferredResource inferred = infer(ResourceInferrer.defaults(), "DELETE",
                "/orgs/{orgId}/projects/{projectId}/documents/{docId}",
                Map.of("orgId", "acme", "projectId", "p1", "docId", "42"));

        Resource expected = Resource.builder()
                .type("documents").id("42")
                .property("parent", Map.of("type", "projects", "id", "p1"))
                .property("ancestors", List.of(Map.of("type", "orgs", "id", "acme"), Map.of("type", "projects", "id", "p1")))
                .build();
        assertEquals(expected, inferred.getResource());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "get"})
    void readsAStaticSegmentAfterTheIdAsANestedCollectionForReads(String method) {
        InferredResource inferred = infer(ResourceInferrer.defaults(), method, "/tenants/{t}/documents",
                Map.of("t", "acme"));

        Resource expected = Resource.builder()
                .type("collection").id("documents")
                .property("parent", Map.of("type", "tenants", "id", "acme"))
                .property("ancestors", List.of(Map.of("type", "tenants", "id", "acme")))
                .build();
        assertEquals(expected, inferred.getResource());
        assertTrue(inferred.isCollection());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void refusesAStaticSegmentAfterTheIdForWritesWithoutAnExplicitAction(String method) {
        RequestDescription request = request(method, "/documents/{id}/cancel", Map.of("id", "42"));

        InferenceException e = assertThrows(InferenceException.class, () -> ResourceInferrer.defaults().infer(request, false));

        assertTrue(e.isMisconfiguration());
        assertTrue(e.getMessage().contains("set the action explicitly"), e.getMessage());
    }

    @Test
    void takesTheResourceOfTheIdWhenTheActionIsExplicit() {
        InferredResource inferred = ResourceInferrer.defaults()
                .infer(request("POST", "/documents/{id}/cancel", Map.of("id", "42")), true);

        assertEquals(Resource.of("documents", "42"), inferred.getResource());
        assertFalse(inferred.isCollection());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "/{tenant}/documents | does not follow a static segment",
        "/documents/{id}/{version} | does not follow a static segment",
        "/documents/{id}/comments/recent | more than one static segment",
        "/ | no segments",
        "/documents/** | wildcard"
    })
    void refusesRoutesItCannotInferFromSafely(String pattern, String reason) {
        RequestDescription request = request("GET", pattern, Map.of("tenant", "t", "id", "1", "version", "2"));

        InferenceException e = assertThrows(InferenceException.class, () -> ResourceInferrer.defaults().infer(request, true));

        assertTrue(e.getMessage().contains(reason), e.getMessage());
        assertTrue(e.isMisconfiguration());
        assertThrows(InferenceException.class, () -> ResourceInferrer.defaults().check("GET", pattern, true));
    }

    @Test
    void checksRoutesWithoutARequest() {
        assertDoesNotThrow(() -> ResourceInferrer.defaults().check("GET", "/documents/{id}", false));
        assertDoesNotThrow(() -> ResourceInferrer.defaults().check("GET", "/documents/{id}/history", false));
        assertThrows(InferenceException.class, () -> ResourceInferrer.defaults().check("POST", "/documents/{id}/cancel", false));
        assertDoesNotThrow(() -> ResourceInferrer.defaults().check("POST", "/documents/{id}/cancel", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "4\n2", "4\u00002", "\u007f", "4\u00852"})
    void refusesInvalidIdsWithoutNamingThem(String id) {
        RequestDescription request = request("GET", "/documents/{id}", Map.of("id", id));

        InferenceException e = assertThrows(InferenceException.class, () -> ResourceInferrer.defaults().infer(request, false));

        assertFalse(e.isMisconfiguration(), "callers control path variables");
        assertTrue(e.getMessage().startsWith("Path variable id of route /documents/{id} "), e.getMessage());
    }

    @Test
    void refusesInvalidIdsOfEnclosingResourcesToo() {
        RequestDescription request = request("GET", "/tenants/{t}/documents/{id}", Map.of("t", "", "id", "42"));

        assertThrows(InferenceException.class, () -> ResourceInferrer.defaults().infer(request, false));
    }

    @Test
    void refusesIdsLongerThanTheLimit() {
        ResourceInferrer inferrer = ResourceInferrer.builder().maxIdLength(3).build();

        assertEquals("123", infer(inferrer, "GET", "/documents/{id}", Map.of("id", "123")).getResource().getId());
        assertThrows(InferenceException.class, () -> infer(inferrer, "GET", "/documents/{id}", Map.of("id", "1234")));
        assertEquals(1024, ResourceInferrer.Builder.DEFAULT_MAX_ID_LENGTH);
        assertThrows(IllegalArgumentException.class, () -> ResourceInferrer.builder().maxIdLength(0));
    }

    @Test
    void keepsIdsAsTheyAre() {
        assertEquals(" 1/../2 ", infer(ResourceInferrer.defaults(), "GET", "/documents/{id}", Map.of("id", " 1/../2 "))
                .getResource().getId());
    }

    @Test
    void refusesAVariableWithoutAValue() {
        InferenceException e = assertThrows(InferenceException.class,
                () -> infer(ResourceInferrer.defaults(), "GET", "/documents/{id}", Map.of()));

        assertTrue(e.isMisconfiguration());
    }

    @Test
    void singularizesOnlyWhenAskedTo() {
        ResourceInferrer singular = ResourceInferrer.builder().singularize(true).build();

        assertEquals(Resource.of("document", "42"), infer(singular, "GET", "/documents/{id}", Map.of("id", "42")).getResource());
        assertEquals(Resource.of("collection", "policy"), infer(singular, "GET", "/policies", Map.of()).getResource());
        assertEquals(Resource.of("documents", "42"),
                infer(ResourceInferrer.defaults(), "GET", "/documents/{id}", Map.of("id", "42")).getResource());
    }

    @Test
    void namesTypesFromTheDictionaryWithOrWithoutSingularizing() {
        ResourceInferrer named = ResourceInferrer.builder().typeName("analyses", "analysis").build();
        ResourceInferrer both = ResourceInferrer.builder().singularize(true).typeName("analyses", "analysis").build();

        assertEquals("analysis", infer(named, "GET", "/analyses/{id}", Map.of("id", "1")).getResource().getType());
        assertEquals("analysis", infer(both, "GET", "/analyses/{id}", Map.of("id", "1")).getResource().getType());
    }

    @Test
    void usesTheConfiguredCollectionType() {
        ResourceInferrer inferrer = ResourceInferrer.builder().collectionType("list").build();

        assertEquals(Resource.of("list", "documents"), infer(inferrer, "GET", "/documents", Map.of()).getResource());
    }
}
