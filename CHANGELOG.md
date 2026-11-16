# Changelog

All notable changes to AxisWright will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.9.3-beta] - 2026-11-16

### Added
- **Open Source & Distribution:**
  - Licensed under the **GNU General Public License v3.0 or later (`GPL-3.0-or-later`)** with in-app third-party license viewer (`Licenses …`) and source code link (`https://github.com/KevBo8327/AxisWright`).
  - Added `play` and `foss` build flavors (`foss` targets F-Droid and GitHub Releases; `play` complies with Google Play policies).
  - Configured per-ABI APK splits (`arm64-v8a` and `x86_64`) for release APK builds (`AxisWright-0.9.3-beta-foss-<abi>.apk`).
- **Full Localization (English & German):**
  - Complete bilingual UI (`values/` English default, `values-de/` German) including core solver/geometry/model messages (`Msg` keys), dialogs, imprint, license pages, and accessibility labels.
- **2D Sketching:**
  - **Transform tool:** Move, rotate, scale, and copy sketch selections with automatic constraint handling.
  - **Text tool:** Text as a sketch element with closed outlines for engraving and embossed 3D features, including transform support.
- **3D Modeling:**
  - **Extrude extensions:** Added `Intersect` (`BodyOp.INTERSECT`), **Two Sides** with independent lengths, and **Up to Face** extrusion modes.
  - **Hole types & metric standards:** Added counterbore and countersink holes plus metric standard sizes **M3–M10**.
  - **Section View:** Dynamic 3D clipping plane to inspect internal geometry and cavities, with picking exclusion behind the cut plane.
  - **Formula fields:** Parameter formula expressions supported for sketch/3D pattern counts and spacing, work plane offsets/angles, and hole depths (including decimal comma support).

### Changed
- **Renamed ParamWorker to AxisWright:**
  - Updated app name, branding, launcher icons, splash screen (`AXISWRIGHT`), and documentation to **AxisWright**.
  - Updated application ID to `io.github.kevbo8327.axiswright` (installs side-by-side with older `ParamWorker` builds) and project export format to `.axiswright.json` (`axiswright-model`).
  - Preserved backward-compatible import for legacy `.paramworker.json`, `.partwright.json`, `androidcad-model`, and `.scad` files.
- **FOSS Donations UI:** Hid the "Support development…" menu entry and dialog in `foss` builds while donation URLs are not yet configured.

### Fixed
- **Native & JNI Stability:** Fixed `BrepShape` lifecycle management, added JNI bounds/null checks, added STEP export mutex, and enforced global lock ordering in `HandleGuard.useWith` to prevent deadlocks and native crashes.
- **UI Responsiveness & Persistence:** Cached `statusText` per `SolveResult` in `SketchEditor`, released OpenGL shaders properly and cached `SceneFrame` during tap picking, coalesced slider undo steps, and moved JSON serialization/flush off the main thread when saving on exit.
- **Sketch Solver & Region Detection:** Improved `TangentLineRound` smoothness, added analytic `AngleDim` Jacobian and QR nullspace handling, fixed exact winding-number region detection for thin concentric rings, and normalized decimal commas in formula evaluation.

### Removed
- Removed all "Pro" feature gates, Google Play Billing (`billing-ktx`), and the `INTERNET` permission—every feature (including STEP export, 3MF export, and the parameter table) is free and 100% offline.

## [0.9.2-beta] - 2026-10-20

- Prior beta release under the working name ParamWorker (feature history, 2D constraint solver, B-rep modeling with Open CASCADE, STEP/STL/3MF export, OpenSCAD script editor).
