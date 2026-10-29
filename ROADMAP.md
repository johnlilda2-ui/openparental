# OpenParental Product Roadmap

This is the working roadmap for the OpenParental fork. It records what we intend to build, what source inspection currently indicates exists, and what still needs implementation or device-level verification. It is a plan, not a claim that every listed feature is already working.

## Product direction

Build a capable, reliable, Android-first parental-control and family device-management product with a child-facing app, a parent controller app, and a secure backend. Prefer explicit setup, visible operation, meaningful parent alerts, and clear child-device status over hidden surveillance.

**Scope for the current work:** Android child app and its end-to-end backend integration first. iOS is out of scope until explicitly prioritized.

## Non-negotiable boundaries

- No covert keylogging, credential/password/token collection, secret camera or microphone activation, stealth recording, or hidden screen capture.
- No bypassing Android permission prompts, security controls, or OS restrictions; no exploit/root bypass.
- Sensitive features must be visible, purpose-limited, permission-aware, and documented. A child or device owner must be able to understand what is collected and why.
- Use Android-supported APIs. Clearly label capabilities that require special access, managed-device/Device Owner enrollment, or are unavailable under normal app permissions.
- Collect the minimum data needed; protect tokens, enforce family/device ownership on the backend, audit remote actions, and provide understandable failure states.
- Preserve the upstream MIT license and notices in derivative work.

## Status definitions

- **Implemented in source:** code paths exist, but this label alone does not mean end-to-end or device testing has passed.
- **Partial:** some plumbing exists, but important behavior or integration is missing.
- **Planned:** not implemented end-to-end.
- **Platform-limited:** possible only with explicit permissions, special enrollment, OS-supported user consent, or restricted APIs.
- **Needs verification:** must be validated with CI, integration tests, or real devices before being called complete.

## Current capability inventory

| Area | Current assessment | What remains |
|---|---|---|
| Parent registration/login and token flow | Implemented in source | CI, security, refresh/revocation and error-path verification |
| Pairing code / QR and device credential | Implemented in source | Race and expiry tests; verify complete parent-to-child flow |
| Device dashboard and last-seen/presence | Implemented in source | Real-device reliability and stale/offline UX |
| Remote ping and command queue | Implemented in source | Verify push delivery, polling fallback, retries, idempotency and visible outcomes |
| Remote device lock | Partial / platform-limited | Requires active Device Admin; validate on supported Android versions and explain limitations |
| Force-unlock | Not supported as a general app capability | Do not claim arbitrary remote unlock; OS and enrollment policy apply |
| App inventory / install-remove events | Implemented in source | Validate package visibility, event delivery and reconciliation |
| App blocking, schedules and usage limits | Partial / needs verification | Test policy enforcement across launch paths, reboot, permission revocation and OEM variants |
| Usage summaries / screen-time reporting | Implemented in source, special access required | Validate aggregation accuracy, time zones, reboot and permission denial |
| Tamper / permission / went-dark alerts | Partial / needs verification | Test each detector, delivery latency, false positives and recovery |
| Location history / periodic reporting | Partial | Validate permissions, freshness, background execution and battery impact |
| Explicit fresh-location request | In progress | Complete Android build; test permissions, GPS/network providers, timeout, server report and parent UI |
| Location permission onboarding | In progress | Validate runtime prompt, coarse-only mode, denial, revocation and settings return |
| DNS content filtering | Partial | DNS-only; validate allow/block behavior and document DoH/DoT, alternate VPN and IPv6 limitations |
| Maintained content categories / blocklists | Planned | Select reputable lists, category controls, update cadence, allowlist and false-positive workflow |
| Notification and alert feed | Implemented in source | End-to-end push delivery and failure/retry verification |
| Parent-to-child policy editing | Implemented in source | Validate policy versioning, conflict handling, acknowledgement and rollback |
| Child-visible status / consent and data transparency | Partial | Add clear data-use summary, permission status, recent actions and reporting status |
| Screen-view / remote screen sharing | Planned / platform-limited | Only explicit OS MediaProjection consent, visible session indicator, secure signaling, short retention and stop control |
| Message/content archive or photo screening | Future research only | Privacy/legal review, on-device processing where possible, strict opt-in and data minimization; no covert collection |
| Device Owner / fully managed enrollment | Not currently a general capability | Research official Android Enterprise enrollment and its supported policy APIs separately |
| iOS child management | Out of current scope | Revisit only after Android baseline is stable; iOS APIs and distribution differ substantially |
| Security hardening | In progress | Production JWT secret checks, atomic pairing claim, ownership/authorization audit, transport and secret handling review |
| CI and release confidence | Needs verification | Backend tests/build, Android assemble, Flutter analyze, install/upgrade and release artifact checks |

