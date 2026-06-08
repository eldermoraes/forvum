package ai.forvum.provider.memory.qdrant;

import ai.forvum.provider.memory.qdrant.dto.QdrantScrollRequest;
import ai.forvum.provider.memory.qdrant.dto.QdrantScrollResponse;
import ai.forvum.provider.memory.qdrant.dto.QdrantSearchRequest;
import ai.forvum.provider.memory.qdrant.dto.QdrantSearchResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A test double for {@link QdrantApi}: it records every request and returns scripted responses (a
 * per-call function keyed by the request, defaulting to an empty envelope). Used directly (not as a CDI
 * bean) so {@link QdrantMemoryProvider#retrieve} can be driven without a live Qdrant or a mocked HTTP
 * endpoint.
 */
class FakeQdrantApi implements QdrantApi {

    final List<QdrantSearchRequest> searches = new ArrayList<>();
    final List<QdrantScrollRequest> scrolls = new ArrayList<>();
    final List<String> baseUrls = new ArrayList<>();
    final List<String> apiKeys = new ArrayList<>();
    final List<String> collections = new ArrayList<>();

    Function<QdrantSearchRequest, QdrantSearchResponse> searchResponder =
            req -> new QdrantSearchResponse(List.of(), "ok");
    Function<QdrantScrollRequest, QdrantScrollResponse> scrollResponder =
            req -> new QdrantScrollResponse(new QdrantScrollResponse.Page(List.of()), "ok");

    boolean throwOnSearch = false;

    @Override
    public QdrantSearchResponse search(String baseUrl, String apiKey, String collection,
                                       QdrantSearchRequest request) {
        baseUrls.add(baseUrl);
        apiKeys.add(apiKey);
        collections.add(collection);
        searches.add(request);
        if (throwOnSearch) {
            throw new RuntimeException("simulated Qdrant search failure");
        }
        return searchResponder.apply(request);
    }

    @Override
    public QdrantScrollResponse scroll(String baseUrl, String apiKey, String collection,
                                       QdrantScrollRequest request) {
        baseUrls.add(baseUrl);
        apiKeys.add(apiKey);
        collections.add(collection);
        scrolls.add(request);
        return scrollResponder.apply(request);
    }
}
