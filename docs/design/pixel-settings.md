# Pixel branding and Settings appearance

2026-11-15: user supplied pixel-icons-2026.zip and requested names 手环 / 洗衣机 / 快递, plus Google Settings inspired appearance.

## Reference implementations
- AOSP SettingsLib/Spa: https://github.com/aosp-mirror/platform_frameworks_base/tree/main/packages/SettingsLib/Spa
- Android Now in Android settings: https://github.com/android/nowinandroid/tree/main/feature/settings

Use existing Material/AndroidX components, large page titles, semantic surface colors, 16–24dp spacing and grouped preference rows. Follow system light/dark mode; preserve dynamic colors where already supported. Do not add a web runtime or migrate application architecture for appearance alone.

## Application scope
- Mainline app name 手环. Preserve upstream and license attribution.
- Replace launcher adaptive, round, density and monochrome layers.
- Settings use an accessible large heading and tonal, ripple-enabled preference rows; preserve native widgets, navigation and search.
- Plain, screen, dialog, text, dropdown, checkbox and compat-switch preferences share the same row layout through the existing AndroidX preference theme overlay. Each specialized preference retains its native widget and dialog style. Seekbar and other custom layouts retain their required controls.
- Use start/end and top/bottom padding and margins so the supported API 23 layout keeps its spacing.

## Runtime icon reuse and cleanup
- Mainline `splash_icon` aliases the new launcher foreground on API 24+, so the splash screen and welcome page use the approved band artwork from one vector source. API 23 reuses the density-specific launcher bitmap because its platform vector parser fails to load the gradient foreground. Both paths are covered by resource-loading regression tests. Flavor-specific upstream art and license attribution remain available to their existing variants.
- The drawer header and sleep-alarm widget preview resolve the flavor's `mipmap/ic_launcher`. The nine duplicate `drawable-*/ic_launcher.png` files in main/nightly have no remaining references and were removed.
- The main round launcher icon aliases `mipmap/ic_launcher`, avoiding five identical bitmap copies and a duplicate adaptive XML while resolving density/API variants normally. Other flavors keep their own qualified launcher resources.
- Release APKs use the existing Mainline Debug signing configuration, matching the previous personal releases. Keep one current APK in the mainline debug output directory.

## Shared color roles (2026-11-15 correction)
- The first preview mixed Gadgetbridge static neutral surfaces with Material dynamic colors in the other apps. Default/new Gadgetbridge theme now uses dynamic colors, including onboarding and every fallback read. Existing explicit theme choices are retained.
- Shared color roles: page background=surface; preference/machine groups=surfaceContainerLow; main text=onSurface; summary/icons=onSurfaceVariant; section/action accents=primary. Light/dark follow the system when using dynamic colors.
- Material Components Color Theming reference: https://github.com/material-components/material-components-android/blob/master/docs/theming/Color.md
