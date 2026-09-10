# Agent Note: 仓库文档库

Status: implemented

[English](2026-08-22-project-documentation-library.md) | 中文

## Problem

仓库原本有有用的架构说明和实现计划，但没有当前文档入口，也没有为持久事实划分所有权的边界。`docs/` 目录还被 Git 忽略，因此新增文档无法按正常方式评审和提交。

## Decision

初次重建建立了位于 `docs/` 和 `.agents/notes/` 下的项目文档库结构。当前架构、开发流程和子系统契约位于活动文档目录中。历史 Superpowers 计划和规格文档位于 `archive/superpowers/` 下，不作为当前权威。由于仓库当时没有完整的双语语料，初次重建暂时使用英文单语活动文档；这一选择已由[后续双语决策](2026-08-22-project-documentation-bilingual.md)取代。

根文档入口包括[架构地图](../../../../docs/architecture.zh.md)、[开发指南](../../../../docs/development.zh.md)、[子系统索引](../../../../docs/subsystems/README.zh.md)和[构建手册](../../../../docs/cookbook/build-and-package.zh.md)。`.agents/notes/` 生命周期目录继续保留，用于未来需要超出当前参考文档所能承载理由的决策。

## Alternatives considered

**删除历史 Superpowers 文档。** 这样可以进一步缩小活动树，但会丢失比较 Desktop 与 Android 参考实现时可能仍有帮助的实现理由。归档可以保留这些背景，同时避免把它们呈现为当前指南。

**翻译并配对整套历史语料。** 现有计划和规格文档是一套规模很大的混合语言语料，之前没有配对契约。初次重建时翻译它们会造成大规模语义迁移，也会让文档系统更难评审。

**继续忽略 `docs/`。** 这样可以保持之前仅本地可见的行为，但文档库会无法进入正常评审和提交流程。仓库现在会跟踪 `docs/` 下的文档文件。

## Consequences

当前文档必须链接到拥有事实的源文件或子系统，而不是复制历史计划。历史 Superpowers 文件仍可供参考，但当前行为发生变化时，应更新活动文档和代码，而不是更新归档计划。初次重建采用的英文单语模式已被明确取代，双语契约及其迁移范围记录在后续决策中。

## Supersession

英文单语重建决策已由[双语文档决策](2026-08-22-project-documentation-bilingual.md)取代。本记录仍作为初次建立文档库，以及选择归档而不是删除历史 Superpowers 语料的记录保留。

## Verification

使用 `audit_template_policy.py` 检查模板策略，使用 `verify_project_docs.py` 检查文档结构，使用 `lint_prose.py` 检查重复正文，并对每次文档变更运行 `git diff --check`。
