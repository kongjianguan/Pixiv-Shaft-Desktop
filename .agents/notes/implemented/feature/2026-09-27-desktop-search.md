# Agent Note: Desktop search page

Status: implemented

English | [中文](2026-09-27-desktop-search.zh.md)

## Problem

The Flutter search entry exposes illustration keyword results and recent history. The Compose search page also offers novel and user results, autocomplete, account-specific filters, pinned history, numeric identifiers, and recognized Pixiv links. These controls form one search workflow.

## Decision

The Flutter search page keeps illustration, novel, and user result tabs. Illustration and novel requests use the existing Rust paged-list entry points with query strings built by Dart's `Uri` library. Rust provides autocomplete, account-specific filter choices, and user search through the same authenticated Pixiv client. Each result tab keeps its own loaded page while the user changes tabs. Search conditions follow the Compose API parameters; R18 and AI choices are also applied to the returned items. Numeric identifiers open a choice of work, novel, user, or keyword search. Pixiv links open the matching in-app detail page. Search history retains pinned entries and can remove individual entries or clear either group. Search and browsing history timestamps use epoch milliseconds, matching the existing Compose records.

## Alternatives considered

**Keep illustration-only search.** This leaves the novel and user search paths inaccessible through the established search entry.

**Create a second query-string builder in Rust.** The public Rust page functions already accept a request path, and Dart's `Uri` class constructs encoded query strings. Another builder would duplicate that work.

## Consequences

The Rust bridge exposes search suggestions, filter choices, user search, and grouped history deletion. Illustration and novel summaries include restriction and AI flags so the active search filter can select visible results. A filtered empty page follows the server cursor until visible results appear or pagination ends.

## Verification

The authenticated search probe requests autocomplete, filter choices, and all three result types and writes their counts to a repeatable local artifact. An isolated real SQLite probe verifies timestamp ordering, repeat searches, pinned history, and grouped deletion. The macOS build compiles the search page and generated Rust bridge.
