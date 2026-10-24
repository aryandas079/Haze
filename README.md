<div align="center">

  <img src="assets/logo.png" alt="Haze Browser Logo" width="128" height="128" />

  # Haze Browser

  ### The Ultra-Fast, Minimalist and Privacy-Hardened Chromium Web Browser for Android

  <p align="center">
    <b>Powered by a custom-compiled dual-ABI Native Chromium Engine (Blink + V8), C++17 native threat filtering, system Notification Media Controller, hybrid local search indexing, and hardware-grade credential encryption.</b>
  </p>

  [![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://www.android.com/)
  [![Package](https://img.shields.io/badge/Package-com.aryandas.haze-blue?style=for-the-badge)](https://github.com/aryandas079/Haze)
  [![Chromium Core](https://img.shields.io/badge/Engine-Native%20Chromium%20(Blink%20%2B%20V8)-4285F4?style=for-the-badge&logo=googlechrome&logoColor=white)](https://www.chromium.org/)
  [![ABI Support](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20armeabi--v7a-00897B?style=for-the-badge)](https://developer.android.com/ndk/guides/abis)
  [![Min SDK](https://img.shields.io/badge/Min%20SDK-24%20(Android%207.0)-blue?style=for-the-badge)](https://developer.android.com/)
  [![Target SDK](https://img.shields.io/badge/Target%20SDK-37-blueviolet?style=for-the-badge)](https://developer.android.com/)
  [![Language](https://img.shields.io/badge/Kotlin-1.9+-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
  [![Native Engine](https://img.shields.io/badge/C%2B%2B-17-00599C?style=for-the-badge&logo=c%2B%2B&logoColor=white)](https://isocpp.org/)
  [![Build System](https://img.shields.io/badge/CMake-3.22.1-064F8C?style=for-the-badge&logo=cmake&logoColor=white)](https://cmake.org/)
  [![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=for-the-badge)](LICENSE)
  [![Open Source](https://img.shields.io/badge/Open%20Source-Yes-brightgreen?style=for-the-badge)](LICENSE)

</div>

---

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
  - [Custom-Compiled Dual-ABI Native Chromium Engine](#custom-compiled-dual-abi-native-chromium-engine)
  - [Notification Center & Lockscreen Media Controller](#notification-center--lockscreen-media-controller)
  - [Universal Website Compatibility & Streaming Acceleration](#universal-website-compatibility--streaming-acceleration)
  - [Haze Privacy Shield (Native C++17 Core)](#haze-privacy-shield-native-c17-core)
  - [Anti-Tracking, Anti-Fingerprinting & Link Sanitization](#anti-tracking-anti-fingerprinting--link-sanitization)
  - [Hybrid Local Indexing & Live Autocomplete Omnibox](#hybrid-local-indexing--live-autocomplete-omnibox)
  - [Redesigned First-Run Onboarding & Bottom-Anchored UI](#redesigned-first-run-onboarding--bottom-anchored-ui)
  - [Floating Mini-Player and Picture-in-Picture](#floating-mini-player-and-picture-in-picture)
  - [Hardware-Backed Password Vault](#hardware-backed-password-vault)
  - [Resumable Multi-Threaded Download Engine](#resumable-multi-threaded-download-engine)
  - [Google Account and Cloud Backup Sync](#google-account-and-cloud-backup-sync)
  - [Ambient Themes and Custom Personalization](#ambient-themes-and-custom-personalization)
  - [Power Utilities Suite](#power-utilities-suite)
- [Deep-Dive Technical Architecture](#deep-dive-technical-architecture)
  - [JNI Zero Dispatch Pipeline](#jni-zero-dispatch-pipeline)
  - [Chromium Content Layer vs. WebView](#chromium-content-layer-vs-webview)
  - [High-Throughput Native C++ Matching Pipeline](#high-throughput-native-c-matching-pipeline)
- [Universal Website Compatibility Suite (Top 50 Domains)](#universal-website-compatibility-suite-top-50-domains)
- [Project Directory Structure](#project-directory-structure)
- [Tech Stack and Dependencies](#tech-stack-and-dependencies)
- [Getting Started and Build Instructions](#getting-started-and-build-instructions)
  - [Prerequisites](#prerequisites)
  - [Automated Native Binary Reassembly Pipeline](#automated-native-binary-reassembly-pipeline)
  - [Clone and Build](#clone-and-build)
  - [Running Tests](#running-tests)
- [Privacy and Security Blueprint](#privacy-and-security-blueprint)
- [Roadmap](#roadmap)
- [Legal, Governance & Trust Policies](#legal-governance--trust-policies)
  - [Privacy Policy & Client-Side Data Architecture](#privacy-policy--client-side-data-architecture)
  - [Cookie, Cache & Web Storage Governance](#cookie-cache--web-storage-governance)
  - [End-User Terms of Service & Acceptable Use Policy](#end-user-terms-of-service--acceptable-use-policy)
  - [Security Architecture & Vulnerability Disclosure Program](#security-architecture--vulnerability-disclosure-program)
  - [Regulatory Compliance & Data Subject Rights (GDPR / CCPA)](#regulatory-compliance--data-subject-rights-gdpr--ccpa)
  - [Law Enforcement Guidelines & Transparency](#law-enforcement-guidelines--transparency)
  - [Intellectual Property, Third-Party Notices & DMCA Policy](#intellectual-property-third-party-notices--dmca-policy)
  - [Accessibility & Ergonomic Inclusion Statement](#accessibility--ergonomic-inclusion-statement)
  - [Legal Notice & Imprint](#legal-notice--imprint)
- [Contributing and Community](#contributing-and-community)
- [Author and Contact](#author-and-contact)
- [License](#license)

---

## Overview

**Haze** (`com.aryandas.haze`) is a standalone, high-performance Android web browser engineered from the ground up with its own embedded **Native Chromium Engine (`libcontent_shell_content_view.so`)** alongside a dedicated C++17 network and content-filtering core (`libhazeshield.so`).

Unlike lightweight wrapper browsers that rely strictly on the host device's system WebView, Haze ships with a self-contained, custom-compiled Chromium Content runtime supporting both **64-bit (`arm64-v8a`)** and **32-bit (`armeabi-v7a`)** ARM architectures:
- **True Standalone Chromium Runtime**: Ships with compiled Blink rendering, V8 JavaScript snapshots (`snapshot_blob_64.bin` / `snapshot_blob_32.bin`), ICU internationalization data (`icudtl.dat`), Chromium resource packs (`content_shell.pak`), and complete `JNI_Zero` Java-to-C++ runtime bindings (`chromium_engine.jar`).
- **Radical Privacy**: Zero first-party tracking telemetry, zero analytics harvesting, C++17 native ad/tracker interception, and hardware-grade credential protection.
- **System Notification Media Center**: Full foreground Android MediaSession controller integrated directly into the notification shade and lockscreen, providing native playback controls, scrubber tracking, and album artwork across YouTube Music, Spotify, Apple Music, and web streams.
- **Universal Compatibility & 60 FPS Fluidity**: Direct RenderThread hardware composition, native HTTP/2 & HTTP/3 multiplexing, and zero-polling event-driven script architectures delivering stutter-free browsing across complex modern single-page applications.
- **One-Handed Ergonomics**: Chrome-inspired First-Run Experience (FRE), a clean top-toolbar-free viewport with only a contextual profile badge on the Home screen, and all search, tab, voice, and menu actions anchored in a flat bottom bar.

---

## Key Features

### Custom-Compiled Dual-ABI Native Chromium Engine
Haze integrates a full Chromium Content runtime behind a clean, modular engine abstraction (`IBrowserEngine`, `IBrowserTab`, `INavigationController`, `IEngineObserver`):

- **Dual-Architecture Native Binaries**:
  - **64-bit (`arm64-v8a`)** and **32-bit (`armeabi-v7a`)** builds of `libcontent_shell_content_view.so`, packaged into both per-ABI split APKs and a single Universal Release APK (`app-universal-release.apk`) that automatically installs the optimal native binary for the target device.
- **Complete `JNI_Zero` Runtime Bridge**:
  - Bundles 394 compiled Chromium Java archives (15,700+ classes) in `chromium_engine.jar` along with `org.jni_zero.*` runtime dispatchers and runtime resource ID synchronization (`gen.base_module.R` <-> `com.example.haze.R`).
- **Native Tab & Navigation Orchestration (`NativeChromiumTab` / `NativeNavigationController`)**:
  - Multi-tab `WebContents` lifecycle management over `ShellManager` and `ActivityWindowAndroid`.
  - Bidirectional JavaScript bridge (`GinJavaBridgeDispatcherHost` / `@JavascriptInterface`) powering `window.Android` interactions on the New Tab page and web documents.
  - Unrestricted JavaScript evaluation across `https://`, `http://`, and Base64-encoded `data:` origins via `RenderFrameHost` execution bindings.
  - Real-time `WebContentsObserver` callbacks for navigation state, load progress, page titles, error recovery, and canvas-backed tab thumbnail capture.

---

### Notification Center & Lockscreen Media Controller
Haze brings desktop- and dedicated-app-grade media playback controls right to Android's Notification Shade and Lockscreen via `HazeMediaNotificationService`:

- **Native MediaSession Integration**:
  - Full Android `MediaSessionCompat` lifecycle synchronization matching native music apps (Spotify, YouTube Music, Apple Music).
  - Automatically captures and broadcasts live track metadata: Track Title, Artist / Channel, Album, and high-resolution Album Artwork.
- **Notification Shade Playback Controls**:
  - Compact and expanded notification styles (`NotificationCompat.MediaStyle`) featuring Previous Track, Play/Pause toggle, Next Track, and Dismiss controls.
  - Interactive scrub bar and playback position reporting.
- **State Deduplication & Audio Focus Guard**:
  - Automatic state-change deduplication prevents AVRCP churn and audio focus storms, eliminating audio stuttering and main thread hitching.
  - Foreground service lifecycle management compliant with Android 8.0+ through Android 14+ requirements.

---

### Universal Website Compatibility & Streaming Acceleration
Engineered and verified against the top 50 global web destinations (including search engines, streaming giants, social media networks, AI assistants, and enterprise SaaS):

- **Zero-Lag Audio & Video Streaming**:
  - Native RFC 7234 HTTP caching and byte-range streaming (`Range: bytes=...`) delegated directly to Chromium's native C++ network stack, bypassing slow synchronous Java disk layers.
  - Uncompromised support for HLS (`.m3u8`, `.ts`), DASH (`.mpd`, `.m4s`), MP4, WebM, and protected media playback.
- **Event-Driven Media Supervisor**:
  - Replaced high-frequency interval polling (`setInterval`) and repetitive DOM scans with purely event-driven HTML5 media observers (`play`, `playing`, `loadedmetadata`, `loadstart`, `yt-navigate-finish`).
  - RequestAnimationFrame-throttled ad killer and video lifecycle handlers eliminate CPU micro-stutters during 60 FPS video and audio playback.
- **Dynamic Viewport & Client Hints Adaptation**:
  - Automatically supplies correct client hints (`navigator.userAgentData`, `navigator.platform = 'Win32'`) and viewport definitions to ensure proper responsive or desktop layouts on complex modern SPAs (YouTube, Spotify, Discord, Instagram, Canva, Microsoft 365).
- **Direct RenderThread Compositing**:
  - Layer type set to `View.LAYER_TYPE_NONE` with disabled `offscreenPreRaster` prevents redundant intermediate offscreen texture allocations, guaranteeing buttery smooth touch response and scrolling.

---

### Haze Privacy Shield (Native C++17 Core)
The Haze Privacy Shield is a multi-tier defense system designed to block web pollution before network packets consume memory or cellular data.

- **Native C++ Interception**: Utilizes `HazeShieldCore`, `DomainMatcher`, and `RuleMatcher` written in modern C++17 (`libhazeshield.so`) compiled with `-O3 -DNDEBUG -fvisibility=hidden` and `std::shared_mutex` for lock-free read concurrency.
- **Sub-Millisecond Evaluation**: An in-memory bounded LRU decision cache (1,024 entries) delivers O(1) decision times on repeated resource lookups.
- **Declarative CSS Cosmetic Filtering**:
  - Banners, sponsored sections, and ad wrappers are instantly collapsed using declarative CSS rules (`display: none !important`) processed directly inside Blink's C++ styling engine with zero JavaScript overhead.
- **Comprehensive Threat Filtering**:
  - Blocks intrusive advertising networks, tracking beacons, and deceptive notification prompts.
  - Neutralizes third-party behavioral trackers and telemetry scripts (Google Analytics, Facebook Pixel, Hotjar, Clarity).
  - Intercepts phishing URLs and malicious domains with custom security warning pages.
- **Smarter YouTube Shielding**: Intelligently suppresses YouTube ad injections and telemetry pings without degrading legitimate media stream segments (`googlevideo.com`).
- **Debounced Live Shield Dashboard**: Transparent metrics showing total blocked threats, cumulative bandwidth saved, and per-domain whitelisting controls with debounced UI updates that never stall page loading.

```text
                      ┌────────────────────────────────┐
                      │  Web Resource Network Request  │
                      └───────────────┬────────────────┘
                                      │
                         [Is Request Whitelisted?]
                                ├── Yes ──> [ Allow Network Traffic ]
                                └── No
                                      │
                         [In-Memory LRU Decision Cache]
                                ├── Hit ──> [ Return Cached Decision ]
                                └── Miss
                                      │
                         [HazeNativeBridge (JNI)]
                                      │
                         [C++17 HazeShieldCore]
                   ┌──────────────────┼──────────────────┐
                   ▼                  ▼                  ▼
             DomainMatcher      RuleMatcher      Harmful Navigation
             (Fast HashSet)   (Pattern Wildcard)  (Security Gate)
                   └──────────────────┬──────────────────┘
                                      │
                      ┌───────────────┴────────────────┐
                      │    Block Verdict & Intercept   │
                      │  (Cosmetic Hide / Warning UI)  │
                      └────────────────────────────────┘
```

---

### Anti-Tracking, Anti-Fingerprinting & Link Sanitization
Advanced privacy engine (`HazeAntiTrackingEngine`) actively neutralizing silent identity profiling:

- **Fingerprint Defense**:
  - Canvas & WebGL probe detection and masking without corrupting standard image processing, CAPTCHAs, or QR decoders.
  - Audio and font metric measurement probe normalization.
  - Device metric spoofing (`hardwareConcurrency`, `deviceMemory`, `maxTouchPoints`).
- **Bounce Tracking Protection**:
  - Detects and unravels redirect trampoline URLs, bypassing tracking redirectors directly to destination pages.
- **Link Tracking Sanitization**:
  - Strips attribution query parameters (`utm_*`, `fbclid`, `gclid`, `msclkid`, `twclid`, `ttclid`, `mc_eid`, etc.) on navigation and hyperlink taps.
- **WebRTC IP Leak Defense**:
  - Sanitizes local LAN IPv4 candidates from SDP offers while preserving STUN and TURN connectivity for seamless Google Meet, Zoom, and Discord calls.

---

### Hybrid Local Indexing & Live Autocomplete Omnibox
Haze features an instant, low-latency search and indexing pipeline built directly into the bottom omnibox:

- **On-Device Local Index (`buildLocalIndexedSuggestions`)**:
  - Queries and ranks matches across **Recent Searches**, **Custom Homepage Shortcuts**, **Saved Bookmarks** (titles and URLs), and **Browsing History** (`HazeDatabase` SQLite index) as you type.
- **Live Autocomplete Fusion**:
  - Merges deduplicated local index hits with live Google autocomplete suggestions over a debounced asynchronous pipeline (150ms typing debounce with request sequence cancellation).
- **Smart URL vs. Query Resolution**:
  - Automatically distinguishes between direct domain/URL navigation (`https://`, `http://`, TLD detection) and search queries across user-selectable search engines (Google, DuckDuckGo, Bing, Yahoo, Ecosia, Wikipedia).

---

### Redesigned First-Run Onboarding & Bottom-Anchored UI
- **Chrome-Inspired First-Run Experience (`HazeOnboardingManager`)**:
  - Clean, minimalist first-launch welcome flow featuring the Haze emblem, one-tap Google Account sign-in or instant guest continuation, and a streamlined default-browser prompt.
- **Distraction-Free Viewport**:
  - Removes top browser chrome and debug toolbars (`R.id.toolbar` hidden), leaving only a subtle top-right profile avatar on the Home screen and 100% of vertical screen real estate for web content.
- **Bottom-Anchored Navigation Pill**:
  - Houses the omnibox search/address bar, voice search trigger, live tab counter badge, and browser menu within effortless thumb reach, paired with a contextual floating Back/Forward/Share pill on web pages.

---

### Floating Mini-Player and Picture-in-Picture
Haze turns video consumption on mobile into a multitasking breeze.

- **In-App Floating Mini-Player**:
  - Drag-and-drop floating player over any webpage.
  - Responsive edge-snapping physics and corner minimization.
  - Integrated seek bar, volume mute toggle, and forward/rewind controls.
  - Pinch-and-drag resize handle for flexible scaling.

---

### Hardware-Backed Password Vault
Never compromise between convenience and security.

- **AndroidKeyStore Security**: Credentials are encrypted using hardware-backed **AES-256-GCM** encryption (`HazePasswordVault`) with secure IV generation.
- **Biometric / Keyguard Gating**: Viewing or exporting passwords requires device authentication (Fingerprint, Face Unlock, or Device PIN).
- **In-Page Credential Autofill and Save**: Detects login submissions and prompts to securely save or update stored credentials.
- **Universal Portability**: Instant CSV import and export compatible with Chrome, Bitwarden, Firefox, and 1Password.

---

### Resumable Multi-Threaded Download Engine
A robust download engine engineered for unstable mobile connections:

- **Parallel Worker Pool**: Concurrency-controlled task queue (up to 3 simultaneous downloads).
- **HTTP Range Resume**: Utilizes `RandomAccessFile` and HTTP `Range` headers to resume interrupted downloads without losing progress.
- **Real-Time Telemetry**: Real-time download throughput (KB/s, MB/s), ETA computation, and progress updates.
- **Persistent Queue**: SQLite-backed history tracking file paths, MIME types, and download status.

---

### Google Account and Cloud Backup Sync
Keep your browser data synchronized across your Android ecosystem:

- **Google Sign-In**: Easy authentication via Google Play Services Auth (`play-services-auth`).
- **Selective Data Sync**:
  - Bookmarks and hierarchical folder trees.
  - Custom browser settings and search preferences.
  - Homepage shortcuts and quick links.
  - Color themes and wallpaper selections.
- **Conflict-Free Local/Cloud Merge**: Timestamp-audited JSON sync backups with automatic backup restore.

---

### Ambient Themes and Custom Personalization
Make your browsing canvas uniquely yours:

- **Ambient Wallpapers**: Built-in curated background themes including:
  - Serene Canyon Arch at Sunset
  - Twilight Forest River Valley
  - Sunlit Ocean Canyon Reef
  - Pastel Ringed Planet Over Dreamy Alien Sea
  - Twilight Coastal Road Overlook
- **Offline Astronaut Experience**: Custom-illustrated interactive offline screen (Offline Astronaut and Cat on the Moon) featuring real-time connection diagnostics and a quick retry engine.
- **Dynamic Theming**: Full support for true OLED Dark Mode, crisp Light Mode, and Material Design accent tinting.

---

### Power Utilities Suite
- **Text-to-Speech (TTS) Narrator**: Integrated speech synthesis engine to read articles and web page content hands-free.
- **QR Code Engine**: Scan web QR codes with the camera and generate QR codes for any webpage link instantly.
- **In-Page Find (`findAllAsync` / `findNext`)**: Live text highlighting and match cycling across web documents.
- **Responsive Tab Grid**: Full-screen visual tab switcher with live captured tab previews, Normal/Private tab segmentation, and one-tap restore.
- **Desktop Mode and Immersive View**: Instant desktop User-Agent spoofing and fullscreen distraction-free reading.

---

## Deep-Dive Technical Architecture

### JNI Zero Dispatch Pipeline
Haze communicates between its Kotlin UI orchestration layer and the low-level C++ Blink/V8 internals through Chromium's official modern binding generator: **`JNI_Zero`**.

```text
[Kotlin Presentation Layer] 
       │  (Calls IBrowserEngine / NativeChromiumTab methods)
       ▼
[JNI_Zero Auto-Generated Stubs: org.jni_zero.*]
       │  (Zero-overhead direct function pointer dispatch)
       ▼
[Chromium JNI Dispatch Bridge: jni_bridge.cc / NativeContentShell]
       │  (Passes thread-safe raw pointers & WebContents handles)
       ▼
[Chromium C++ Core: Blink Rendering Engine + V8 JS Runtime]
```

This decoupled architecture guarantees:
1. **Type Safety**: Automatic translation between Java/Kotlin objects and native C++ `base::android::ScopedJavaLocalRef`.
2. **Deterministic Memory Lifecycles**: Eliminates JNI global reference memory leaks that often plague custom Android browser forks.
3. **Low-Latency Callback Hooks**: Immediate C++ to Kotlin signals for page navigation states, DOM layout progress, and render process termination recovery.

### Chromium Content Layer vs. WebView

| Metric / Capability | Standard Android WebView | Haze Native Chromium Engine |
| :--- | :--- | :--- |
| **Blink & V8 Version** | Frozen to host OS system version | Custom-compiled high-performance binary snapshot |
| **Process Isolation** | Shared sandbox / constrained by system | Dedicated sandboxed multi-process `WebContents` architecture |
| **HTTP/3 & QUIC** | Inconsistent across older Android releases | Full native support compiled into network stack |
| **Custom Network Interceptors** | Synchronous Java `shouldInterceptRequest` | Native C++ socket-level trie matching (`libhazeshield.so`) |
| **JNI Dispatch Bridge** | Hidden framework internal bindings | Direct `JNI_Zero` runtime dispatch over `chromium_engine.jar` |
| **Memory Allocation** | System Webview shared zygote | Custom heap snapshots (`snapshot_blob_64.bin` / `snapshot_blob_32.bin`) |
| **Dual-ABI Packaging** | Relies on vendor system library extraction | Bundled dual-ABI (`arm64-v8a` + `armeabi-v7a`) native libraries |

### High-Throughput Native C++ Matching Pipeline
Network security evaluation is performed inside `libhazeshield.so` compiled with C++17:
- **`DomainMatcher`**: Operates on a hash-based set and prefix tree to identify blocked hostnames with $O(1)$ amortized complexity.
- **`RuleMatcher`**: Evaluates wildcard and substring patterns across URL paths without blocking the UI thread.
- **`std::shared_mutex` Concurrency**: Lock-free concurrent read operations across multiple parallel network requests, ensuring zero frame drops even during intense web asset loading.

---

## Universal Website Compatibility Suite (Top 50 Domains)

Haze is tested and optimized across 50 of the world's most demanding web applications, spanning mobile and desktop viewports:

| Category | Target Websites | Compatibility & Optimization Highlights |
| :--- | :--- | :--- |
| **Search Engines** | `google.com`, `bing.com`, `yahoo.com`, `yandex.ru`, `baidu.com`, `duckduckgo.com` | Fast instant results rendering, location suggestions, voice input integration, and image carousel navigation. |
| **Streaming Media** | `youtube.com`, `netflix.com`, `twitch.tv`, `bilibili.com`, `imdb.com` | Notification Media Center scrub controls, 1080p60 WebM/MP4 hardware decoding, background playback, and PiP multitasking. |
| **Social Networks** | `facebook.com`, `instagram.com`, `x.com`, `reddit.com`, `tiktok.com`, `linkedin.com`, `pinterest.com`, `vk.ru` | Infinite scroll smoothness, multi-image upload dialogues, auto-playing feed videos, and dynamic story carousels. |
| **Messaging & Chat** | `whatsapp.com`, `t.me`, `discord.com` | QR code camera pairing, WebRTC voice/video calls, audio recording permissions, and live WebSocket message delivery. |
| **AI Assistants** | `chatgpt.com`, `gemini.google.com`, `claude.ai` | Real-time Server-Sent Events (SSE) streaming responses, code snippet copy-to-clipboard, and complex LaTeX formula rendering. |
| **E-Commerce & Travel** | `amazon.com`, `temu.com`, `ebay.com`, `booking.com` | Secure multi-step payment flows, interactive map widgets, photo zoom galleries, and dynamic filtering drawers. |
| **News & Knowledge** | `wikipedia.org`, `yahoo.co.jp`, `news.yahoo.co.jp`, `naver.com`, `dzen.ru`, `globo.com`, `weather.com` | Fast text reflow, responsive typography scaling, article table-of-contents navigation, and high-contrast reader view. |
| **Productivity & Cloud**| `cloud.microsoft`, `live.com`, `microsoft.com`, `canva.com`, `samsung.com`, `github.com`, `brave.com`, `fandom.com`, `mail.ru` | Complex WebAssembly (Wasm) canvas manipulation, drag-and-drop file uploaders, code diff viewers, and responsive editor panes. |
| **Entertainment & Web** | `pornhub.com`, `xhamster.com`, `xvideos.com`, `stripchat.com`, `bet.br` | Native HLS/DASH streaming video playback, interactive player controls, and strict script isolation. |

---

## Architecture and Design

```mermaid
graph TD
    subgraph UI ["Presentation Layer (Kotlin & Material 3)"]
        MA[MainActivity]
        Onboard[HazeOnboardingManager]
        TabMgr[FullScreen Tab Manager]
        MiniPlayer[HazeMiniPlayer]
        ShieldUI[Privacy Shield Dashboard]
        VaultUI[Password Vault Dialog]
        MediaNotif[HazeMediaNotificationService]
    end

    subgraph Core ["Feature & Indexing Managers"]
        SearchIdx[Hybrid Local & Live Search Indexer]
        AccountMgr[HazeAccountManager]
        SyncMgr[HazeSyncManager]
        DownloadEng[HazeDownloadEngine]
        PassVault[HazePasswordManager]
        FilterMgr[HazeFilterListManager]
        AntiTrack[HazeAntiTrackingEngine]
    end

    subgraph Abstraction ["Decoupled Browser Engine Layer"]
        IBEngine[IBrowserEngine]
        IBTab[IBrowserTab]
        INav[INavigationController]
        NativeEngine[NativeChromiumBrowserEngine]
        NativeTab[NativeChromiumTab]
        NativeNav[NativeNavigationController]
        ChromiumTab[ChromiumBrowserTab]
    end

    subgraph ChromiumNative ["Embedded Chromium Runtime (arm64-v8a & armeabi-v7a)"]
        JniZero[JNI_Zero Bridge & chromium_engine.jar]
        ContentShell[libcontent_shell_content_view.so]
        BlinkV8[Blink Renderer + V8 Snapshot + ICU Data]
    end

    subgraph ShieldNative ["Native C++17 Shield Core (libhazeshield.so)"]
        JNI[jni_bridge.cc]
        ShieldCore[haze_shield_core.cc]
        DomainMatch[domain_matcher.cc]
        RuleMatch[rule_matcher.cc]
    end

    subgraph SystemServices ["Android System Integration"]
        MediaSession[Android MediaSessionCompat]
        NotifManager[NotificationManager & Shade]
        KeyStore[AndroidKeyStore AES-GCM]
        DB[(HazeDatabase SQLite)]
    end

    MA --> Onboard
    MA --> SearchIdx
    SearchIdx --> DB
    MA --> IBEngine
    IBEngine --> NativeEngine
    NativeEngine --> NativeTab
    NativeTab --> NativeNav
    NativeEngine --> JniZero
    JniZero --> ContentShell
    ContentShell --> BlinkV8

    MA --> ChromiumTab
    ChromiumTab --> MediaNotif
    MediaNotif --> MediaSession
    MediaNotif --> NotifManager

    MA --> MiniPlayer
    MA --> ShieldUI
    MA --> VaultUI

    ShieldUI --> FilterMgr
    FilterMgr --> JNI
    JNI --> ShieldCore
    ShieldCore --> DomainMatch
    ShieldCore --> RuleMatch
    ChromiumTab --> AntiTrack

    VaultUI --> PassVault
    PassVault --> KeyStore

    SyncMgr --> AccountMgr
    SyncMgr --> DB
    DownloadEng --> DB
```

---

## Project Directory Structure

```text
Haze/
├── .github/                              # CI/CD Workflows & Issue Templates
├── assets/                               # Brand logos & design showcases
│   ├── background/                       # Curated ambient themes & offline artwork
│   └── logo.png                          # High-resolution Haze emblem
├── app/
│   ├── build.gradle.kts                  # Build config (com.aryandas.haze), ABI splits, R8 & native unpacker
│   ├── google-services.json              # Firebase & Google Services client credentials
│   ├── proguard-rules.pro                # R8 keep rules for JNI_Zero, Chromium & AndroidX bridges
│   ├── libs/                             # Compiled Chromium Java runtime archives
│   │   ├── chromium_engine.jar           # Merged 394 Chromium Content/Blink/Base Java libraries
│   │   ├── media_java.jar                # Chromium media & audio bindings
│   │   └── ...                           # Mojo, capture, gamepad, bluetooth & GURL jars
│   ├── native_blobs/                     # GitHub-safe (<40MB) compressed Chromium .so chunks
│   │   ├── arm64-v8a/                    # 64-bit libcontent_shell_content_view.so.gz.part00..02
│   │   └── armeabi-v7a/                  # 32-bit libcontent_shell_content_view.so.gz.part00..01
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml       # Permissions, FileProvider authority & MediaPlayback service
│   │   │   ├── assets/                   # Chromium & Haze runtime assets
│   │   │   │   ├── content_shell.pak     # Chromium UI & Blink packed resources
│   │   │   │   ├── icudtl.dat            # ICU internationalization data table
│   │   │   │   ├── snapshot_blob_64.bin  # 64-bit V8 heap & context startup snapshot
│   │   │   │   ├── snapshot_blob_32.bin  # 32-bit V8 heap & context startup snapshot
│   │   │   │   └── ...                   # Ambient WebP wallpapers & filter rules
│   │   │   ├── cpp/                      # C++17 Native Privacy Shield (libhazeshield.so)
│   │   │   │   ├── CMakeLists.txt
│   │   │   │   ├── domain_matcher.cc / .h
│   │   │   │   ├── haze_shield_core.cc / .h
│   │   │   │   ├── jni_bridge.cc
│   │   │   │   └── rule_matcher.cc / .h
│   │   │   ├── jniLibs/                  # Reassembled native Chromium libraries (auto-unpacked)
│   │   │   │   ├── arm64-v8a/libcontent_shell_content_view.so
│   │   │   │   └── armeabi-v7a/libcontent_shell_content_view.so
│   │   │   ├── java/
│   │   │   │   ├── org/jni_zero/         # JNI_Zero native dispatch & annotation bindings
│   │   │   │   └── com/example/haze/
│   │   │   │       ├── engine/           # Browser Engine Abstraction & Implementations
│   │   │   │       │   ├── IBrowserEngine.kt
│   │   │   │       │   ├── IBrowserTab.kt
│   │   │   │       │   ├── INavigationController.kt
│   │   │   │       │   ├── chromium/       # Optimized Chromium tab with MediaSession & AdGuard rules
│   │   │   │       │   └── nativechromium/ # Embedded Native Chromium Content runtime
│   │   │   │       │       ├── NativeChromiumBrowserEngine.kt
│   │   │   │       │       ├── NativeChromiumTab.kt
│   │   │   │       │       └── NativeNavigationController.kt
│   │   │   │       ├── native_engine/    # C++17 Privacy Shield JNI Bridge
│   │   │   │       ├── HazeMediaNotificationService.kt # Foreground Notification Center Media Controller
│   │   │   │       ├── HazeAntiTrackingEngine.kt       # Anti-Fingerprinting & Link Sanitizer
│   │   │   │       └── MainActivity.kt   # Primary browser orchestrator & hybrid indexer
│   │   │   └── res/                      # Android layouts, icons, and themes
│   │   └── test/                         # Unit & engine test suites
│   └── src/androidTest/                  # Instrumentation test suite (com.aryandas.haze)
├── gradle/
│   └── libs.versions.toml                # Version catalog
└── build.gradle.kts                      # Root build script
```

---

## Tech Stack and Dependencies

| Layer | Technology | Purpose |
| :--- | :--- | :--- |
| **Application ID** | `com.aryandas.haze` | Official package identity across Android OS and distribution channels |
| **Language** | Kotlin 1.9+ / Java 11 | Application logic, UI controllers, engine orchestration |
| **Web Engine** | Custom Native Chromium (`arm64-v8a` & `armeabi-v7a`) | Standalone Blink rendering, V8 JS engine, `WebContents` & `JNI_Zero` |
| **Native Shield** | C++17 & CMake 3.22.1 (`libhazeshield.so`) | Ultra-fast rule parsing, sub-millisecond ad/tracker pattern matching |
| **Media Controller** | `MediaSessionCompat` & Foreground Service | Notification shade and lockscreen playback controls with scrubber tracking |
| **Build Optimization** | R8 Minification, Resource Shrinking & C++ `-O3` Strip | Bytecode tree-shaking, symbol stripping, per-ABI & Universal APK packaging |
| **UI Components** | Material 3 & AndroidX | Edge-to-edge screens, bottom-anchored omnibox, fluid transitions |
| **Cryptography** | AndroidKeyStore (AES-256-GCM) | Hardware-isolated credential encryption |
| **Cloud Sync** | Google Play Services Auth | OAuth 2.0 account authentication & cloud backups |
| **Persistence** | SQLite3 (`HazeDatabase`) | Thread-safe history, bookmark, shortcut, and download indexing |

---

## Getting Started and Build Instructions

### Prerequisites
Make sure your development machine has the following installed:
1. **JDK 17** or newer (Android Studio bundled JBR recommended).
2. **Android Studio** (Ladybug, Meerkat, or newer) with **Android SDK 37**.
3. **Android NDK** (version 25+ / 28+) and **CMake 3.22.1**.

### Automated Native Binary Reassembly Pipeline
Because GitHub enforces a strict 100 MB per-file limit, the compiled 64-bit (`arm64-v8a`, ~292 MB) and 32-bit (`armeabi-v7a`, ~138 MB) `libcontent_shell_content_view.so` binaries are stored in the repository as compressed `<= 40 MB` chunks under `app/native_blobs/<abi>/`.

When you run any Gradle build task, the custom **`prepareNativeChromiumLibs`** task (hooked automatically into `preBuild` in `app/build.gradle.kts`) seamlessly concatenates and decompresses both `.so` binaries into `app/src/main/jniLibs/arm64-v8a/` and `app/src/main/jniLibs/armeabi-v7a/` before packaging.

### Clone and Build

```bash
# 1. Clone the repository
git clone https://github.com/aryandas079/Haze.git

# 2. Navigate to the project directory
cd Haze

# 3. Build the optimized Release APKs (Universal + arm64-v8a + armeabi-v7a)
./gradlew assembleRelease

# 4. Install the Universal Release APK onto any 32-bit or 64-bit Android device
adb install -r app/build/outputs/apk/release/app-universal-release.apk
```

The release build generates three APKs under `app/build/outputs/apk/release/`:
- `app-universal-release.apk`: Universal APK containing both `arm64-v8a` and `armeabi-v7a` native engines (works on all Android phones).
- `app-arm64-v8a-release.apk`: Dedicated 64-bit ARM release APK.
- `app-armeabi-v7a-release.apk`: Dedicated 32-bit ARM release APK.

> [!NOTE]
> To enable Google Sign-In with cloud sync during personal development, make sure your `google-services.json` file inside the `app/` directory is configured with your signing key's SHA-1 fingerprint for package `com.aryandas.haze`.

### Running Tests

Execute the unit test suite verifying filter list compilation, ad/tracker detection, and third-party rule handling:

```bash
./gradlew testDebugUnitTest
```

---

## Privacy and Security Blueprint

Haze treats user privacy as a fundamental architectural constraint, not an afterthought:

1. **Zero Tracking Telemetry**: Haze collects no telemetry, no device IDs, no browsing habits, and no usage analytics.
2. **Local-First Processing**: URL matching, search indexing, and shield classifications happen 100% on-device in native and local SQLite memory.
3. **Hardware Key Isolation**: Master keys never leave the secure enclave/TEE of your mobile hardware.
4. **Isolated Private Sessions**: Incognito tabs operate in isolated contexts that permanently discard cookies, web caches, and storage blobs upon closure.
5. **Surrogate Scripting**: Blocks invasive analytics and advertising trackers while safely supplying lightweight local surrogate stubs (`gtag`, `fbq`, `clarity`, `adsbygoogle`) to ensure host websites never break.

---

## Roadmap

- [x] Production application identity migration to `com.aryandas.haze`
- [x] Custom-compiled Dual-ABI (`arm64-v8a` & `armeabi-v7a`) Native Chromium Engine integration
- [x] Full `JNI_Zero` runtime bridge & `chromium_engine.jar` packaging
- [x] Android Notification Center & Lockscreen Media Controller with scrubber controls
- [x] Zero-polling streaming media acceleration for YouTube Music, Spotify, and web video
- [x] Event-driven ad mitigation and declarative CSS cosmetic filtering
- [x] Chrome-inspired First-Run Experience & bottom-anchored UI redesign
- [x] Hybrid local database indexing + live Google autocomplete search omnibox
- [x] Native C++17 Shield Engine (`libhazeshield.so`) with JNI integration
- [x] Anti-Fingerprinting, Bounce Tracking protection, and link parameter sanitizer
- [x] Hardware-backed Password Vault with biometric unlock
- [x] In-app Draggable Mini-Player
- [x] Multi-threaded resumable download engine
- [x] Google Account Cloud Sync
- [ ] DNS-over-HTTPS (DoH) with NextDNS & Cloudflare presets
- [ ] WebExtension (MV3) experimental compatibility layer
- [ ] Tab Groups and Collections
- [ ] Built-in PDF annotator & offline reader mode

---

## Legal, Governance & Trust Policies

Haze Browser is distributed as an open-source native Android web browser. Below is the comprehensive statement of privacy practices, acceptable use, security disclosures, and intellectual property terms.

---

### Privacy Policy & Client-Side Data Architecture

Haze operates on a **zero-telemetry, local-first** architecture:

- **Zero First-Party Telemetry**: Haze contains no background telemetry daemons, diagnostic beacons, pingbacks, or usage counters. No server logs exist because Haze operates without central user-tracking servers.
- **Zero Analytics SDKs**: The codebase is completely free of Google Firebase Analytics, Adjust, AppsFlyer, Mixpanel, Crashlytics, Facebook Graph SDK, or third-party behavioral profiling code.
- **Zero Advertising Identifiers**: Haze never queries the Android Advertising ID (`AAID`) or hardware serial numbers (`IMEI`, `MAC address`, `Android ID`).
- **SQLite Local Indexing (`HazeDatabase`)**: All user browsing artifacts—including browsing history, custom bookmarks, homepage shortcuts, and search omnibox caches—reside solely in the private app sandbox (`/data/user/0/com.aryandas.haze/databases/`).
- **Hardware-Backed AES-256-GCM Vault**: Web passwords, autofill entries, and credentials are encrypted via `HazePasswordVault` using hardware-generated keys managed by the `AndroidKeyStore`. Cryptographic keys never leave the hardware-isolated Trusted Execution Environment (TEE) or StrongBox keymaster.
- **Biometric Unlock**: Stored credentials and private browsing tabs can be locked behind device-level biometric authentication (Fingerprint, Face Unlock, or Device Lock PIN).
- **Incognito & Ephemeral Session Lifecycle**: Incognito tabs operate in a dedicated in-memory context completely detached from standard browsing profiles. When the last Incognito tab is closed, in-memory cookies, DOM storage keys, and IndexedDB data are immediately purged from memory.
- **Cloud Synchronization Boundaries**: Cloud sync in Haze is entirely opt-in. When enabled via Google Account Authentication (`play-services-auth`), your browser settings, bookmarks, and custom shortcuts are synchronized directly to your personal Google Cloud / Firebase container. No intermediary proxy or developer-owned relay inspects or relays your synchronized data.

---

### Cookie, Cache & Web Storage Governance

- **First-Party Cookies**: First-party session and authentication cookies are stored locally to allow persistent logins across web applications (e.g., GitHub, YouTube, Reddit).
- **Third-Party Tracking Cookies**: Haze blocks known third-party tracking cookies by default via its native C++ rule engine (`libhazeshield.so`), neutralizing cross-site advertising networks.
- **Origin Partitioning**: To prevent cross-site identity leakage, Haze enforces origin partitioning across `localStorage`, `sessionStorage`, `IndexedDB`, and Service Worker registrations. Tracking scripts cannot correlate your identity across domains.
- **Cache Eviction**: HTTP caching complies strictly with RFC 7234 cache directives. Cached assets can be flushed on demand via `Settings > Clear Browsing Data` or automatically configured to evict stale entries under low device storage conditions.

---

### End-User Terms of Service & Acceptable Use Policy

- **Permitted Use**: Haze Browser is distributed as free, open-source software under the Apache License, Version 2.0. Users are granted permission to install, run, modify, fork, and distribute the application for personal, educational, research, or commercial purposes.
- **Prohibited Activities**: Users and redistributors may not use Haze Browser or its native filtering engine to conduct distributed denial-of-service (DDoS) attacks, automated botnet crawling, network flooding, vulnerability probing of unauthorized websites, or malware distribution.
- **Security Boundaries**: Haze does not bypass website paywalls, defeat Digital Rights Management (DRM), crack CAPTCHAs, or spoof authentication tokens. Native filtering is strictly limited to blocking invasive advertising, telemetry, and script tracking.
- **Open-Source Disclaimer (AS-IS)**: Haze Browser is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied, including, without limitation, any warranties or conditions of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.

---

### Security Architecture & Vulnerability Disclosure Program

- **Dual-Layer Security Perimeter**:
  - *Kotlin/Android Runtime*: Adheres to Android zero-trust architecture, enforces explicit one-time runtime permissions (`RECORD_AUDIO`, `CAMERA`), and blocks cleartext HTTP by default.
  - *Native C++17 Shield (`libhazeshield.so`)*: Hardened with `-fstack-protector-strong -D_FORTIFY_SOURCE=2 -fPIE -O3`, with thread-safe `std::shared_mutex` synchronization and bounded memory limits.
- **Upstream Chromium Security Backports**: High-severity upstream Chromium Common Vulnerabilities and Exposures (CVEs) affecting Blink, V8 sandboxing, and Skia rendering are periodically compiled and integrated into updated releases.
- **Responsible Vulnerability Disclosure**:
  - Report suspected security vulnerabilities directly to the maintainer via GitHub Private Security Advisories.
  - Include reproducible test cases, Android version, device model, and scrubbed logcat output.
  - Inquiries receive an initial acknowledgment within 48 hours, followed by remediation updates.

---

### Regulatory Compliance & Data Subject Rights (GDPR / CCPA)

- **Data Minimization**: Haze never collects, sells, rents, or monetizes personal data. You retain 100% control over all data generated during web browsing.
- **Right to Erasure ("Forget Me")**: Instantly delete browsing history, cached web files, saved cookies, site permissions, and encrypted passwords via `Settings > Privacy Shield > Clear All Browsing Data`.
- **Right to Data Portability**: Passwords stored in the hardware vault can be exported to standard CSV format at any time using biometric authentication.
- **Global Privacy Controls**: Haze automatically transmits the `DNT: 1` (Do Not Track) and `Sec-GPC: 1` (Global Privacy Control) HTTP headers on all outgoing web requests.
- **Zero Subprocessors**: Because Haze operates client-side without proprietary backend servers, there are zero third-party data subprocessors.

---

### Law Enforcement Guidelines & Transparency

- **Absence of User Data**: Because Haze Browser does not log, collect, or store user browsing sessions, IP addresses, or device identifiers on remote infrastructure, **we possess no user data to produce in response to subpoenas, warrants, court orders, or law enforcement requests**.
- **On-Device Physical Encryption**: Any data residing on a physical device is encrypted under the device's hardware credentials and the Android Keystore system. The maintainers of Haze do not hold encryption keys or backdoors.

---

### Intellectual Property, Third-Party Notices & DMCA Policy

- **Apache 2.0 Open Source Licensing**: The original application code, Kotlin UI components, CMake configurations, and custom C++17 shield engines are licensed under the **Apache License, Version 2.0**.
- **Third-Party Software Attribution**:
  - *The Chromium Project* (Blink, V8, Content Shell, JNI_Zero): Copyright (c) The Chromium Authors. Licensed under the BSD 3-Clause License.
  - *Android Jetpack & AndroidX Libraries*: Copyright (c) The Android Open Source Project. Licensed under the Apache License, Version 2.0.
  - *Kotlin Standard Library & Coroutines*: Copyright (c) JetBrains s.r.o. Licensed under the Apache License, Version 2.0.
  - *ICU4C / Unicode Data Tables*: Copyright (c) Unicode, Inc. Licensed under the Unicode License Agreement.
- **Trademark Guidelines**: The names **"Haze"**, **"Haze Browser"**, and the associated icon and branding assets are marks belonging to Aryan Das. The Apache 2.0 license grants code rights, but does not grant permission to use project trademarks for downstream branding without permission.
- **DMCA / Copyright Inquiries**: Notices regarding copyright infringement or licensing conflicts can be filed directly through GitHub Issues or by contacting the maintainer.

---

### Accessibility & Ergonomic Inclusion Statement

- **WCAG 2.1 & TalkBack Screen Reader Compatibility**: All UI surfaces—including the bottom navigation bar, quick actions pill, tab manager cards, and password dialogs—provide rich, localized `contentDescription` semantics for Android TalkBack.
- **Dynamic Typography & Font Scaling**: Full support for Android system typography scaling up to 200% without clipped layouts.
- **True OLED Dark Mode**: Pure `#000000` AMOLED high-contrast palette minimizes eye strain in low-light environments and significantly conserves battery life on OLED panels.
- **One-Handed Mobile Reachability**: All essential touch targets (address omnibox, search suggestions, tab switcher trigger, voice search, settings menu) are anchored to the bottom of the display for effortless one-handed control.

---

### Legal Notice & Imprint

- **Project Name**: Haze Browser
- **Application Package**: `com.aryandas.haze`
- **Lead Architect & Maintainer**: Aryan Das
- **Source Code Repository**: [https://github.com/aryandas079/Haze](https://github.com/aryandas079/Haze)
- **Primary License**: Apache License, Version 2.0
- **Governing Law**: Subject to open-source software licensing norms and applicable international intellectual property laws.

---

## Contributing and Community

Haze Browser is open source and community-driven. We welcome contributions from developers worldwide, including bug fixes, engine optimizations, documentation improvements, and feature proposals.

1. **Fork the Repository**: Create your feature branch (`git checkout -b feature/amazing-feature`).
2. **Adhere to Code Standards**: Follow established Kotlin and modern C++17 conventions.
3. **Commit Your Changes**: Keep commit messages clear, descriptive, and atomic (`git commit -m 'Add native feature'`).
4. **Push to Branch**: (`git push origin feature/amazing-feature`).
5. **Open a Pull Request**: Submit your pull request with a detailed description of changes and test results.

### Code of Conduct and Issue Tracking
- Bug reports and feature suggestions are tracked via [GitHub Issues](https://github.com/aryandas079/Haze/issues).
- Please ensure sensitive credentials and personal data are redacted from issue reports and log outputs.

---

## Author and Contact

**Haze Browser** is designed, architected, and maintained by **Aryan Das**.

- **GitHub**: [@aryandas079](https://github.com/aryandas079)
- **LinkedIn**: [Aryan Das](https://linkedin.com/in/iam-aryandas)
- **Instagram**: [@iam_aryandas](https://instagram.com/iam_aryandas)

---

## License

Haze Browser is released as open source software under the terms of the **Apache License, Version 2.0**.

```text
Copyright (c) 2026 Aryan Das

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

For full details, please refer to the [LICENSE](LICENSE) file located in the root of this repository.

---

<div align="center">
  <sub>Copyright (c) 2026 Aryan Das. Licensed under the Apache License, Version 2.0.</sub>
</div>
