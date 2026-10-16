# Services: boundaries, contracts and data

The backend started as one deployable (a modular monolith) whose bounded contexts were kept separable; each group is
now its own service, procurement included (`backend/procurement-service`, formerly `backend/monolith`).
`ModuleBoundariesTest` (ArchUnit) fails the build when a rule below is broken, and `DataOwnershipTest` fails it when a
SQL or JPQL query names another context's table or entity.

## Target services

| Service | Contexts | Notes |
|---|---|---|
| API gateway | `gateway` | Validates JWTs, routes, rate-limits, traces (`X-Gateway-Trace-Id`). |
| Identity | `auth`, `employee` | Issues RS256 tokens (`TokenIssuer`) and publishes their public key at `/.well-known/jwks.json`; owns users and employees. |
| Master data | `masterData` | Materials, categories, suppliers, stock levels. |
| Procurement | `purchaseRequisition`, `purchaseOrder`, `goodsReceipt`, `returnToVendor` | One flow: PR → PO → GR → RTV. |
| Finance | `invoice`, `payment`, `settings` | Three-way match, payments, and the settings they use (price tolerance). |
| Analytics | `dashboard` (`analytics` planned) | Read-only. The dashboard composes each service's reporting use case (API composition); event-fed read models can replace calls where needed. |
| Notification (planned) | `notification` | Reacts to events. |

`common` is the shared kernel (money, currencies, base types, document code sequences, the outbox and inbox, the
change history, attachments, FX valuation). It is a library: each extracted service gets its own copy and its own
`global_code_sequences`, `outbox_messages`, `processed_events`, `audit_log`, `attachments` and `exchange_rates`
tables. The shared code names no context's table; each context plugs its own data in:

| Shared feature | What each context provides |
|---|---|
| Change history | `@Audited` on its own JPA entities (history type, code, parent, read permission). |
| Attachments | An `AttachmentOwnerLookup` per document type it owns (does the document exist?). |
| FX valuation | A `SqlDocumentFxStore` per document type it owns (its table, amount and date). |
| User names | `UserDisplayNames`, provided by identity (`AuthUserDisplayNames`). |

## API gateway (`backend/gateway-service`)

The only way in from outside: the frontend's nginx proxies `/api` (and the API docs) to the gateway, which routes
them to the service that owns the path: identity (`/api/v1/auth`, `/api/v1/employees`, the JWK set), master data
(`/api/v1/masterdata`), finance (`/api/v1/invoices`, `/api/v1/payments`, `/api/v1/settings`, and the attachments of
invoices: `ownerType=INVOICE`), analytics (`/api/v1/dashboard`), each with the change history of its own records
(`/api/v1/audit/<Type>`), and procurement for the rest. Anything not routed (actuator, internal endpoints) answers 404.

```
browser ─▶ nginx (frontend) ─▶ gateway ─▶ procurement (or the service owning the path) ─▶ PostgreSQL
           sets X-Real-IP       trace id     verifies the token again,
                                rate limit   checks the permission
                                token check
```

