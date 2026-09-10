# Agent Note: Repository documentation library

Status: implemented

English | [中文](2026-08-22-project-documentation-library.zh.md)

## Problem

The repository had useful architecture notes and implementation plans, but no current documentation entry point or ownership boundary for durable facts. The `docs/` tree was also ignored by Git, so new documentation could not be reviewed or committed normally.

## Decision

The initial bootstrap established the project documentation library structure under `docs/` and `.agents/notes/`. Current architecture, development procedures, and subsystem contracts live in the active documentation tree. Historical Superpowers plans and specifications live under `archive/superpowers/` and are not current authority. The bootstrap temporarily used English-only active docs because the existing repository had no coherent bilingual corpus; that choice is superseded by the bilingual maintenance decision in [the follow-up note](2026-08-22-project-documentation-bilingual.md).

The root documentation entry points are [the architecture map](../../../../docs/architecture.md), [the development guide](../../../../docs/development.md), [the subsystem index](../../../../docs/subsystems/README.md), and [the build cookbook](../../../../docs/cookbook/build-and-package.md). The `.agents/notes/` lifecycle directories remain available for future decisions that need rationale beyond the current reference docs.

## Alternatives considered

**Delete the historical Superpowers documents.** This would reduce the active tree further, but it would discard implementation rationale that may still help when comparing the Desktop port with the Android reference. Archiving preserves that context without presenting it as current guidance.

**Translate and pair the entire historical corpus.** The existing plans and specifications are a large mixed-language corpus with no prior pairing contract. Translating them during bootstrap would create a large semantic migration and make the documentation system harder to review.

**Keep `docs/` ignored.** This would preserve the previous local-only behavior, but it would make the documentation library invisible to normal review and commits. The repository now tracks documentation files under `docs/`.

## Consequences

Current documentation must link to the source file or subsystem that owns a fact instead of copying historical plans. Archived Superpowers files remain available for reference, but changes to current behavior update active docs and code rather than the archived plans. The initial English-only bootstrap was deliberately superseded rather than silently reversed; the bilingual contract and migration scope are recorded in the follow-up note.

## Supersession

The English-only bootstrap decision is superseded by [the bilingual documentation decision](2026-08-22-project-documentation-bilingual.md). This note remains as the record of the initial library setup and the decision to archive, rather than delete, the historical Superpowers corpus.

## Verification

The template policy is checked with `audit_template_policy.py`. The documentation structure is checked with `verify_project_docs.py`, duplicate prose is checked with `lint_prose.py`, and every documentation change is checked with `git diff --check`.
