# TaroGram

> A Telegram for Android fork optimised for the **Realme GT Neo 5** (RMX3706,
> codename _taro / senna_), based on [Cherrygram](https://github.com/arsLan4k1390/Cherrygram).

This fork focuses on:

- **Full camera access** on the OPLUS / Qualcomm camera stack — ultrawide,
  telephoto, front-aux instead of getting silently downgraded to the main
  sensor like stock Telegram does.
- **Audio-synced video recording** — no more "soundtrack falling off" on
  long Camera2 captures.
- **Hardware video stabilisation (EISv3 + OIS)** wired into instant video
  messages.
- **Smooth 60 fps front camera** preview.
- **Realme UI 7.0** system optimisations (Doze allowlist, FGS types,
  ColorOS power profile).
- **SmartProxy** — built-in MTProto WS proxy manager with auto-recovery,
  port hopping, CDN toggling (based on [amurcanov/tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android)).

For the **system-level camera HAL whitelist**, see the companion KSU module
at [etern1ty-crypto/SennaCamUnlock](https://github.com/etern1ty-crypto/SennaCamUnlock).

## Status

| Phase | Description | Status |
|---|---|---|
| 1 | Camera2 ISP enhancements (Qualcomm / OPLUS family) | In review |
| 2 | Multi-camera switch UI (wide / tele / front toggle) | Planned |
| 3 | Audio-sync MediaCodec pipeline for long recordings | Planned |
| 4 | KSU module — see [SennaCamUnlock](https://github.com/etern1ty-crypto/SennaCamUnlock) | v0.1.0 shipped |
| 5 | Realme UI 7.0 system optimisations | Planned |
| 6 | F-Droid metadata + reproducible builds | Planned |
| 7 | SmartProxy manager (WS-proxy + auto-recovery + AmneziaWG opt-in) | Planned |

## Downloads

Built artifacts:
- **GitHub Actions** → [latest builds](https://github.com/etern1ty-crypto/TaroGram/actions)
  (each run uploads `TaroGram-<sha>-<type>.apk`)
- **Releases** → [tagged builds](https://github.com/etern1ty-crypto/TaroGram/releases)

## Upstream

This is an unofficial fork of:

- [Cherrygram](https://github.com/arsLan4k1390/Cherrygram) — the immediate
  base (1.3k stars, actively maintained as of 2026-04)
- [Telegram for Android](https://github.com/DrKLO/Telegram) — the
  upstream-of-upstream

## Build

The default working branch is `tarogram-base` (corresponds to Cherrygram's
`main_Reproducible_Builds` branch — the buildable one). The `main` branch
on GitHub exists but is intentionally empty per repo policy.

### Local build

1. `git clone https://github.com/etern1ty-crypto/TaroGram.git -b tarogram-base`
2. Generate a signing key as `Your_Key.jks` with password `Your_Password`,
   alias `Your_Alias` — or update the paths in
   `TMessagesProj_AppStandalone/build.gradle`.
3. Fill in [Extra.kt](TMessagesProj/src/main/java/uz/unnarsx/cherrygram/Extra.kt)
   with your Telegram `APP_ID` / `APP_HASH` from <https://my.telegram.org/apps>.
4. `./gradlew :TMessagesProj_AppStandalone:assembleAfatDebug`

### CI

Every push to `tarogram-base` or any `devin/**` / `feature/**` branch
triggers `.github/workflows/build-tarogram.yml` which builds the
Standalone APK on a GitHub-hosted runner and uploads it as an artifact.
Repo secrets `TELEGRAM_APP_ID` and `TELEGRAM_APP_HASH` are picked up if
present.

## License

GPL-2.0-or-later — same as upstream Telegram for Android and Cherrygram.

## Thanks

- [arsLan4k1390](https://github.com/arsLan4k1390) for Cherrygram
- [Quantom2](https://github.com/Quantom2) for the GT Neo 5 device tree,
  kernels, and KSU module template
- [amurcanov](https://github.com/amurcanov) for the WS-proxy Android
  wrapper (base for SmartProxy Phase 7)
- [DrKLO](https://github.com/DrKLO) and the Telegram Android team
- The [Flowseal](https://github.com/Flowseal/tg-ws-proxy) project for the
  Go-based MTProto WS proxy
- [Catogram](https://github.com/Catogram/Catogram),
  [Nekogram](https://gitlab.com/Nekogram/Nekogram),
  [exteraGram](https://github.com/exteraSquad/exteraGram),
  [OwlGram](https://github.com/OwlGramDev/OwlGram),
  [Telegraher](https://github.com/nikitasius/Telegraher),
  [Telegram Monet](https://github.com/c3r5b8/Telegram-Monet)
