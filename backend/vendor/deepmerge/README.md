## OpenParental-maintained deepmerge compatibility patch

This is a private, local compatibility fork based on deepmerge 4.3.1 (MIT). It is not an upstream deepmerge release.

It unconditionally filters the prototype-sensitive keys __proto__, constructor, and prototype when copying object properties. This addresses the vulnerable null-prototype target path described in GHSA-gcqx-w48m-w2h4 / CVE-2026-93753 while upstream has no patched release.

The patch is exercised by the backend test at src/security/deepmerge-hardening.spec.ts. This package is an override for the backend's development/test toolchain only. Re-evaluate it and return to the official package when an upstream patched release is available.
