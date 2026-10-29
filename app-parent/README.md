# OpenParental parent (controller) app

> **Project roadmap:** see [../ROADMAP.md](../ROADMAP.md) for the cross-project plan and evidence-based feature status. The parent UI and backend must be tested together; a control in the UI is not complete until the child performs it and the result is reflected accurately.


Flutter. Login/register, pair a device (shows the code to type on the child),
device dashboard (online/battery/last-seen), remote **Lock** / **Ping**, a
blocked-apps **policy editor** that pushes `SET_POLICY` to every device, and an
**alert feed** (tamper, went-dark, unblock requests). Current roadmap priority is
to verify command outcomes and complete the parent-to-child fresh-location
request flow; see [../ROADMAP.md](../ROADMAP.md).

## Run

```bash
flutter pub get
flutter run
```

On the login screen set **Backend URL** to your backend
(`http://10.0.2.2:3000` reaches localhost from the Android emulator).

## Structure

```
lib/
  api/        ApiClient (Dio, auto token-refresh on 401) + models
  state/      Riverpod providers (baseUrl, api, auth)
  screens/    login, home (Devices + Alerts tabs), pairing, device detail
```

Distribution: ships to **Google Play as an AAB**. (The managed *child* app does
not — it is a sideloaded APK.)

Note: built and reviewed by inspection; this environment has no Flutter SDK, so
it has not been `flutter analyze`'d here.
