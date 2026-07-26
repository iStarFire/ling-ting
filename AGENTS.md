# TingYiTing Project Context

Last reviewed: 2026-07-26

## Project Overview

TingYiTing is a minimal Android audiobook app. It connects to an Alist WebDAV server, browses cloud-drive audio resources, streams them online with Media3/ExoPlayer, and stores bookshelf metadata plus playback progress locally.

The app intentionally does not download audio files to local storage. Books store WebDAV URLs or imported directory roots, and playback uses HTTP/WebDAV streaming with Range support.

## Tech Stack

- Language: Kotlin
- UI: Jetpack Compose + Material 3
- Playback: AndroidX Media3 ExoPlayer and MediaSessionService
- Database: Room
- Dependency injection: Hilt
- Networking: OkHttp with WebDAV PROPFIND/GET
- Preferences/secrets: EncryptedSharedPreferences via androidx.security:security-crypto
- Build: Gradle Kotlin DSL, AGP 8.4.0, Kotlin 1.9.22, JDK 17

## Current Architecture

Main flow:

1. TingYiTingApp starts and asks WebDavRepository to restore the saved WebDAV config.
2. MainActivity starts PlaybackService and hosts Compose navigation.
3. AppNavigation starts at the bookshelf and exposes two top-level tabs: bookshelf and accounts.
4. The bookshelf shows locally stored books and routes to WebDAV browsing or playback.
5. The browser lists WebDAV directories/files and can add a single audio file or import one or more directories.
6. Directory import recursively collects supported audio files, stores one book plus ordered tracks, then playback uses an ExoPlayer playlist.
7. PlayerViewModel saves progress periodically, on seek, and when playlist tracks change.

Important package areas:

- app/src/main/java/com/tingyiting/ui/navigation/: Compose navigation and route definitions.
- app/src/main/java/com/tingyiting/ui/bookshelf/: bookshelf screen and state.
- app/src/main/java/com/tingyiting/ui/accounts/: external account management UI.
- app/src/main/java/com/tingyiting/ui/server/: WebDAV config/edit screen.
- app/src/main/java/com/tingyiting/ui/browser/: WebDAV directory browsing and import.
- app/src/main/java/com/tingyiting/ui/player/: playback UI and player state.
- app/src/main/java/com/tingyiting/data/repository/: business/data coordination.
- app/src/main/java/com/tingyiting/data/local/: Room database, DAOs, and entities.
- app/src/main/java/com/tingyiting/network/: WebDAV client, PROPFIND parser, and auth data source.
- app/src/main/java/com/tingyiting/service/: background playback service.
- app/src/main/java/com/tingyiting/di/: Hilt modules.

## Data Model Notes

- books stores both legacy single-file books and directory-imported books.
- Single-file books primarily use webdavUrl.
- Directory-imported books use rootPath and currentTrackIndex; their audio items are stored in tracks.
- tracks stores bookId, trackIndex, title, WebDAV URL, path, duration, and position.
- Room database is currently version 3 with explicit migrations:
  - v1 -> v2: add tracks.
  - v2 -> v3: add rootPath and currentTrackIndex to books.

## WebDAV Notes

- WebDavRepository owns the active WebDavClient, current config, and config state flow.
- Saved config is restored on app startup and persisted through WebDavConfigStore.
- WebDavClient sends PROPFIND requests for listing and builds streaming URLs.
- WebDavPropfindParser is SAX-based and normalizes href values relative to the configured base URL, preventing duplicate mount prefixes such as /dav/dav/...
- Supported audio extensions are defined in WebDavFile.SUPPORTED_AUDIO_EXTENSIONS.
- Directory import uses recursive traversal with depth/count safeguards and natural sorting.

## Playback Notes

- AppModule provides a singleton ExoPlayer configured with DynamicAuthDataSourceFactory, so HTTP(S) playback gets WebDAV auth dynamically from WebDavRepository.
- Single-file books fall back to one MediaItem.
- Multi-track books call setMediaItems(...), resume from currentTrackIndex and saved track position, and update the current track on media-item transitions.
- Sleep timer and periodic progress saving live in PlayerViewModel.

## Tests And Verification

Project convention from CLAUDE.md: new features and bug fixes should include unit tests, preferably TDD-style.

Current JVM tests include:

- WebDavPropfindParserTest: namespace handling, href normalization, UTF-8 percent decoding, base path stripping.
- BrowserViewModelTest: multi-directory selection/import behavior and determinate import progress.

Verified on 2026-07-26:

    ./gradlew testDebugUnitTest

Result: build successful. One non-blocking Compose deprecation warning remains in BookshelfScreen.kt for LinearProgressIndicator(Float, ...).

## Current Worktree State

The worktree was already dirty during review. The visible uncommitted work appears to implement:

- bookshelf-first startup and account-management refactor;
- encrypted persistent WebDAV config;
- directory import for WebDAV folders;
- Room track support and multi-track playback;
- JVM tests for parser/import behavior.

Avoid treating the current tree as a clean baseline. Preserve unrelated user changes, especially in already modified files such as CLAUDE.md, app/build.gradle.kts, navigation, repository, database, browser, bookshelf, and player files.

## Development Guidance

- Follow the existing MVVM + Repository + Hilt pattern.
- Keep UI in Compose Material 3 and avoid introducing View-system code.
- Keep WebDAV behavior behind repository/network layers.
- Preserve single-file playback compatibility while extending directory/multi-track flows.
- Do not print credentials or auth headers in logs.
- For parsing, repository logic, ViewModel branches, and migrations, add or update JVM tests under app/src/test/java/...
- Before handoff for code changes, run ./gradlew testDebugUnitTest; run ./gradlew assembleDebug when UI/build integration changed.
