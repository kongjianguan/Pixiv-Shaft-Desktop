# Agent Note: Full-height macOS window content

Status: implemented

English | [中文](2026-09-27-full-height-window.zh.md)

## Problem

The macOS title bar occupies a separate empty row above the full-window feed. Extending Flutter content into that row also places native window buttons above Flutter controls, so navigation and pointer handling need explicit safe regions.

## Decision

The Runner enables AppKit's full-size content view, transparent title bar, and hidden title text while retaining native window buttons and the existing close-to-status-item behavior. The recommended feed renders from the top of the window, and its compact mode control sits in the title-bar area. `macos_window_utils` supplies the title-bar height and native pointer pass-through for the Flutter mode control. The pass-through surface exists only while the recommended page is active and at the top of navigation. The root `MediaQuery` carries the measured top safe inset for routes with an app bar. The other main sections use that inset for their own controls, while the recommended feed paints behind the native title bar. A minimum window width keeps the centered mode control clear of the native buttons.

## Alternatives considered

**A separate title-bar row.** It reserves vertical space without contributing to the feed and keeps the mode control below the window buttons.

**An AppKit-only mode switch.** It would require duplicate selection state and bidirectional synchronization with Flutter's page controller. The Flutter control remains the single owner of tab state.

## Consequences

Artwork can occupy the full window height, including the space behind the native controls. Search, profile, discovery, dynamic, login, and detail controls remain below the title-bar safe region. The native buttons remain available for closing, minimizing, and resizing the window. Full-screen and resize changes update the safe inset from the current AppKit window geometry.

## Verification

The macOS integration test checks that the mode control intersects the measured title-bar region, stays selectable, and that the login app bar begins below the safe inset. The packaged application is checked for native window buttons, pointer interaction, and window close behavior.
