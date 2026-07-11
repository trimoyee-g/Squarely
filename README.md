<div align="center">

# Squarely

**Split expenses, track recurring bills, and settle up with two-party acknowledgement —
a trusted shared record without exchanging payment screenshots.**

Squarely lets a group split a bill any way they like — equal, exact amounts, percentages,
or shares — and turns it into a running ledger of who owes whom. Settling up isn't just
one person clicking "paid": the payer claims it, the receiver confirms or disputes it, and
only then does the balance actually clear. Recurring costs (rent, Wi-Fi, anything that
repeats between two people) get their own tracker with the same settlement handshake, not
a separate parallel system.

[![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square&logo=openjdk)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-green?style=flat-square&logo=springboot)](https://spring.io/projects/spring-boot)
[![Apache Kafka](https://img.shields.io/badge/Kafka-3.7-231F20?style=flat-square&logo=apachekafka)](https://kafka.apache.org)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?style=flat-square&logo=postgresql)](https://www.postgresql.org)
[![React](https://img.shields.io/badge/React-18-blue?style=flat-square&logo=react)](https://react.dev)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.6-3178C6?style=flat-square&logo=typescript)](https://www.typescriptlang.org)
[![Vite](https://img.shields.io/badge/Vite-5-646CFF?style=flat-square&logo=vite)](https://vitejs.dev)

</div>

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Services](#services)
- [Tech Stack](#tech-stack)
- [Kafka Event Bus](#kafka-event-bus)
- [API Reference](#api-reference)
- [Getting Started](#getting-started)
- [Environment Variables](#environment-variables)
- [Project Structure](#project-structure)
- [Key Design Decisions](#key-design-decisions)
- [Contributing](#contributing)

---

## Overview

Squarely is a microservices platform where users can:

- **Split any way** — equal, exact amounts, percentages, or shares, with largest-remainder
  rounding so split cents always sum back to the original total
- **Settle up for real** — a settlement isn't "mark as paid." The payer claims it, the
  receiver acknowledges or disputes it, and the ledger only clears on acknowledgement —
  enforced by a state machine, not just a status flag
- **Track recurring bills** — rent, Wi-Fi, anything that repeats between two people, on a
  weekly/monthly/custom-interval cadence, generating real settlements when a bill comes due
  instead of a separate "mark paid" shortcut
- **Record personal debts** — a 1:1 IOU with no group attached
- **See the full picture** — a dashboard of net balances, a per-group ledger, and an
  activity feed backed by real notification events, not placeholder text
- **Trust the numbers** — every balance is derived from an append-only ledger; corrections
  are reversal rows, not edits, so the history stays auditable

Every account can see and act on both sides of a relationship — group membership, the
counterparty on a recurring rule, and the other party to a settlement all get visibility
into shared state, not just whoever created it.

---

## Architecture

```
                         ┌─────────────────────────────────┐
                         │          React Frontend          │
                         │  (Vite · TypeScript · Tailwind)  │
                         └──────────────┬──────────────────┘
                                        │ Vite dev proxy (no gateway)
              ┌─────────────┬───────────┼───────────┬─────────────────┐
              │             │           │           │                 │
       ┌──────▼──────┐┌─────▼─────┐┌────▼─────┐┌────▼──────────┐
       │ auth :8081  ││ group:8082││ledger:8083││notification:8084│
       │ JWT · BCrypt ││ expenses  ││ balances  ││ recurring bills │
       │              ││ splits    ││settlements││ notifications   │
       └──────┬──────┘└─────┬─────┘└────┬─────┘└────┬──────────┘
              │             │           │            │
              │             └─────┬─────┴────────────┘
              │                   │  publishes / consumes
              │            ┌──────▼──────────────┐
              │            │   Kafka (KRaft)      │
              │            │ expense.added        │
              │            │ payment.claimed       │
              │            │ payment.acknowledged  │
              │            │ payment.disputed      │
              │            └──────┬───────────────┘
              │                   │
       ┌──────▼───────────────────▼──────────────────────────┐
       │      PostgreSQL 16 — one instance, four databases     │
       │        auth  ·  groups  ·  ledger  ·  notify          │
       └────────────────────────────────────────────────────────┘
```

> Redis is provisioned in `docker-compose.yml` and wired into every service's environment,
> but nothing actually reads or writes to it yet — idempotency and locking are handled in
> Postgres. See [Key Design Decisions](#key-design-decisions).

Shared `common` module: JWT issue/validate, stateless resource-server security config,
Kafka event contracts (`Events`, `Topics`), and a global exception handler shared by all
four services.

---

## Services

| Service | Port (compose → internal) | Owns | Responsibility |
|---|---|---|---|
| **auth-service** | 8081 → 8080 | `users`, `refresh_tokens` (db `auth`) | Signup/login, JWT issuance, rotating refresh tokens, batch user lookup for other services |
| **group-service** | 8082 → 8080 | `groups`, `group_members`, `expenses`, `expense_splits` (db `groups`) | Groups, membership, expenses, split calculation (equal/exact/percent/shares) |
| **ledger-service** | 8083 → 8080 | `ledger_entries`, `settlements` (db `ledger`) | Append-only ledger, balance derivation, debt simplification, settlement state machine, idempotency, optimistic locking |
| **notification-service** | 8084 → 8080 | `notifications`, `recurring_rules`, `payment_obligations` (db `notify`) | Kafka consumers, recurring-bill scheduler, in-app notifications, SSE stream |

Every service binds to `8080` internally; docker-compose maps each to a distinct host port.
There's no API gateway in front of them yet — the frontend's Vite dev proxy (and, in
production, whatever reverse proxy you put in front) routes by path prefix directly to the
owning service. See the `ponytail:` comment in `frontend/vite.config.ts`.

---

## Tech Stack

### Backend

| Concern | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.3.5 |
| Auth | Spring Security · JWT (JJWT 0.12.6, HS256 shared secret) · BCrypt |
| Data access | Spring Data JPA · Hibernate |
| Migrations | Flyway (`ddl-auto: validate` — Flyway owns the schema, Hibernate never auto-generates DDL) |
| Async messaging | Apache Kafka 3.7 (KRaft mode, no ZooKeeper) via Spring Kafka |
| Validation | Jakarta Bean Validation (`spring-boot-starter-validation`) |
| Observability | Spring Boot Actuator (`health`, `info`) |
| Primary database | PostgreSQL 16 — one instance, one schema-per-service database |
| Build | Maven, multi-module (`squarely-parent` aggregates `common` + 4 services) |
| Utilities | Lombok |

### Frontend

| Concern | Technology |
|---|---|
| Framework | React 18 + TypeScript 5.6 |
| Build tool | Vite 5 |
| Styling | Tailwind CSS 3 — dark theme app-wide, no light mode |
| Routing | React Router v6 — public landing/login, everything else behind an auth guard |
| Server state | TanStack Query (React Query) v5 |
| 3D | Three.js — the marketing landing page's animated hero, lazy-loaded so the ~470 kB library only ships to visitors of `/`, not the authenticated app |
| HTTP client | Hand-rolled `fetch` wrapper (`api.ts`) with automatic access-token refresh on 401 |

No global state library, no design-system dependency, no CSS-in-JS — a handful of shared
primitives in `ui.tsx` (`Card`, `Button`, `Badge`, `Avatar`, `Tabs`, `SettlementStepper`,
plus a small hand-drawn icon set) cover every page.

---

## Kafka Event Bus

Topics are defined once in `common`'s `Topics` class and consumed with
`spring.json.trusted.packages` locked to `com.squarely.common.events`.

| Topic | Producer | Consumer(s) | Purpose |
|---|---|---|---|
| `expense.added` | group-service | ledger-service, notification-service | New expense in a group → ledger records the per-person debt; notification tells every participant |
| `payment.claimed` | ledger-service | notification-service | Payer marks "I've paid" → receiver is notified to confirm or dispute |
| `payment.acknowledged` | ledger-service | notification-service | Receiver confirms receipt → ledger entry written in the same DB transaction, payer notified |
| `payment.disputed` | ledger-service | notification-service | Receiver rejects the claim → payer notified |

> A fifth constant, `payment.due`, is declared in `Topics` and has a matching `PaymentDue`
> event record in `Events`, but nothing ever calls `kafkaTemplate.send()` with it — it's
> dead wiring. Recurring-bill due/overdue notifications are written directly to the
> `notifications` table by notification-service's own scheduler, in-process, not over Kafka.
> group-service only *produces* to Kafka — it has no `@KafkaListener` of its own.

---

## API Reference

All endpoints are hit directly on each service's port — there's no gateway to route through
yet. Every request other than the three listed as **public** below requires
`Authorization: Bearer <accessToken>`; a missing or invalid token gets a 401 from a shared
entry point in `common`. There's no role-based auth anywhere in the platform — every other
check (only the payer can claim, only the receiver can acknowledge, only the creator can
edit a recurring rule) is enforced by hand in service code, not `@PreAuthorize`.

### Auth — `:8081/auth`

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/auth/signup` | public | Create an account, returns an access + refresh token pair |
| POST | `/auth/login` | public | Log in with email + password |
| POST | `/auth/refresh` | public | Rotate a refresh token — the old one is revoked the moment the new one is issued |
| POST | `/auth/logout` | required | Revoke the caller's refresh tokens |
| GET | `/auth/me` | required | Current user's profile |
| GET | `/auth/internal/users?ids=1,2,3` | required | Batch-resolve user ids to display names/emails — used by the frontend to turn "User #4" into a real name everywhere |

### Groups & Expenses — `:8082`

| Method | Path | Description |
|---|---|---|
| POST | `/groups` | Create a group |
| GET | `/groups` | List groups the caller belongs to |
| POST | `/groups/{groupId}/members` | Add a member by user id |
| GET | `/groups/{groupId}/members` | List a group's members |
| DELETE | `/groups/{groupId}/members/{userId}` | Remove a member |
| POST | `/groups/{groupId}/expenses` | Add an expense with a split (`EQUAL` / `EXACT` / `PERCENT` / `SHARES`); publishes `expense.added` |
| GET | `/groups/{groupId}/expenses` | List a group's expenses |
| GET | `/expenses/{expenseId}` | Get one expense |

### Ledger & Settlements — `:8083`

| Method | Path | Description |
|---|---|---|
| GET | `/balances/me` | Caller's net balances across every group and personal debt |
| GET | `/balances/group/{groupId}` | A group's net balances plus the debt-simplified settle-up list |
| POST | `/personal-debts` | Record a 1:1 debt with no group attached |
| GET | `/ledger/group/{groupId}` | Raw append-only ledger entries for a group — the audit trail |
| POST | `/ledger/{entryId}/reverse` | Write a reversal entry for a ledger row |
| POST | `/settlements` | Create a settlement; send an `Idempotency-Key` header to make retries return the same settlement instead of creating a duplicate |
| POST | `/settlements/{id}/claim` | Payer marks "I've paid" (403 if the caller isn't the payer) |
| POST | `/settlements/{id}/acknowledge` | Receiver confirms receipt → ledger entry written, `payment.acknowledged` published (403 if not the receiver, 409 if not currently `PAYMENT_CLAIMED`) |
| POST | `/settlements/{id}/dispute` | Receiver rejects the claim (same 403 guard as acknowledge) |
| GET | `/settlements/{id}` | Get one settlement (403 unless the caller is the payer or receiver) |
| GET | `/settlements` | List settlements the caller is party to |

### Notifications & Recurring — `:8084`

| Method | Path | Description |
|---|---|---|
| GET | `/notifications?unreadOnly=` | List the caller's notifications |
| GET | `/notifications/unread-count` | `{ "count": n }` |
| POST | `/notifications/{id}/read` | Mark one notification read |
| GET | `/notifications/stream` | Server-sent-events stream of the caller's notifications (single-instance in-memory fan-out — see [Key Design Decisions](#key-design-decisions)) |
| POST | `/recurring` | Create a recurring rule — a 1:1 IOU with a cadence (`WEEKLY` / `MONTHLY` / `CUSTOM`) and a due date |
| GET | `/recurring` | List active rules the caller created *or* is the counterparty on |
| PATCH | `/recurring/{id}` | Edit a rule (403 unless the caller created it) |
| DELETE | `/recurring/{id}` | Soft-delete a rule (`active = false`) — stops future bills without touching history (403 unless the caller created it) |
| GET | `/obligations` | List generated bill instances for the caller's rules, each with a `status` (`UPCOMING` / `DUE` / `OVERDUE` / `SETTLED`) and an optional `settlementId` once settling has started |
| POST | `/obligations/{id}/settle` | Attach a settlement id to an obligation and mark it settled — called after the frontend creates the actual settlement via ledger-service, not instead of it |
| POST | `/recurring/run` | Manually fire the same tick the scheduler runs daily at 06:00 — generates due obligations and advances UPCOMING → DUE → OVERDUE. Useful for demos: a rule created today has no bill instance until a tick actually runs |

---

## Getting Started

### Prerequisites

- Docker Desktop (6 GB+ RAM allocated) for the one-command path, **or** JDK 17 + Maven +
  Node 18+ for running services individually
- Git

### Option A — everything via Docker Compose

```bash
git clone <repo-url>
cd split
docker compose up --build
```

Brings up Postgres (4 databases), Redis, Kafka (KRaft, single broker), and all four Spring
Boot services. Then start the frontend separately:

```bash
cd frontend && npm install && npm run dev
```

Frontend: **http://localhost:5173** · Services: **8081**-**8084**

### Option B — infra in Docker, services run locally

```bash
# 1. Infrastructure only
docker compose up -d postgres redis kafka
# Postgres: one instance, four databases (auth, groups, ledger, notify)
# Kafka (KRaft, no ZooKeeper) reachable at localhost:29092 from the host

# 2. Build all four service jars
mvn -q clean package -DskipTests

# 3. Run each service (env vars override application.yml -- see below)
SERVER_PORT=8081 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/auth \
  SPRING_DATASOURCE_USERNAME=squarely SPRING_DATASOURCE_PASSWORD=squarely \
  java -jar auth-service/target/auth-service-0.1.0.jar
# ...repeat for group-service (8082/groups), ledger-service (8083/ledger),
#    notification-service (8084/notify) -- the last three also need
#    SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:29092

# 4. Frontend
cd frontend && npm install && npm run dev
```

### Smoke test

With all four services running (either option):

```bash
python smoke-test.py
```

Stdlib-only, no dependencies. Exercises the real running system end-to-end: signup for two
users → JWT + refresh rotation (and confirms the rotated-away token is revoked) → group +
membership → an equal-split expense → polls until Kafka has propagated it to ledger-service
→ balance and debt-simplification checks → settlement creation with an `Idempotency-Key`
(confirms retries return the same settlement) → the full claim/acknowledge handshake,
including the 403 receiver-only guard and the 409 already-settled guard → confirms balances
zero out → polls notification-service until both users' events arrive → a personal (no
group) debt → a recurring rule, a manual tick, and confirms an obligation was generated.

---

## Environment Variables

Every service reads these the same way -- an env var if set, otherwise the default baked
into that service's `application.yml`.

| Variable | Default | Used by |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/<auth\|groups\|ledger\|notify>` | all 4 services (default database name differs per service) |
| `SPRING_DATASOURCE_USERNAME` | `squarely` | all 4 services |
| `SPRING_DATASOURCE_PASSWORD` | `squarely` | all 4 services |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | group, ledger, notification (auth-service has no Kafka dependency) |
| `SECURITY_JWT_SECRET` | `dev-only-super-secret-change-me-256bit-minimum-length!!` | all 4 services -- **change before deploying anywhere real** |
| `SERVER_PORT` | `8080` | all 4 services, if you need to run more than one locally at once outside Docker |

Additionally, docker-compose sets `SPRING_DATA_REDIS_HOST=redis` on every service, but no
service actually depends on Spring Data Redis or reads that variable -- it's provisioned,
not used (see [Key Design Decisions](#key-design-decisions)).

`security.jwt.access-ttl` (`PT15M`) and `security.jwt.refresh-ttl` (`P30D`) are hardcoded in
auth-service's `application.yml`, not env-configurable. notification-service's scheduler
cadence (`recurring.tick-cron`, default `0 0 6 * * *` -- 6am daily) is likewise only a
code-level default.

---

## Project Structure

```
split/
├── pom.xml                     # Maven parent -- aggregates the 5 modules below
├── docker-compose.yml          # Full stack: infra + all 4 services
├── smoke-test.py                # Stdlib-only end-to-end test against the running system
│
├── common/                     # Shared library -- not independently deployable
│   └── src/main/java/com/squarely/common/
│       ├── events/                 # Topics.java, Events.java -- Kafka contracts
│       ├── security/                # JwtService, JwtAuthFilter, AuthContext,
│       │                            #   ResourceServerSecurityConfig
│       └── web/                     # GlobalExceptionHandler
│
├── auth-service/                # :8081 -- signup/login/JWT/refresh tokens
│   └── src/main/java/com/squarely/auth/
│       ├── api/ (AuthController, Dtos)     ├── domain/ (User, RefreshToken)
│       ├── repo/                           └── service/ (AuthService)
│
├── group-service/               # :8082 -- groups, members, expenses, splits
│   └── src/main/java/com/squarely/group/
│       ├── api/ (GroupController, Dtos)    ├── domain/ (ExpenseGroup, GroupMember, Expense, ExpenseSplit)
│       ├── repo/                           ├── service/ (GroupService)
│       └── split/ (SplitCalculator, SplitType)
│
├── ledger-service/              # :8083 -- balances, settlements, audit trail
│   └── src/main/java/com/squarely/ledger/
│       ├── api/ (LedgerController, SettlementController, Dtos)
│       ├── domain/ (LedgerEntry, Settlement)   ├── kafka/ (LedgerConsumer)
│       ├── repo/                               ├── service/ (BalanceService, LedgerService, SettlementService)
│       ├── debt/ (DebtSimplifier)              └── settlement/ (SettlementStateMachine, SettlementStatus)
│
├── notification-service/        # :8084 -- notifications, recurring bills, scheduler
│   └── src/main/java/com/squarely/notification/
│       ├── api/ (NotificationController, RecurringController, Dtos)
│       ├── domain/ (Notification, RecurringRule, PaymentObligation)
│       ├── kafka/ (EventConsumer)              ├── recurring/ (RecurrenceCalculator)
│       ├── repo/                               └── service/ (NotificationService, RecurringService, RecurringScheduler)
│
├── infra/                       # Dockerfile (multi-stage, parametrized by SERVICE build arg),
│                                 #   init-multi-db.sh (creates the 4 Postgres databases)
│
└── frontend/                    # React + TypeScript SPA
    └── src/
        ├── App.tsx               # Routing -- public landing/login, auth-gated everything else
        ├── api.ts                 # fetch wrapper with automatic access-token refresh
        ├── auth.tsx                # Auth context/provider
        ├── users.ts                 # Batch user-id -> display-name resolution + cache
        ├── ui.tsx                    # Shared primitives: Card, Button, Badge, Avatar,
        │                            #   Tabs, SettlementStepper, icon set
        ├── types.ts                  # TypeScript types mirroring backend DTOs
        └── pages/                     # Landing, Login, Dashboard, Groups, GroupDetail,
                                        #   Settlements, Recurring
```

---

## Key Design Decisions

**Materialised nothing, everything derived.** Balances are never stored -- they're computed
from the append-only `ledger_entries` table on every read. Corrections are reversal rows,
not edits or deletes, so the ledger stays a true audit trail.

**Settlement is a state machine, not a status flag.** Every transition -- create -> claim ->
acknowledge/dispute -- routes through `SettlementStateMachine`. Acknowledging an
already-settled payment isn't just discouraged, it's a 409; only the receiver can
acknowledge or dispute (403 for anyone else, including the payer).

**Idempotency lives in Postgres, not a cache.** A settlement's `Idempotency-Key` is a
UNIQUE column -- retries and double-clicks return the identical settlement rather than
creating a duplicate. A `(ref_type, ref_id, debtor, creditor)` UNIQUE constraint on ledger
entries makes Kafka redelivery safe too: replaying `expense.added` twice writes the ledger
row once.

**Optimistic locking, not pessimistic.** `@Version` on `settlements` means two concurrent
acknowledgements can't both win -- the loser gets a 409, not a silently-overwritten row.

**A recurring rule is a 1:1 IOU, not a group split.** `memberUserIds` on a `RecurringRule`
is always exactly `[debtorId, creditorId]` -- order encodes direction, so no separate
"who pays whom" field was needed. Both parties can see the rule; only the creator can edit
or delete it.

**Settling a recurring bill creates a real Settlement, not a shortcut.** Marking a bill
"paid" doesn't just flip a status -- it calls the same `POST /settlements` endpoint an
expense settle-up would, then cross-references the resulting `settlementId` back onto the
`payment_obligations` row. The claim/acknowledge/dispute handshake is identical either way;
there's exactly one settlement lifecycle in the whole system, not two.

**Kafka for cross-service events, direct calls for same-service work.** `expense.added`,
`payment.claimed/acknowledged/disputed` cross service boundaries and go over Kafka.
Recurring-bill due/overdue notifications never leave notification-service, so they're a
plain method call -- no topic, no consumer, no redelivery semantics needed for something
that's already in the same transaction.

**Redis is provisioned, not used.** It's in `docker-compose.yml` and every service depends
on it being healthy, but no service declares `spring-boot-starter-data-redis` or reads from
it -- durable idempotency already lives in Postgres UNIQUE constraints. Add Redis only if a
cache or distributed lock becomes an actual bottleneck, not preemptively.

**No API gateway yet.** The frontend's Vite dev server proxies by path prefix straight to
the owning service (`/auth` -> 8081, `/groups`+`/expenses` -> 8082, and so on). Fine for a
single frontend and four services; the first thing to add before a second client or a
public deployment is a real gateway or reverse proxy in front of this map.

**No role-based access control.** There's no admin role, no `@PreAuthorize`, no `ROLE_*`
anywhere in the platform. Every authorization check -- only the payer can claim, only the
receiver can acknowledge, only a rule's creator can edit or delete it -- is a plain `if`
in service code comparing the authenticated user id to a stored owner id.

**Single-instance SSE.** `/notifications/stream` fans out from an in-memory emitter
registry inside notification-service. Correct for one running instance; horizontally
scaling notification-service would need that registry backed by something shared (Redis
pub/sub, most likely) to fan out across replicas -- not built, since there's only ever been
one instance running.

---

## Contributing

1. Fork the repo
2. Create a feature branch (`git checkout -b feature/your-feature`)
3. Commit your changes (`git commit -m 'Add your feature'`)
4. Push and open a pull request

Follow the existing package structure (`api/` / `service/` / `domain/` / `repo/` per
service) and add unit tests for new logic in the money/state-machine cores -- `SplitCalculator`,
`DebtSimplifier`, `SettlementStateMachine`, and `RecurrenceCalculator` all have existing test
suites under each service's `src/test`; new logic there should too. Run everything with
`mvn test`.

---

<div align="center">

Split it evenly. Settle it for real.

</div>
