# Agent Note: 双语文档契约

Status: implemented

[English](2026-08-22-project-documentation-bilingual.md) | 中文

## Problem

仓库的文档模板提供双语契约，但初次重建选择了英文单语模式，并从活动目录中移除了中文对应模板。因此仓库没有中文 README，当前文档也没有可以机械验证的配对文件。

## Decision

活动文档库使用内置的中英文配对契约。`docs/` 和 `.agents/notes/` 下的当前活动 Markdown 文档，以及根 README，都提供同等权威的英文和简体中文对应文件，并配有记录 blob hash 的 `.i18n.yaml` sidecar。指令文件、术语表和风格样例、机器消费的翻译 prompt，以及冻结的归档记录等模板排除项仍不属于普通配对范围。

历史 Superpowers 语料保持原样，位于 `archive/superpowers/`。它属于历史实现资料而不是当前参考文档，因此不纳入活动文档配对范围。活动归档说明会链接到它；当前行为仍由配对的架构、开发、cookbook 和子系统页面负责。

## Alternatives considered

**继续使用英文单语模式。** 这样可以避免翻译工作，但会违背仓库内置的默认契约，也无法满足维护者需要中文文档的要求。

**翻译每一份归档 Superpowers 文件。** 这套历史计划和规格文档约有 10,579 行。翻译它们会扩大评审范围，却不会让它们成为当前权威。

**使用语言目录或中英混排单文件。** 内置契约要求同目录的 `.md`、`.zh.md` 和 `.i18n.yaml` 文件，因此其他布局会绕过仓库验证器，也会让更新更难评审。

## Consequences

以后新增活动文档时，必须同时创建或更新英文和中文配对文件，并在语义评审后刷新 sidecar。中文侧指向活动双语文档时，必须链接到中文对应文件。历史 Superpowers 资料仍可供参考，但不需要翻译或 sidecar。

`docs/i18n/terminology.md` 下的项目术语表是稳定翻译选择的真源。结构验证只能证明配对机制正确；翻译含义、术语和技术准确性仍由人工评审负责。

## Verification

使用 `verify_project_docs.py` 检查结构；评审变更后的配对后使用 `refresh_i18n_sidecars.py --write`；使用 `lint_prose.py` 检查活动正文重复；使用 `audit_template_policy.py` 和 `verify_template_integrity.py` 检查模板完整性；使用 `git diff --check` 检查空白字符错误。