## Delivery phases

### Phase 0 — Establish a trustworthy baseline
- [ ] Make CI run reliably on pull requests and report results.
- [ ] Run backend build/tests, Android debug assembly, and Flutter analysis.
- [ ] Fix failures based on actual logs; do not mark a check complete from source inspection alone.
- [ ] Review authentication, pairing expiry/concurrency, device ownership, command authorization, secret configuration and transport security.
- [ ] Record known platform constraints and a repeatable test protocol.

### Phase 1 — Reliable Android supervision core
- [ ] Finish and verify explicit fresh-location requests end to end.
- [ ] Make permission and special-access onboarding accurate, visible and recoverable after denial/revocation.
- [ ] Verify service startup, reboot recovery, heartbeat, FCM/polling behavior and OEM battery restrictions.
- [ ] Verify remote lock, ping, app inventory, policy sync and command result reporting.
- [ ] Make command outcomes honest: unsupported/unknown commands must not look like successful enforcement.
- [ ] Test Android API levels and at least one aggressive battery-management OEM.

### Phase 2 — Complete family controls
- [ ] Validate app blocklists, schedules, per-app daily limits and policy versioning.
- [ ] Improve usage summaries and parent dashboard freshness/empty/error states.
- [ ] Complete tamper events, offline/went-dark reconciliation, alert delivery and recovery behavior.
- [ ] Build DNS category lists, custom allow/block rules, list updates and filtering diagnostics.
- [ ] Add parent-visible explanations for each permission, control and known bypass limitation.

### Phase 3 — Product quality, privacy and operations
- [ ] Add an explicit child-device data transparency/status screen.
- [ ] Audit data retention, deletion, access control, audit logs, backups and incident handling.
- [ ] Improve accessibility, localization, onboarding recovery and low-connectivity behavior.
- [ ] Add integration tests and release smoke tests; document APK signing and safe updates.
- [ ] Measure battery, network use, command latency and location freshness on real devices.

### Phase 4 — Carefully scoped advanced features
- [ ] Evaluate official Android Enterprise / Device Owner management for supported deployments.
- [ ] Evaluate consented screen sharing using Android's system capture consent and visible indicators.
- [ ] Consider content-safety features only after privacy, legal, retention and on-device-processing reviews.
- [ ] Reassess iOS only after Android's baseline and support model are stable.

## Definition of done for a feature

A feature is not complete merely because a model, database table, permission, or command enum exists. Before calling it complete:

1. The UI explains the feature and required permission/access.
2. The client implementation actually performs the action and handles refusal, timeout, offline state and OS restrictions.
3. The backend validates authentication and ownership and records meaningful outcomes.
4. Success and failure are shown honestly in the parent app.
5. Automated checks pass and the end-to-end path is tested on a real Android device where platform behavior matters.
6. Privacy, retention and security implications are documented.

## Immediate next tasks

1. Run the Android build for the fresh-location PR and fix compiler/test failures.
2. Verify the location request from parent command to Android fix to backend storage to parent display.
3. Diagnose why PR checks are not appearing; do not merge until required checks have actually run.
4. Re-audit command result semantics, especially unsupported command types.
5. Update this roadmap as features are tested; keep status labels evidence-based.
