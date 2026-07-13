<div align="center">

# Squarely

**Split expenses, track recurring bills, and settle up with two-party acknowledgement —
a trusted shared record without exchanging payment screenshots.**

Split a bill any way you like — equal, exact, percentages, shares — and it becomes a running
ledger of who owes whom. Settling isn't one person clicking "paid": the payer claims it, the
receiver confirms or disputes it, and only then does the balance clear.

[![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square&logo=openjdk)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-green?style=flat-square&logo=springboot)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2023.0.3-6DB33F?style=flat-square&logo=spring)](https://spring.io/projects/spring-cloud)
[![Apache Kafka](https://img.shields.io/badge/Kafka-3.7-231F20?style=flat-square&logo=apachekafka)](https://kafka.apache.org)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?style=flat-square&logo=postgresql)](https://www.postgresql.org)
[![React](https://img.shields.io/badge/React-18-blue?style=flat-square&logo=react)](https://react.dev)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.6-3178C6?style=flat-square&logo=typescript)](https://www.typescriptlang.org)

</div>

---

## Architecture

```
        React SPA (Vite · TS · Tailwind)  :5173
                        │
        gateway-service :8080  ──lb://──►  discovery-service :8761
        routes · CORS · rate limit          Eureka registry
                        │                          ▲
      ┌─────────────┬───┴───────┬─────────────┐    │ every service registers
      ▼             ▼           ▼             ▼    │
  auth :8081   group :8082  ledger :8083  notification :8084
  JWT·BCrypt   expenses     balances      recurring bills
  refresh      splits       settlements   notifications
      │             └───────────┼─────────────┘
      │                    Kafka (KRaft)
      │        expense.added · payment.claimed/acknowledged/disputed
      │                         │
  ────┴─────────────────────────┴────────────────────────────
        PostgreSQL 16 — one instance, four databases
             auth · groups · ledger · notify
```

Shared `common` module: JWT issue/validate, resource-server security config, Kafka event
contracts, global exception handler. Redis holds only ephemeral counters: the gateway's rate
limiter buckets and auth-service's failed-login throttle. Nothing durable lives there.

## Services

| Service                  | Port (host → internal) | Owns                                                      | Responsibility                                                                                                            |
| ------------------------ | ---------------------- | --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| **discovery-service**    | 8761                   | in-memory registry                                        | Eureka server. Everything registers here; the gateway resolves `lb://` through it                                         |
| **gateway-service**      | 8080                   | stateless                                                 | Single public entrypoint: path-prefix routes, CORS, Redis leaky-bucket rate limit keyed on the JWT subject (IP only for anonymous callers), fail-closed |
| **auth-service**         | 8081 → 8080            | `users`, `refresh_tokens`                                 | Signup/login, JWT issuance, rotating refresh tokens with family reuse-detection, per-account failed-login throttle, batch id → display-name lookup |
| **group-service**        | 8082 → 8080            | `groups`, `group_members`, `expenses`, `expense_splits`   | Groups, membership, expenses, split calculation (equal/exact/percent/shares)                                              |
| **ledger-service**       | 8083 → 8080            | `ledger_entries`, `settlements`                           | Append-only ledger, balance derivation, debt simplification, settlement state machine, idempotency, optimistic locking    |
| **notification-service** | 8084 → 8080            | `notifications`, `recurring_rules`, `payment_obligations` | Kafka consumers, recurring-bill scheduler, in-app notifications, SSE stream                                               |

Clients go through the gateway on `:8080`. The per-service host ports are published for
direct debugging only — and every service validates its own JWT, so the gateway is **not** a
trust boundary: it routes, throttles, and terminates CORS.

## Tech Stack

**Backend** — Java 17 · Spring Boot 3.3.5 · Spring Cloud 2023.0.3 (Gateway + Eureka +
LoadBalancer) · Spring Security with JJWT (symmetric HMAC; the secret's length picks
HS256/384/512) · Spring Data JPA · Flyway (`ddl-auto: validate` — Flyway owns the schema) ·
Kafka 3.7 (KRaft) · PostgreSQL 16 · Maven multi-module · Lombok.

**Frontend** — React 18 · TypeScript 5.6 · Vite 5 · Tailwind (dark only) · React Router v6 ·
TanStack Query v5 · Three.js on the landing page, lazy-loaded so the ~470 kB never ships to
the authenticated app · a hand-rolled `fetch` wrapper that refreshes the access token on 401.
No global state library, no design system — shared primitives in `ui.tsx` cover every page.

## Kafka Event Bus

Topics live in `common`'s `Topics`; consumers lock `spring.json.trusted.packages` to
`com.squarely.common.events`.

| Topic                  | Producer | Consumer(s)          | Purpose                                                          |
| ---------------------- | -------- | -------------------- | ---------------------------------------------------------------- |
| `expense.added`        | group    | ledger, notification | Ledger records per-person debt; participants notified            |
| `payment.claimed`      | ledger   | notification         | Payer says "I've paid" → receiver asked to confirm               |
| `payment.acknowledged` | ledger   | notification         | Receiver confirms → ledger entry written in the same transaction |
| `payment.disputed`     | ledger   | notification         | Receiver rejects the claim → payer notified                      |

> `payment.due` is declared in `Topics` with a matching event record, but nothing ever sends
> it — dead wiring. Recurring due/overdue notifications are written in-process by
> notification-service's own scheduler.

## Project Structure

```
split/
├── pom.xml                  # Maven parent — aggregates the 6 modules
├── docker-compose.yml       # Full stack: infra + registry + gateway + services
├── common/                  # Shared lib: events/ security/ web/ — not deployable
├── discovery-service/       # :8761 — Eureka (@EnableEurekaServer)
├── gateway-service/         # :8080 — routes live in application.yml
├── auth-service/            # :8081 — api/ domain/ repo/ service/ + Flyway migrations
├── group-service/           # :8082 — + split/ (SplitCalculator)
├── ledger-service/          # :8083 — + debt/ (DebtSimplifier), settlement/ (state machine)
├── notification-service/    # :8084 — + recurring/ (RecurrenceCalculator, scheduler)
├── infra/                   # Dockerfile (parametrized by SERVICE), init-multi-db.sh
└── frontend/                # React SPA — api.ts, auth.tsx, ui.tsx, pages/
```

## Key Design Decisions

**Nothing materialised, everything derived.** Balances are computed from the append-only
`ledger_entries` on every read. Corrections are reversal rows, never edits — the ledger stays
a true audit trail.

**Settlement is a state machine, not a status flag.** create → claim → acknowledge/dispute
routes through `SettlementStateMachine`. Acknowledging an already-settled payment is a 409;
only the receiver can acknowledge or dispute (403 for anyone else, payer included).
`@Version` on `settlements` means two concurrent acknowledgements can't both win.

**Idempotency lives in Postgres, not a cache.** `Idempotency-Key` is a UNIQUE column, and a
`(ref_type, ref_id, debtor, creditor)` UNIQUE constraint on ledger entries makes Kafka
redelivery safe: replaying `expense.added` writes the row once.

**Refresh tokens are single-use, and reuse burns the family.** Only a SHA-256 hash is stored.
Rotation is an atomic conditional `UPDATE … WHERE revoked = false`, so concurrent refreshes
serialise and exactly one wins. Presenting an already-spent token means the chain leaked, so
the whole family is revoked. The revocation runs in `REQUIRES_NEW` **on purpose** — the
request ends in a 401, which rolls back its own transaction, and joining it would roll the
revocation back too, making reuse detection a silent no-op.

> **Client contract:** the server can't tell an attacker replaying a stolen token from a
> client firing two refreshes at once. It chooses strict, so clients must single-flight their
> refresh call and never blindly retry one whose response was dropped. `api.ts` is the only
> thing that refreshes and is the place to enforce it. If flaky-network logouts show up, the
> fix is a `revoked_at` grace window — noted in `AuthService.refresh()`, not built.

**A recurring rule is a 1:1 IOU, not a group split.** `memberUserIds` is always exactly
`[debtorId, creditorId]` — order encodes direction, so no "who pays whom" field was needed.
Settling one creates a **real** settlement via the same `POST /settlements`, then
cross-references the id back onto the obligation. One settlement lifecycle, not two.

**Kafka for cross-service events, direct calls for same-service work.** Recurring due/overdue
notifications never leave notification-service, so they're a method call — no topic, no
redelivery semantics for something already in the same transaction.

**Gateway routes, it doesn't authenticate.** One public port, explicit path-prefix routes,
CORS, and a Redis leaky bucket (20 req/s, 5 on `/auth`; over the limit is a 429, never a
queued request — queueing at the edge trades a fast rejection for held connections). JWT
validation stays in each service, so bypassing the gateway gains nothing.

**Rate limits key on the user, not the IP.** IP is a bad bucket key in both directions: too
coarse (a NAT'd office shares one bucket) and too easy to escape (proxy pools are cheap, and
one IPv6 /64 hands a single machine 2^64 addresses). `UserKeyResolver` verifies the access
token's signature and keys on `sub`, so escaping your bucket means forging an HMAC. Anonymous
callers — i.e. `/auth/**` — fall back to IP, with IPv6 bucketed by /64.

**The limiter fails closed; Spring's does not.** `RedisRateLimiter` returns "allowed" when
Redis is unreachable, on the theory that traffic shouldn't depend on Redis. That's backwards
here: Redis down is exactly when an attacker wants the brakes off, and every admitted login
costs a BCrypt hash. `FailClosedRateLimiter` inverts it. The price is that Redis is now a
hard dependency of the edge and of login — the single node in compose is a SPOF for
authentication, and deserves a replica before this goes anywhere real.

**Credential stuffing is stopped per-account, not per-IP.** A stuffing run tries one password
against a million accounts from a million IPs; every IP sends one request, so no per-IP bucket
ever fills. `LoginThrottle` counts failures per account in Redis (hashed emails, TTL'd
counters — nothing durable) and escalates: 5 free attempts, then 30s doubling to a 15-minute
ceiling, cleared by a correct password. Escalating delay rather than hard lockout, because a
lockout hands anyone a griefing DoS — fail someone's login six times and they're locked out.
Unknown emails are counted too, or "no such user" becomes a free account-enumeration oracle.
`/auth/refresh` is deliberately exempt: failing login closed is a bounded annoyance, but
failing refresh closed would sign out every active user the moment Redis blipped.

**Discovery is Eureka; routes stay explicit.** Route URIs are `lb://auth-service` and
friends, so the load balancer picks a live instance from the registry. The gateway's
_discovery locator_ — which would auto-expose every registered service at `/SERVICE-NAME/**`
— stays **off**: a service must be in the route map to be publicly reachable, so registering
never publishes anything by accident. The registry is a single standalone node; clients serve
from their cached copy if it dies, so add a peer replica only when that stops being fine.

**No RBAC. Single-instance SSE.** No admin role, no `@PreAuthorize` anywhere.
`/notifications/stream` fans out from an in-memory emitter registry — correct for one
instance; scaling notification-service would need Redis pub/sub behind it. Not built.

## Contributing

Branch, commit, PR. Follow the existing `api/` / `service/` / `domain/` / `repo/` layout, and
add tests for new logic in the money and state-machine cores — `SplitCalculator`,
`DebtSimplifier`, `SettlementStateMachine`, `RecurrenceCalculator` all have suites already.
`mvn test` runs everything.

> **Anything touching transactions, revocation, or persistence must be tested against a real
> database, not Mockito.** A mock verifies a call was _made_, never that it _committed_ —
> which is exactly how refresh-token family revocation shipped as a silent no-op: the
> revoking UPDATE joined the caller's transaction and was rolled back by the 401 thrown right
> after, and every mocked test still passed.

---

<div align="center">

Split it evenly. Settle it for real.

</div>
