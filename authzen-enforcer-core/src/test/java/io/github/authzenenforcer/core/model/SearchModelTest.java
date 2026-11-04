package io.github.authzenenforcer.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SearchModelTest {

    private static final Subject ALICE = Subject.of("user", "alice");
    private static final Action READ = Action.of("can_read");
    private static final Resource ACCOUNT = Resource.of("account", "123");

    @Test
    void buildsSearchRequestsWithTheSearchedTypeOnly() {
        SubjectSearchRequest subjects = SubjectSearchRequest.of("user", READ, ACCOUNT);
        ResourceSearchRequest resources = ResourceSearchRequest.of(ALICE, READ, "account");
        ActionSearchRequest actions = ActionSearchRequest.of(ALICE, ACCOUNT);

        assertEquals("user", subjects.getSubjectType());
        assertTrue(subjects.getSubjectProperties().isEmpty());
        assertEquals("account", resources.getResourceType());
        assertEquals(ACCOUNT, actions.getResource());
        assertTrue(subjects.getPage().isEmpty());
        assertTrue(resources.getContext().isEmpty());
    }

    @Test
    void rejectsSearchRequestsWithoutRequiredParts() {
        assertThrows(IllegalStateException.class, () -> SubjectSearchRequest.of(null, READ, ACCOUNT));
        assertThrows(IllegalStateException.class, () -> SubjectSearchRequest.of("user", null, ACCOUNT));
        assertThrows(IllegalStateException.class, () -> SubjectSearchRequest.of("user", READ, null));
        assertThrows(IllegalStateException.class, () -> ResourceSearchRequest.of(ALICE, READ, null));
        assertThrows(IllegalStateException.class, () -> ResourceSearchRequest.of(null, READ, "account"));
        assertThrows(IllegalStateException.class, () -> ActionSearchRequest.of(ALICE, null));
        assertThrows(IllegalStateException.class, () -> ActionSearchRequest.of(null, ACCOUNT));
    }

    @Test
    void copiesSearchedEntityProperties() {
        ResourceSearchRequest request = ResourceSearchRequest.builder()
                .subject(ALICE).action(READ).resourceType("account")
                .resourceProperty("old", true)
                .resourceProperties(Map.of("region", "eu", "level", 3))
                .build();

        assertEquals(Map.of("region", "eu", "level", 3L), request.getResourceProperties());
        assertThrows(UnsupportedOperationException.class, () -> request.getResourceProperties().put("x", 1));
        assertThrows(IllegalArgumentException.class, () -> SubjectSearchRequest.builder()
                .subjectType("user").subjectProperty("x", new Object()).action(READ).resource(ACCOUNT).build());
    }

    @Test
    void withPageTokenKeepsTheRestOfTheRequest() {
        Context context = Context.of(Map.of("time", "now"));
        SubjectSearchRequest first = SubjectSearchRequest.builder()
                .subjectType("user").subjectProperty("department", "Sales")
                .action(READ).resource(ACCOUNT).context(context)
                .page(PageRequest.builder().limit(10).property("sort", "name").build())
                .build();

        SubjectSearchRequest next = first.withPageToken("t2");

        assertEquals(PageRequest.builder().token("t2").limit(10).property("sort", "name").build(),
                next.getPage().orElseThrow());
        assertEquals(first.getSubjectProperties(), next.getSubjectProperties());
        assertEquals(context, next.getContext().orElseThrow());
        assertEquals(next, next.withPageToken("t2"));
        assertTrue(first.getPage().orElseThrow().getToken().isEmpty());
    }

    @Test
    void withPageTokenAddsAPageWhenThereWasNone() {
        ActionSearchRequest next = ActionSearchRequest.of(ALICE, ACCOUNT).withPageToken("t2");
        ResourceSearchRequest resources = ResourceSearchRequest.of(ALICE, READ, "account").withPageToken("t3");

        assertEquals("t2", next.getPage().orElseThrow().getToken().orElseThrow());
        assertTrue(next.getPage().orElseThrow().getLimit().isEmpty());
        assertEquals("t3", resources.getPage().orElseThrow().getToken().orElseThrow());
        assertThrows(NullPointerException.class, () -> ActionSearchRequest.of(ALICE, ACCOUNT).withPageToken(null));
    }

    @Test
    void pageRequestRejectsANegativeLimit() {
        assertThrows(IllegalArgumentException.class, () -> PageRequest.ofLimit(-1));
        assertEquals(0, PageRequest.ofLimit(0).getLimit().orElseThrow());
        assertTrue(PageRequest.builder().build().getToken().isEmpty());
    }

    @Test
    void pageResponseRequiresANextTokenAndNonNegativeCounts() {
        assertThrows(IllegalStateException.class, () -> PageResponse.builder().build());
        assertThrows(IllegalArgumentException.class, () -> PageResponse.builder().count(-1));
        assertThrows(IllegalArgumentException.class, () -> PageResponse.builder().total(-1));

        PageResponse last = PageResponse.builder().nextToken("").count(1).total(3).build();

        assertFalse(last.hasNextPage());
        assertEquals(3L, last.getTotal().orElseThrow());
        assertTrue(PageResponse.builder().nextToken("t").build().hasNextPage());
    }

    @Test
    void searchResponseCopiesItsResults() {
        List<Subject> results = new ArrayList<>(List.of(ALICE));
        SearchResponse<Subject> response = SearchResponse.<Subject>builder().results(results).build();

        results.add(Subject.of("user", "bob"));

        assertEquals(List.of(ALICE), response.getResults());
        assertThrows(UnsupportedOperationException.class, () -> response.getResults().add(ALICE));
        assertThrows(NullPointerException.class, () -> SearchResponse.<Subject>builder().result(null));
        assertTrue(response.getPage().isEmpty());
        assertTrue(response.getContext().isEmpty());
    }

    @Test
    void equalityCoversEveryPart() {
        PageRequest page = PageRequest.ofLimit(5);
        assertEquals(ResourceSearchRequest.builder().subject(ALICE).action(READ).resourceType("account").page(page).build(),
                ResourceSearchRequest.builder().subject(ALICE).action(READ).resourceType("account").page(page).build());
        assertNotEquals(ResourceSearchRequest.of(ALICE, READ, "account"),
                ResourceSearchRequest.of(ALICE, READ, "document"));
        assertNotEquals(SubjectSearchRequest.of("user", READ, ACCOUNT),
                SubjectSearchRequest.builder().subjectType("user").action(READ).resource(ACCOUNT).page(page).build());
        assertEquals(ActionSearchRequest.of(ALICE, ACCOUNT).hashCode(), ActionSearchRequest.of(ALICE, ACCOUNT).hashCode());
        SearchResponse<Action> response = SearchResponse.<Action>builder().result(READ)
                .page(PageResponse.builder().nextToken("").build()).build();
        assertEquals(response, SearchResponse.<Action>builder().result(READ)
                .page(PageResponse.builder().nextToken("").build()).build());
        assertNotEquals(response, SearchResponse.<Action>builder().result(READ).build());
    }

    @Test
    void equalityComparesEachPartOfTheRequests() {
        Context context = Context.of(Map.of("time", "now"));
        PageRequest page = PageRequest.ofLimit(5);
        SubjectSearchRequest subjects = SubjectSearchRequest.builder().subjectType("user").subjectProperty("a", 1)
                .action(READ).resource(ACCOUNT).context(context).page(page).build();
        ResourceSearchRequest resources = ResourceSearchRequest.builder().subject(ALICE).action(READ)
                .resourceType("account").resourceProperty("a", 1).context(context).page(page).build();
        ActionSearchRequest actions = ActionSearchRequest.builder().subject(ALICE).resource(ACCOUNT)
                .context(context).page(page).build();

        List<Object> differentSubjects = List.of(
                SubjectSearchRequest.builder().subjectType("group").subjectProperty("a", 1).action(READ).resource(ACCOUNT).context(context).page(page).build(),
                SubjectSearchRequest.builder().subjectType("user").action(READ).resource(ACCOUNT).context(context).page(page).build(),
                SubjectSearchRequest.builder().subjectType("user").subjectProperty("a", 1).action(Action.of("x")).resource(ACCOUNT).context(context).page(page).build(),
                SubjectSearchRequest.builder().subjectType("user").subjectProperty("a", 1).action(READ).resource(Resource.of("account", "9")).context(context).page(page).build(),
                SubjectSearchRequest.builder().subjectType("user").subjectProperty("a", 1).action(READ).resource(ACCOUNT).page(page).build(),
                SubjectSearchRequest.builder().subjectType("user").subjectProperty("a", 1).action(READ).resource(ACCOUNT).context(context).build(),
                "not a request");
        List<Object> differentResources = List.of(
                ResourceSearchRequest.builder().subject(Subject.of("user", "bob")).action(READ).resourceType("account").resourceProperty("a", 1).context(context).page(page).build(),
                ResourceSearchRequest.builder().subject(ALICE).action(Action.of("x")).resourceType("account").resourceProperty("a", 1).context(context).page(page).build(),
                ResourceSearchRequest.builder().subject(ALICE).action(READ).resourceType("document").resourceProperty("a", 1).context(context).page(page).build(),
                ResourceSearchRequest.builder().subject(ALICE).action(READ).resourceType("account").context(context).page(page).build(),
                ResourceSearchRequest.builder().subject(ALICE).action(READ).resourceType("account").resourceProperty("a", 1).page(page).build(),
                ResourceSearchRequest.builder().subject(ALICE).action(READ).resourceType("account").resourceProperty("a", 1).context(context).build(),
                "not a request");
        List<Object> differentActions = List.of(
                ActionSearchRequest.builder().subject(Subject.of("user", "bob")).resource(ACCOUNT).context(context).page(page).build(),
                ActionSearchRequest.builder().subject(ALICE).resource(Resource.of("account", "9")).context(context).page(page).build(),
                ActionSearchRequest.builder().subject(ALICE).resource(ACCOUNT).page(page).build(),
                ActionSearchRequest.builder().subject(ALICE).resource(ACCOUNT).context(context).build(),
                "not a request");

        assertEqualOnlyToItself(subjects, differentSubjects);
        assertEqualOnlyToItself(resources, differentResources);
        assertEqualOnlyToItself(actions, differentActions);
        assertEquals(subjects.withPageToken("t").hashCode(), subjects.withPageToken("t").hashCode());
    }

    @Test
    void equalityComparesEachPartOfThePagesAndResponses() {
        PageRequest page = PageRequest.builder().token("t").limit(5).property("a", 1).build();
        PageResponse response = PageResponse.builder().nextToken("t").count(1).total(2).property("a", 1).build();
        SearchResponse<Action> search = SearchResponse.<Action>builder().result(READ).page(response)
                .context(Context.of(Map.of("a", 1))).build();

        assertEqualOnlyToItself(page, List.of(
                PageRequest.builder().limit(5).property("a", 1).build(),
                PageRequest.builder().token("t").property("a", 1).build(),
                PageRequest.builder().token("t").limit(5).build(),
                "not a page"));
        assertEqualOnlyToItself(response, List.of(
                PageResponse.builder().nextToken("u").count(1).total(2).property("a", 1).build(),
                PageResponse.builder().nextToken("t").total(2).property("a", 1).build(),
                PageResponse.builder().nextToken("t").count(1).property("a", 1).build(),
                PageResponse.builder().nextToken("t").count(1).total(2).build(),
                "not a page"));
        assertEqualOnlyToItself(search, List.of(
                SearchResponse.<Action>builder().page(response).context(Context.of(Map.of("a", 1))).build(),
                SearchResponse.<Action>builder().result(READ).context(Context.of(Map.of("a", 1))).build(),
                SearchResponse.<Action>builder().result(READ).page(response).build(),
                "not a response"));
        assertEquals(1L, response.getCount().orElseThrow());
        assertEquals(Map.of("a", 1L), response.getProperties());
        assertEquals(Map.of("a", 1L), page.getProperties());
        assertTrue(search.toString().contains("can_read"), search.toString());
        assertTrue(ActionSearchRequest.of(ALICE, ACCOUNT).toString().startsWith("ActionSearchRequest{"));
    }

    private static void assertEqualOnlyToItself(Object value, List<Object> others) {
        assertEquals(value, value);
        for (Object other : others) {
            assertNotEquals(value, other, String.valueOf(other));
        }
    }

    @Test
    void toStringMasksPersonalDataAndTokens() {
        SubjectSearchRequest request = SubjectSearchRequest.builder()
                .subjectType("user").subjectProperty("email", "alice@example.com")
                .action(READ).resource(ACCOUNT)
                .page(PageRequest.builder().token("secret-cursor").limit(5).build())
                .build();
        PageResponse page = PageResponse.builder().nextToken("secret-cursor").count(5).build();

        String text = request + " " + page;

        assertFalse(text.contains("alice@example.com"), text);
        assertFalse(text.contains("secret-cursor"), text);
        assertTrue(text.contains("email=***"), text);
        assertTrue(text.contains("limit=5"), text);
    }
}
