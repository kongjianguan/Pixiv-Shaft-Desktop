# Agent Note: Bilingual documentation contract

Status: implemented

English | [中文](2026-08-22-project-documentation-bilingual.zh.md)

## Problem

The repository's documentation templates provide a bilingual contract, but the initial bootstrap selected English-only mode and removed the Chinese counterpart templates from the active tree. That left the repository without the Chinese README and without a mechanically verifiable pair for current documentation.

## Decision

The active documentation library uses the bundled English/Chinese pairing contract. Current active Markdown documents under `docs/` and `.agents/notes/`, together with the root README, carry equal-authority English and Simplified Chinese counterparts and an `.i18n.yaml` blob-hash sidecar. Template exclusions remain outside ordinary pair scope, including instruction files, terminology/style samples, machine-consumed translation prompts, and frozen archived notes.

The historical Superpowers corpus remains unchanged under `archive/superpowers/`. It is outside the active documentation pair scope because it is historical implementation material rather than current reference documentation. The active archive explanation links to it, while current behavior remains owned by the paired architecture, development, cookbook, and subsystem pages.

## Alternatives considered

**Keep English-only mode.** This would avoid translation work, but it would contradict the repository's bundled default contract and the maintainer's need for Chinese documentation.

**Translate every archived Superpowers file.** The corpus is about 10,579 lines of historical plans and specifications. Translating it would add a large review surface without making it current authority.

**Use locale directories or bilingual single files.** The bundled contract requires sibling `.md`, `.zh.md`, and `.i18n.yaml` files, so other layouts would bypass the repository's verifier and make updates harder to review.

## Consequences

Every new active documentation page must be created or updated as an English/Chinese pair and its sidecar must be refreshed after semantic review. Links from the Chinese side use Chinese counterparts for active bilingual documents. Historical Superpowers material remains available for reference but does not need translation or sidecars.

The project terminology table under `docs/i18n/terminology.md` is the source of truth for durable translation choices. Structural verification proves pairing mechanics, while human review remains responsible for translation meaning, terminology, and technical accuracy.

## Verification

Run `verify_project_docs.py` for structure, `refresh_i18n_sidecars.py --write` after reviewing changed pairs, `lint_prose.py` for duplicate active prose, `audit_template_policy.py` and `verify_template_integrity.py` for template integrity, and `git diff --check` for whitespace errors.
