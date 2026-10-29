# OpenParental backend

> **Project roadmap:** see [../ROADMAP.md](../ROADMAP.md) for end-to-end feature status and delivery phases. The directory labels below are historical orientation, not a reliable indication that a module is unfinished or fully verified.


Node + TypeScript (NestJS) backend: auth, pairing, command spine, policy, events,
tamper, heartbeat, alerts. Postgres (Prisma) + Redis + FCM.

## Run locally

```bash
cp .env.example .env
docker compose up -d            # Postgres + Redis
npm install
npm run prisma:generate         # generate the Prisma client (required before tests)
npm run prisma:migrate          # create the schema (first run)
npm run start:dev               # http://localhost:3000/health
```

## Test

```bash
npm run prisma:generate         # tests import the generated client
npm test                        # unit tests
npm run test:cov                # with coverage
npm run test:e2e                # end-to-end (needs Postgres + Redis up)
```

## Layout

```
src/
  config/        env configuration
  prisma/        PrismaService (global)
  redis/         RedisService (global) — presence / heartbeat keys
  common/        guards (JwtAuthGuard, DeviceAuthGuard), decorators, hash utils, types
  auth/          register / login / refresh / logout + TokenService     [done]
  pairing/       QR/code issue + device claim → device token            [implemented; tests/edge cases to verify]
  devices/       device registry, status, info                          [implemented; integration to verify]
  policies/      rules CRUD, versioned                                  [implemented; enforcement to verify]
  commands/      command spine (enqueue → push/poll → ack/result)        [implemented; delivery and outcomes to verify]
  events/        batched event ingestion                                [implemented; integration to verify]
  heartbeats/    presence + "went dark" reconciler                      [implemented; reliability to verify]
  tamper/        tamper events + alert fan-out                          [implemented; detector coverage to verify]
  alerts/        alert feed + FCM to parent                             [implemented; push delivery to verify]
  locations/     location ingest + query                               [implemented; fresh-request flow in progress]
  fcm/           push abstraction (firebase-admin)                      [implemented; configuration/delivery to verify]
  realtime/      WebSocket gateway (presence, screen-view signaling)    [partial; screen-view feature not end-to-end]
```

## Token model

- **access** (short TTL) — parent app, `Authorization: Bearer`. `JwtAuthGuard`.
- **refresh** (long TTL, rotated, sha256-hashed in DB) — parent app.
- **device** (very long TTL) — managed app, minted at pairing claim. `DeviceAuthGuard`.
