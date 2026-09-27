# Agent Note: Work visibility in the Flutter client

Status: implemented

English | [中文](2026-09-27-work-visibility.zh.md)

## Problem

The Flutter client stores the global R18 setting but its Rust list responses still include restricted works. Novel lists can also contain entries marked invisible by the service. Displaying those entries breaks the existing content policy and can lead to an unusable novel detail page.

## Decision

The Rust API layer filters illustration and novel lists when converting responses. It reads the current R18 setting for each page, removes items whose `x_restrict` is positive while R18 is disabled, and always removes novels whose `visible` field is false. Illustration and novel detail requests remain direct requests for an explicitly selected identifier. The Flutter home page reloads visited feeds when the R18 setting changes. The shared paged grid follows server cursors when a filtered page has no visible entries.

## Alternatives considered

**Filter separately in each page widget.** This would require every present and future list to repeat the same content rule, leaving omissions likely.

**Stop pagination after an empty filtered page.** The service can return a page containing only restricted entries while later pages contain visible works. Stopping would hide those later works.

## Consequences

List conversion consults the settings database for every response page. Changing the R18 setting rebuilds visited feeds and obtains newly eligible results. A sequence of fully filtered pages may issue several requests before the first visible work appears.

## Verification

The authenticated discovery probe compares a real R18 ranking response with the filtered Rust result and records the counts. The Rust compilation and macOS build check the API and Flutter integration.
