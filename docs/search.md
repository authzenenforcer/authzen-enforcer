# Search

The Search APIs ask which subjects, resources or actions the PDP permits. The entity searched for is
given by its type only, since its identifier is what the search finds.

```java
// Which accounts may Alice read?
SearchResponse<Resource> accounts = client.searchResources(ResourceSearchRequest.builder()
        .subject(Subject.of("user", "alice"))
        .action(Action.of("can_read"))
        .resourceType("account")
        .page(PageRequest.ofLimit(50))
        .build());

// Who may read account 123?
SearchResponse<Subject> readers = client.searchSubjects(
        SubjectSearchRequest.of("user", Action.of("can_read"), Resource.of("account", "123")));

// What may Alice do with account 123?
SearchResponse<Action> actions = client.searchActions(
        ActionSearchRequest.of(Subject.of("user", "alice"), Resource.of("account", "123")));
```

A result is not an access decision: the PDP is expected to permit it in an evaluation, but this is
not guaranteed, so enforce access with `evaluate`.

## Page by page

A response holds one page. If `getNextPageToken()` is present, ask for the next page with
`request.withPageToken(token)`, which keeps the rest of the request unchanged, as the specification
requires.

## All pages

`searchAllSubjects`, `searchAllResources` and `searchAllActions` walk all pages for you. They return a
lazy `Stream`: a page is fetched only once the results before it are consumed, so `limit(10)` stops
sending requests after the page that holds the tenth result.

```java
try (Stream<Resource> accounts = client.searchAllResources(request)) {
    accounts.filter(account -> account.getId().startsWith("eu-")).forEach(this::show);
}
```

The `...Async` variants return a `java.util.concurrent.Flow.Publisher` that fetches pages as its
subscriber asks for results; Reactor adapts it with `JdkFlowAdapter.flowPublisherToFlux(publisher)`
and Mutiny with `Multi.createFrom().publisher(publisher)`. Cancelling stops the search, but a page
request that is already sent runs until the PDP answers or the request timeout passes.

### Limits

A PDP decides how many pages a search takes, so the client limits a search to 100 pages and 10,000
results by default. A search that goes beyond a limit, or a PDP that hands out a page token a second
time, fails with an `AuthZenException`; the results are never cut off silently. Set other limits with
`pagination(PaginationOptions)` on the builder, or for a single search with the overloads that take
`PaginationOptions`.

Pagination is no snapshot: if the data changes while paging, a result can appear twice or not at
all, and the client passes the results on as the PDP returns them.

## Endpoints

The endpoints come from `search_subject_endpoint`, `search_resource_endpoint` and
`search_action_endpoint` in the metadata. As with batch evaluations, a PDP that publishes metadata
without one of them does not offer that search, and the call fails without contacting it. Without
metadata, the default paths below `/access/v1/search/` are used; `searchSubjectEndpoint(URI)`,
`searchResourceEndpoint(URI)` and `searchActionEndpoint(URI)` set them explicitly.
