# Spring Data OpenSearch: Criteria `.or()` with `.exists().not()` Issue

This project demonstrates an issue in the **Spring Data OpenSearch** Criteria API where combining `.or()` with `.exists().not()` produces an incorrect OpenSearch query.

## The Issue

When using the Criteria API:

- **Standalone** `.exists().not()` works correctly
- **Inside an `.or()` clause**, `.exists().not()` loses or misapplies the negation, returning incorrect results

### Example

```java
// incorrect query is generated
Criteria criteria = new Criteria("status").is("ACTIVE")
    .or(new Criteria("status").exists().not());
```

The equivalent **native query** works:

```json
{
  "bool": {
    "should": [
      { "term": { "status": "ACTIVE" } },
      { "bool": { "must_not": [{ "exists": { "field": "status" } }] } }
    ],
    "minimum_should_match": 1
  }
}
```

## Prerequisites

- Java 21+
- Docker (for Testcontainers / docker-compose)

## Running the Tests

```bash
./gradlew test
```

The tests use Testcontainers to start an OpenSearch instance automatically — no manual setup needed.

### Test Cases

| Test | Description | Status    |
|------|-------------|-----------|
| `testCriteriaExistsNot` | Standalone `.exists().not()` | Passes    |
| `testCriteriaIsOrExistsNot` | `.or()` + `.exists().not()` via Criteria API | **Fails** |
| `testNativeQueryIsOrMustNotExists` | Equivalent native query | Passes    |

## Running OpenSearch Locally

If you want to run OpenSearch outside of tests:

```bash
docker compose up -d
```

This starts a single-node OpenSearch cluster on `http://localhost:9200` with security disabled.

## Test Data

The tests create three `Product` documents:

| ID | Name | Status |
|----|------|--------|
| product-1 | Widget | ACTIVE |
| product-2 | Gadget | INACTIVE |
| product-3 | Gizmo | *(missing)* |

The query "status is ACTIVE **or** status field does not exist" should return **product-1** and **product-3**.
