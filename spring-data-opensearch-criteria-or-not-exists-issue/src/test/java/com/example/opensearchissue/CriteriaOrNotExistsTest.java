package com.example.opensearchissue;

import com.example.opensearchissue.document.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.StringQuery;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests demonstrating that Spring Data OpenSearch Criteria API generates
 * incorrect queries when combining {@code .or()} with {@code .exists().not()}.
 *
 * <p>Standalone {@code .exists().not()} works correctly, but when used inside an
 * {@code .or()} clause the negation is lost or misapplied.
 *
 * <p>The Criteria API test uses the suspected buggy combination.
 * The native query test uses the equivalent correct OpenSearch query DSL as a control.
 */
@SpringBootTest
@Testcontainers
class CriteriaOrNotExistsTest {

    @Container
    static OpenSearchContainer<?> opensearch =
            new OpenSearchContainer<>("opensearchproject/opensearch:3.5.0");

    @DynamicPropertySource
    static void opensearchProperties(DynamicPropertyRegistry registry) {
        registry.add("opensearch.uris", () -> opensearch.getHttpHostAddress());
    }

    @Autowired
    private ElasticsearchOperations operations;

    @BeforeEach
    void setUp() {
        var indexOps = operations.indexOps(Product.class);
        if (indexOps.exists()) {
            indexOps.delete();
        }
        indexOps.create();
        indexOps.putMapping(indexOps.createMapping());

        operations.save(new Product("product-1", "Widget", "ACTIVE"));
        operations.save(new Product("product-2", "Gadget", "INACTIVE"));
        operations.save(new Product("product-3", "Gizmo", null));

        indexOps.refresh();
    }

    // ── Criteria API test (works: standalone .exists().not()) ──────────

    @Test
    void testCriteriaExistsNot() {
        // Criteria: documents where "status" field does NOT exist
        Criteria criteria = new Criteria("status").exists().not();
        CriteriaQuery query = new CriteriaQuery(criteria);

        SearchHits<Product> hits = operations.search(query, Product.class);

        List<String> ids = hits.getSearchHits().stream()
                .map(h -> h.getContent().getId())
                .toList();

        assertThat(ids)
                .as("Criteria .exists().not() should return only product-3 (no status field)")
                .containsExactlyInAnyOrder("product-3");
    }

    // ── Criteria API test (buggy: .or() + .exists().not()) ─────────────

    @Test
    void testCriteriaIsOrExistsNot() {
        // Criteria: status == "ACTIVE" OR status does not exist
        Criteria criteria = new Criteria("status").is("ACTIVE")
                .or(new Criteria("status").exists().not());
        CriteriaQuery query = new CriteriaQuery(criteria);

        SearchHits<Product> hits = operations.search(query, Product.class);

        List<String> ids = hits.getSearchHits().stream()
                .map(h -> h.getContent().getId())
                .toList();

        assertThat(ids)
                .as("Criteria .is('ACTIVE').or(.exists().not()) should return product-1 and product-3")
                .containsExactlyInAnyOrder("product-1", "product-3");
    }

    // ── Native query test (known correct, control group) ─────────────────

    @Test
    void testNativeQueryIsOrMustNotExists() {
        // Native: bool { should: [ term{status:ACTIVE}, bool{must_not:[exists{status}]} ], minimum_should_match: 1 }
        String json = """
                {"bool":{"should":[{"term":{"status":{"value":"ACTIVE"}}},{"bool":{"must_not":[{"exists":{"field":"status"}}]}}],"minimum_should_match":1}}""";
        StringQuery query = new StringQuery(json);

        SearchHits<Product> hits = operations.search(query, Product.class);

        List<String> ids = hits.getSearchHits().stream()
                .map(h -> h.getContent().getId())
                .toList();

        assertThat(ids)
                .as("Native should(term,mustNot(exists)) should return product-1 and product-3")
                .containsExactlyInAnyOrder("product-1", "product-3");
    }
}
