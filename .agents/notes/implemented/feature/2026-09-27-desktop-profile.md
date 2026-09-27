# Agent Note: Desktop profile pages

Status: implemented

English | [中文](2026-09-27-desktop-profile.zh.md)

## Problem

The Flutter personal page exposes only illustration works and first-page bookmark results. Author pages show works without public bookmarks. These views cannot show later pages or the personal novel works available in the Compose client.

## Decision

Personal illustration bookmarks, novel bookmarks, published illustrations, and published novels use cursor-based lists through the Rust API. The existing four personal tabs remain, and the published-works tab has illustration and novel choices. The profile header provides following, followers, and MyPixiv entries through the existing user-list page. An author page has works and public-bookmark tabs. The logout action lives in the account section of settings, which is reachable from the macOS menu and the personal header.

## Alternatives considered

**Keep first-page lists.** Users with more than one response page could not reach older works and bookmarks.

**Keep logout in the profile header.** The Compose client groups account actions in settings. Keeping the action in two places would add a redundant profile control.

## Consequences

The Rust profile list methods return server cursors as well as items. Profile and author pages use the shared paged grid and inherit its empty-page handling. Switching between published illustration and novel lists retains each loaded page while the personal page remains open.

## Verification

The authenticated profile probe fetches profile data, the avatar, both bookmark types, both published-work types, and public author bookmarks. The macOS build compiles the profile pages and generated bridge.