| Filter | Does |
|---|---|
| `TraceIdFilter` | Gives every request an `X-Gateway-Trace-Id` (keeps the caller's when it is a safe value), on the request to the service and on the response. |
| `RateLimitFilter` | 120 requests a minute per client, 10 for sign-in and password reset. The client is `X-Real-IP` (set by nginx). Procurement keeps its own limiter as a second line. |
| `BearerTokenFilter` | Verifies a bearer token with Identity's JWK set (`/.well-known/jwks.json`, cached by Nimbus); refuses forged, expired and service tokens with 401 before any service sees them. A request without a token goes on: the service decides whether its path needs one. |

Configuration: `GATEWAY_PROCUREMENT_URI` (procurement's address), `GATEWAY_<SERVICE>_URI` for the others,
`GATEWAY_JWKS_URI` (defaults to Identity's JWK set), `GATEWAY_RATE_LIMIT_*`. CORS stays in the services for now. In
the browser the API is same-origin through nginx, so only the Vite dev server (calling a service directly) needs it.

## One database per service

Each service's data lives in its own PostgreSQL schema (`V13__schema_per_service.sql`), with its own copy of the
shared kernel's tables (phase 6: each service's "shared kernel tables" migration, which copied the rows that belong to
it from the `public` tables every service shared before). A service connects as its own database user, which owns its
schema and nothing else (`aws/deploy/db/service-roles.sql`), and sees only that schema. Moving a service to a
database server of its own is a dump of its schema.

| Schema | Tables |
|---|---|
| `identity` | users, refresh_tokens, password_reset_tokens, employees |
| `masterdata` | categories, materials, suppliers, supplier_payment_terms, material_stock_movements |
| `procurement` | purchase_requisitions(+lines), purchase_orders(+lines), goods_receipts(+lines), return_to_vendor(+lines) |
| `finance` | invoices(+lines), payments(+lines), app_settings |
| `analytics` | none yet |
| each of the above | its own shared kernel: global_code_sequences, outbox_messages, processed_events, audit_log, exchange_rates, attachments |
| `public` | the legacy shared tables (no service reads them) and procurement's migrations' history |

- Every entity of a context names its service's schema (`@Table(name = "users", schema = "identity")`);
  `EntitySchemaTest` fails the build when one does not. The shared kernel's entities name none.
- Native SQL and the shared kernel's entities name no schema: each service's connection runs `SET search_path TO
  <its schema>` (`spring.datasource.hikari.connection-init-sql`), and nothing else.
- Each service relays its own outbox to Kafka, keeps its own change history (the audit log page's feed is composed
  by analytics over `audit.v1.ChangeHistory`), its own attachments (the gateway routes attachment requests by owner
  type) and its own exchange rates (refreshed daily by procurement, finance and analytics).
- Attachment files (procurement's and finance's) are stored on disk by default (`app.attachments.storage=local`,
  `/data/attachments` on EC2) or in S3 (`app.attachments.storage=s3`, `S3AttachmentStorage`): one bucket, each
  service under its own prefix (`procurement/`, `finance/`), credentials from the task role. Only the bytes move; the
  metadata stays in each service's `attachments` table. `S3AttachmentStorageIT` runs it against an S3-compatible
  server.
- On EC2, `deploy.sh` applies the database users before the services start and again once procurement migrated (on
  a new database, procurement's legacy migrations still create every context's tables before moving them). Local
  `docker compose` does the same with two one-shot services, `db-roles` and `db-roles-handover`; the passwords
  default to local-only values (`DB_PASSWORD_*` in `.env`).
- No foreign key crosses two schemas (`SchemaPerServiceMigrationIT` checks it on a migrated database).
- New migrations name the schema of every table they touch (`alter table finance.invoices ...`).
- Rolling back to an image older than V13: run `db/rollback/V13__undo_schema_per_service.sql` first (it puts every
  table back in `public` and forgets V13; `SchemaPerServiceMigrationIT` runs it).

## Modules (`backend/`)

`backend/pom.xml` is the parent of every module. Build and test everything from `backend/` with `./mvnw test`; run
a service with `./mvnw -pl <module> -am spring-boot:run` (procurement: `-pl procurement-service`).

| Module | Holds | Depends on |
|---|---|---|
| `materia-common` | The shared kernel above (`com.materia.backend.common`). | Spring, JPA, Kafka |
| `materia-grpc` | The contracts between services (`src/main/proto/materia/<service>/v1`), the stubs generated from them (`com.materia.proto.*`), and the interceptors every service installs (`com.materia.grpc.*`). | `materia-common`, grpc-java |
| `materia-platform` | What every service runs on: security chain, token keys, error handling, database and Kafka setup, the gRPC server, channels and identity client. | `materia-common`, `materia-grpc` |
| `procurement-service` | Procurement (`purchaseRequisition`, `purchaseOrder`, `goodsReceipt`, `returnToVendor`): REST 8080, gRPC `procurement.v1` 9090; its legacy migrations create the schemas and own the legacy tables in `public`. Its docker compose service is `procurement` locally and still `backend` on EC2 (its ECR repository too), so a rollback finds the images it redeploys. | `materia-platform` |
| `identity-service` | Identity (`auth`, `employee`): REST 8082, gRPC `identity.v1` 9091; the only signer of tokens. | `materia-platform` |
| `masterdata-service` | Master data (`masterData`): REST 8083, gRPC `masterdata.v1` 9092; keeps stock from procurement's events (Kafka). | `materia-platform` |
| `finance-service` | Finance (`invoice`, `payment`, `settings`): REST 8084, gRPC `finance.v1` 9093; reads procurement's orders and receipts (`procurement.v1`) for the three-way match. | `materia-platform` |
| `analytics-service` | Analytics (`dashboard`, `analytics`): REST 8085; composes the other services' figures (`procurement.v1`, `masterdata.v1`, `finance.v1` metrics) and owns no table. | `materia-platform` |

### gRPC contracts

Each contract mirrors a public use case and its view, field for field. Reads cross services by gRPC; a change in
another service travels as an event (stock on order, received goods, replaced goods). The one exception is raising a
replenishment requisition: master data needs the new requisition's code at once, so it is a gRPC command, never
retried automatically, so one reorder never raises two requisitions.

| Contract (server) | Mirrors | Callers |
|---|---|---|
| `identity.v1.UserDirectory` | `UserDirectoryUseCase` (`findUser`, `findUsersWithRole`) | procurement, finance |
| `masterdata.v1.MaterialCatalog` | the reads of `MaterialInventoryUseCase` | procurement |
| `procurement.v1.PurchaseOrderLookup` | `PurchaseOrderLookupUseCase` | finance |
| `procurement.v1.GoodsReceiptLookup` | `GoodsReceiptLookupUseCase` | finance |
| `procurement.v1.Replenishment` | `ReplenishmentUseCase` (the open-requisition check, and the replenishment commands) | master data |
| `procurement.v1.ProcurementMetrics` | `RequisitionMetricsUseCase`, `PurchaseOrderMetricsUseCase`, `GoodsReceiptMetricsUseCase`, `ReturnMetricsUseCase` | analytics |
| `finance.v1.FinanceMetrics` | `InvoiceMetricsUseCase`, `PaymentMetricsUseCase` | analytics |
| `masterdata.v1.MasterDataMetrics` | `MaterialMetricsUseCase` | analytics |

`GrpcContractsTest` holds each contract to its use cases: a method added to one of them fails the build until it has
its RPC, or is listed as deliberately left off (a write, which travels as an event). The reporting contracts share
their shapes (`common.v1.metrics`: reporting rates, periods, month amounts, recent documents).

Conventions: ids are UUID strings, amounts are `common.v1.Money` (a decimal string and a currency), dates are ISO
strings, and a lookup that finds nothing returns an empty `optional` field. A field number is never reused; a
breaking change is a new package version (`v2`).

Interceptors every service installs (`materia-grpc`):

| Side | Interceptor | Does |
|---|---|---|
| Client | `CallerTokenClientInterceptor` | Sends the user's token, so the called service applies that user's rights. |
| Client | `TraceIdClientInterceptor` | Sends the trace id of the request being served. |
| Client | `DefaultDeadlineClientInterceptor` | Gives a call without a deadline a default one: a few seconds (`app.grpc.<service>.deadline`). |
| Server | `AuthenticationServerInterceptor` | Refuses a call without a valid token (`UNAUTHENTICATED`) and exposes the caller (`GrpcCaller`). |
| Server | `AuthorizationServerInterceptor` | Refuses a call its caller may not make (`PERMISSION_DENIED`), by the RPC's policy in `GrpcPolicies`; an RPC without a policy is refused. |

**Who may call each RPC (`GrpcPolicies`).** Part of the contracts, like the .proto files; `GrpcPoliciesTest` fails when
an RPC has no policy. A policy names the user permissions that may call the RPC (users reach it with their own token,
forwarded by the service serving their request) and the services that may call it with their service token (work no
user started: jobs, event handlers, seeding; `service:<client id>`). A read RPC asks for the permission that reads the
same data over the REST API, so a call never shows a user more than the REST API would; a write RPC (raising a
replenishment) for the permissions of the actions that lead to it. Only issuing a service token is open; the account
directory (`identity.v1.UserDirectory`) admits any authenticated caller, since every service shows who did what.
`DashboardAccessPolicyTest` checks every role's dashboard only asks for figures its role may read.
| Server | `TraceIdServerInterceptor` | Logs the call under the caller's trace id. |
| Server | `ExceptionTranslatingServerInterceptor` | Turns the shared exceptions into status codes; `GrpcErrors.toException` turns them back on the client. |


## Rules

1. **A context's `domain` and `application` layers know no other context and no gateway.** They reach the
   outside through their own outbound ports (`domain/ports/out`) with their own value types.
2. **Other code uses a context only through its public API:** `domain.ports.in`, `application.dtos`, and the
   `domain.enums`, `domain.events` and `domain.exceptions` those expose. In practice only infrastructure
   adapters (`infrastructure/adapters/out/<other context>`) and the gateway do this.
3. **`common` knows no context.**
4. **A Spring listener (`@EventListener`, `@TransactionalEventListener`) only handles its own context's events.**
   Another context's events are handled by an `IntegrationEventHandler` on their JSON payload, as they will be when
   they arrive from another service.
5. **Contexts of different services use none of each other's classes** (`ModuleBoundariesTest`): they call each
   other's gRPC contracts and react to each other's events. Contexts of one service (the four of procurement, invoice
   and payment) still use each other's public API directly. Dev seeders are the exception.

## How a call between services is wired

```
invoice.application.InvoiceService                        (finance)
   └─ InvoicedOrders                    (invoice/domain/ports/out, invoice-owned records)
        └─ ProcurementInvoicedOrders    (invoice/infrastructure/adapters/out/procurement)
             └─ PurchaseOrderLookupBlockingStub          (procurement.v1, materia-grpc)
                  ╎ in-process gRPC channel, interceptors (GrpcConfig)
             PurchaseOrderLookupGrpcService               (purchaseOrder/infrastructure/adapters/in/grpc)
                  └─ PurchaseOrderLookupUseCase           (procurement)
```

Between the contexts of one service (and in tests) the server and channel are in process and run the call on the caller's thread, so it joins the
caller's transaction as the direct call it replaced did. Extracting a service changes the channel's address; the
adapter, the service and their tests do not change. Tests reach other services the same way
(`support/fixtures/InProcessGrpc`, used by `CrossContextWiring`).

A call carries the caller's token: the one of the gRPC call being served, else the signed-in user's (kept by the
gateway's JWT filter in a `BearerTokenAuthentication`), else a service token (`subject = service:<client id>`, e.g. `service:procurement`,
`ROLE_SERVICE`) for work no user started. The REST API refuses service tokens.

Public lookups added for this purpose:

| Context | Public use cases |
|---|---|
| auth | `UserDirectoryUseCase` (`UserSummary`), `AccountProvisioningUseCase` |
| masterData | `MaterialInventoryUseCase` (`MaterialReference`, `StockMovement`) |
| purchaseRequisition | `ReplenishmentUseCase`, `RequisitionUseCase` (`RequisitionLineSummary`) |
| purchaseOrder | `PurchaseOrderLookupUseCase` (`PurchaseOrderView`), `PurchaseOrderReceivingUseCase` |
| goodsReceipt | `GoodsReceiptLookupUseCase` (`GoodsReceiptView`) |
| returnToVendor | `ReplacementQuantitiesUseCase` |

Reporting use cases (each computes its own figures on its own data; the dashboard composes them):
`RequisitionMetricsUseCase`, `PurchaseOrderMetricsUseCase`, `GoodsReceiptMetricsUseCase`, `InvoiceMetricsUseCase`,
`PaymentMetricsUseCase`, `MaterialMetricsUseCase`, `ReturnMetricsUseCase`.

In tests, `support/fixtures/CrossContextWiring` builds the real adapter and use case over the other context's
mocked repositories, so service tests keep stubbing what they always did.

## Events, the outbox and the inbox

- Every `DomainEvent` published through Spring is also written to `outbox_messages` in the same transaction
  (`OutboxRecorder`): it is stored if and only if the change commits. Events are always published through Spring,
  whatever `app.messaging.type` is; nothing sends to Kafka directly.
- `OutboxRelay` publishes pending messages to Kafka, oldest first, on `materia.<context>.events`, keyed by
  aggregate id, in an envelope `{eventId, eventType, sourceContext, aggregateId, occurredAt, payload}`. Delivery is
  at least once. Enable it with `app.outbox.relay.enabled=true` and `app.messaging.type=kafka` (tuning:
  `app.outbox.relay.interval-ms`, `batch-size`, `send-timeout-ms`).
- A context reacts to another context's events with an `IntegrationEventHandler`: it reads the envelope's JSON
  payload, never the publisher's Java class, so it works unchanged once the publisher is a separate service.
  `IntegrationEventDispatcher` runs each handler in its own transaction and records the event in
  `processed_events` (the inbox) in that transaction: a repeated delivery is skipped, a failed one is retried.
- Delivery today is in-process, after the publisher's commit (`InProcessIntegrationEvents`). With
  `app.messaging.type=kafka`, `KafkaIntegrationEventConsumer` also consumes `materia.*.events` (raw JSON, no type
  information from messages); the inbox makes the double delivery harmless. An extracted service sets
  `app.events.in-process.enabled=false` and receives only from Kafka. Tests use `app.events.in-process.mode=immediate`
  (inside the test transaction); `IntegrationEventsIT` and `ReceiptStockEventsIT` check the after-commit delivery.
- Events must stay plain data (strings, numbers, ids, enums): they are serialized to JSON.
- `KafkaEventsIT` runs the whole pipeline against a real broker (Testcontainers): commit, outbox, relay, topic,
  consumer, handler. The local Docker Compose stack runs with Kafka (`APP_MESSAGING_TYPE=kafka`,
  `APP_OUTBOX_RELAY_ENABLED=true`); so does the AWS stack (one broker on the EC2 host).

### Procurement and master data

Procurement changes stock only through events; master data applies them:

| Event (publisher) | Handler (consumer) | Effect |
|---|---|---|
| `StockOnOrderChanged` (purchaseOrder) | `StockOnOrderHandler` (masterData) | Adds or releases stock on order. |
| `GoodsReceiptLineReceived` (goodsReceipt) | `ReceivedGoodsStockHandler` (masterData) | Accepted goods enter stock; the received quantity leaves stock on order. |
| `ReceiptStockApplied` (masterData) | `ReceiptStockLevelsHandler` (goodsReceipt) | The receipt line records the stock before and after. |
| `ReplacementPutOnOrder` (returnToVendor) | `StockOnOrderHandler` (masterData) | Returned goods the supplier replaces are on order again. |

Procurement reads master data synchronously (material lookups, `masterdata.v1.MaterialCatalog`), and master data
raises replenishment requisitions in procurement with a command (`procurement.v1.Replenishment`, one direction).

## Not done yet

- **Retiring the legacy tables in `public`:** once every environment has run each service's copy migration, a
  procurement migration can drop them (no service reads them).
- **The dashboard's metrics** cross services through fine-grained RPCs: a load makes 32 calls for an admin or a
  purchaser and 23 for a receiver (37 for an admin with foreign currencies and spend), pinned by
  `DashboardAccessPolicyTest`. They run side by side in two waves (`DashboardService`, on `CallContextExecutor`,
  which keeps each call the user's), so a load waits for about six calls in a row, not all of them: on the local stack
  an admin's load went from a median of 1.3 s (8 s at the 90th percentile) to 0.5 s (1.1 s). If that is still too
  slow, an event-fed read model in analytics can replace the hottest figures.
