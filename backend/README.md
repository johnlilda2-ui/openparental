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


## Dependency security verification

The backend CI and dependency-maintenance workflow use Node.js 24.15.0 and rebuild the npm lockfile from the declared dependency graph before doing a clean `npm ci`. Verify production dependencies with `npm run audit:production` and all dependencies with `npm audit`; both commands must be evaluated against the committed lockfile.

The current Jest toolchain uses an OpenParental-maintained local compatibility fork at `vendor/deepmerge`, configured through the npm `deepmerge` override. It is based on the upstream 4.3.1 implementation, preserves the MIT license notices, and rejects prototype-sensitive keys before copying properties. This local patch is not an upstream release; keep its regression tests in `src/security/deepmerge-hardening.spec.ts` and re-evaluate it when upstream publishes a fixed version. Never replace it with an older Jest version solely to make the advisory disappear.
