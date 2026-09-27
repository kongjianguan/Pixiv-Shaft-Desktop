# Agent Note: Desktop home controls

Status: implemented

English | [中文](2026-09-27-home-controls.zh.md)

## Problem

The full-window feed needs the compact, discoverable mode switch used by the Compose client. The application also needs a system status-bar entry that can reopen its hidden main window or exit the process.

## Decision

The Flutter home feed places four content-sized pills at the top center, with the Compose spacing, typography, selected colors, and rounded surface. The pills start visible, hide after the pointer leaves, and reappear when the pointer reaches the top edge. They use the feed's existing `TabController`, so clicks and page swipes share one selection state. The macOS Runner owns an AppKit template status item with the circular P mark and Show / Exit actions. Closing the main window hides it while retaining the Flutter page state. The status item stays available while the application runs.

## Alternatives considered

**Flutter `TabBar`.** Its built-in tab height, padding, and indicator geometry differ from the compact Compose control.

**`tray_manager` package.** The macOS Runner already handles the main window lifecycle. AppKit can create the status item and menu in the same place without a Dart-to-native window coordination path.

## Consequences

The status item appears on application launch and remains after reopening the window. The hidden window can be recovered from Show, and Exit terminates the application. The top-edge hover region remains twenty logical pixels high; the transparent pill control releases pointer input while hidden.

## Verification

The macOS Flutter integration test checks the four labels, compact centered geometry, selection changes, and hover visibility. The release build compiles the AppKit status item and packages the application.
