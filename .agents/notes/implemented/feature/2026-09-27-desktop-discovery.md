# Agent Note: Desktop discovery page

Status: implemented

English | [中文](2026-09-27-desktop-discovery.zh.md)

## Problem

The Flutter discovery entry shows only an illustration ranking. The Compose page also exposes trending tags, illustration and novel rankings with mode and date controls, and Pixivision articles. Keeping these entries together matters because users browse them as one discovery page.

## Decision

The Flutter discovery entry presents the same sections and ranking modes as the Compose page. Each ranking keeps its own selected mode, date, pagination cursor, and scroll position. The global R18 setting controls which ranking modes are available. Pixivision has an article preview in discovery and a separate illustration and manga article page. Article links open in the system browser. The Rust core owns Pixiv API requests and response conversion; Flutter owns page state and presentation.

## Alternatives considered

**Keep the ranking-only page.** This leaves the other discovery entries inaccessible from their established location and makes the two desktop clients behave differently.

**Embed the full article lists in discovery.** The established page uses a short preview and a separate article page. Keeping that structure preserves the browsing flow and independent article pagination.

## Consequences

The discovery page requests trending tags, both ranking types, and the article preview when opened. Ranking panels use bounded heights within the page scroll view, and each panel paginates independently. The Rust image client fetches tag and article thumbnails through the same CDN path as work images.

## Verification

The authenticated discovery probe requests all four first pages and fetches a tag thumbnail and an article thumbnail. The macOS build compiles the Flutter page and generated Rust bridge.
