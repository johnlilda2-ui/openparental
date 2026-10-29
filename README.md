<div align="center">

# 🛡️ OpenParental

**Consent-first, open-source parental controls for Android.**

*Bypass-evident, not bypass-proof — the kid always knows it's there, and the parent always knows when it's off.*

[![CI](https://github.com/Suhaib3100/openparental/actions/workflows/ci.yml/badge.svg)](https://github.com/Suhaib3100/openparental/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)
[![Kotlin](https://img.shields.io/badge/child%20app-Kotlin-7F52FF?logo=kotlin&logoColor=white)](android-managed)
[![Flutter](https://img.shields.io/badge/parent%20app-Flutter-02569B?logo=flutter&logoColor=white)](app-parent)
[![NestJS](https://img.shields.io/badge/backend-NestJS-E0234E?logo=nestjs&logoColor=white)](backend)

[Why OpenParental?](#-why-openparental) •
[Features](#-features) •
[Architecture](#%EF%B8%8F-architecture) •
[Quick start](#-quick-start) •
[Roadmap](#%EF%B8%8F-roadmap) •
[Contributing](#-contributing)

</div>

---

## 💡 Why OpenParental?

Most parental-control apps compete on how well they *hide*: stealth modes, covert
screenshots, secret microphones. OpenParental makes the opposite bet — **supervision a
teenager can verify beats spyware they'll route around.**

- **Overt and consent-based.** The child app has a UI, says what it does, and
  shows what it reports. Nothing runs in secret.
- **Bypass-evident by design.** Without Device Owner you *can't* make an app
  un-killable — so OpenParental doesn't pretend to. Instead, every bypass (disabling
  accessibility, revoking a permission, force-stopping the app, the device going
  dark) becomes a **parent alert within minutes**. Turning it off is always
  possible — and never silent.
- **Hard privacy lines.** No covert audio or camera. No ambient recording. No
  covert screen capture. No server-side storage of minors' photos. No Device
  Owner / no device wipe. These are design constraints, not roadmap items.

## ✨ Features

| | Feature | How it works |
|---|---|---|
| 🔗 | **Pairing** | Parent generates a short code → child app claims it → device gets its own scoped token |
| 🔒 | **Remote lock & ping** | Command spine: enqueue → push/poll → device ack → result, fully audited |
| 🚫 | **App blocking** | Versioned policy engine — blocklists, per-app daily limits, schedules (e.g. no games after 9pm) |
| 🌐 | **DNS content filter** | DNS-only `VpnService` blocks filtered domains — no traffic inspection, no MITM |
| 🧭 | **Guided onboarding** | Child-app setup walks through every grant — battery, accessibility, usage, admin, filter, OEM auto-start |
| ⏱️ | **Screen-time limits** | Accessibility service bounces blocked / over-limit / out-of-schedule apps |
| 🚨 | **Tamper evidence** | Disabled accessibility, revoked permissions, force-stop, "went dark" — all surfaced as alerts |
| 🌙 | **Went-dark reconciler** | Presence tracked in Redis, reconciled from Postgres — a Redis restart never causes a false alarm |
| 🆕 | **New-app detection** | Parent is alerted when a new app is installed |
| 📍 | **Location** | Periodic location reporting to the parent dashboard |
| 📊 | **Usage visibility** | Daily per-app usage reported to the backend |
| 🔔 | **Alert feed** | Tamper, went-dark, new-app and unblock-request alerts in the parent app |

## 🏗️ Architecture

```mermaid
flowchart LR
    P["📱 Parent app<br/>(Flutter)"] -- "REST + JWT" --> B
    subgraph B["⚙️ Backend (NestJS)"]
        direction TB
        API[API] --- PG[(Postgres<br/>Prisma)]
        API --- R[(Redis<br/>presence)]
        API --- F[FCM push]
    end
    B -- "commands · policies" --> C["🧒 Child app<br/>(Kotlin, sideloaded)"]
    C -- "heartbeat · events · tamper<br/>location · usage" --> B
```

Three components, one monorepo:

```
openparental/
├── backend/           NestJS + Postgres (Prisma) + Redis + FCM
│   ├── src/           auth · pairing · devices · policies · commands ·
│   │                  events · heartbeats · tamper · alerts · locations
│   └── test/          68 unit tests + e2e
├── android-managed/   Kotlin child app (sideloaded APK)
│   └── app/           FGS supervisor · accessibility enforcement ·
│                      tamper watchers · heartbeat · visibility reporters
├── app-parent/        Flutter parent app (Play-store track)
│   └── lib/           login · pairing · dashboard · lock/ping ·
│                      policy editor · alert feed
└── .github/           CI: backend build+test, Android assemble, Flutter analyze
```

**The flow, end-to-end:** parent registers → creates a pairing code → child app
claims it → parent sees the device, sends **Lock**/**Ping**, edits a blocked-apps
policy that pushes to the device → the child app enforces it and reports
heartbeat, tamper, new apps, location and usage → parent gets alerts.

## 🚀 Quick start

**1. Backend** — Node 20+, Docker

```bash
cd backend
cp .env.example .env
docker compose up -d              # Postgres 16 + Redis 7
npm install
npm run prisma:generate && npm run prisma:migrate
npm run start:dev                 # → http://localhost:3000/health
```

**2. Child app** — JDK 17, Android Studio

Open `android-managed/` in Android Studio and run it on a device. In the app, set
the backend URL (`http://10.0.2.2:3000` from an emulator) and enter a pairing code.

**3. Parent app** — Flutter 3.22+

```bash
cd app-parent
flutter pub get
flutter run                       # set the backend URL on the login screen
```

Register, create a pairing code, type it into the child app — the device appears
on your dashboard.

## 🗺️ Roadmap

The roadmap is maintained in **[ROADMAP.md](ROADMAP.md)**. It tracks current source status, platform limitations, delivery phases, and the evidence required before a feature is called complete.

**Current priority: Android first.** Finish the explicit fresh-location request end to end, get pull-request CI running, verify command outcomes and security, then harden core supervision and complete app controls, alerts, and DNS filtering. iOS is out of scope until Android is stable and explicitly reprioritized.

Status labels are evidence-based: code existing does not automatically mean a feature is end-to-end tested or CI-green. Advanced features such as screen sharing remain planned and must use explicit Android system consent and visible session controls.

## 🧰 Tech stack

| Component | Stack |
|---|---|
| Backend | Node 20, NestJS, TypeScript, Prisma + Postgres 16, Redis 7, firebase-admin |
| Child app | Kotlin 2.0, AGP 8.6, Gradle 8.9, SDK 35, JDK 17 |
| Parent app | Flutter 3.22+, Dart |
| CI | GitHub Actions — backend build + 68 tests, `assembleDebug`, `flutter analyze` |

## 🤝 Contributing

Contributions are welcome — especially OEM survival reports (does the FGS anchor
survive overnight on your Xiaomi/Samsung/OnePlus?), enforcement edge cases, and
parent-app UX. See [CONTRIBUTING.md](CONTRIBUTING.md) to get started.

One rule is non-negotiable: PRs adding covert surveillance (hidden recording,
stealth modes, secret capture) will be declined — that's the whole point of the
project. See [why OpenParental?](#-why-openparental)

## 🔐 Security

Found a vulnerability? Please report it privately via
[GitHub Security Advisories](https://github.com/Suhaib3100/openparental/security/advisories/new) —
see [SECURITY.md](SECURITY.md).

## 📄 License

[MIT](LICENSE) © 2026 Suhaib

---

<div align="center">
<sub>If OpenParental's approach resonates with you, a ⭐ helps other parents find it.</sub>
</div>
