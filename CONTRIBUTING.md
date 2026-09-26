# Contributing to AID / 贡献指南

欢迎中文与英文贡献。You can help with reproducible bug reports, documentation, translations, focused fixes and integrations based on public provider documentation.

Please follow the [code of conduct](CODE_OF_CONDUCT.md). For usage questions, see [support](SUPPORT.md). Report vulnerabilities through the [security policy](SECURITY.md), not public issues.

## Before making a change / 开始之前

1. Search [existing issues](https://github.com/gzxx-2025/aid-studio/issues) to avoid duplicate work.
2. For a large feature, open an issue describing the user need, proposed scope and compatibility impact before implementing it.
3. Fork the public repository, create a focused branch from its default branch and keep unrelated changes out of the PR.
4. Use only source code, examples and media you have permission to contribute. Never include real API keys, accounts, database exports, private prompts, signed media URLs or deployment configuration.

先搜索已有问题，大功能先讨论范围，再从公开仓默认分支创建贡献分支。每个 PR 聚焦一项问题，并保留第三方版权与许可声明。

## Development / 开发

Use the setup instructions in [README](README.md#本地开发), [English README](README.en.md#source-layout-and-development) and the [deployment guide](deploy/README.md). Run commands only in your own development environment.

- **Backend:** Java 17 and Maven. Controllers belong in `aid-business`, domain services and persistence in `aid-interface`, and shared infrastructure in `aid-common`.
- **Frontends:** the public repository places the admin interface in `frontend/admin` and creator interface in `frontend/web`. Use the Node version, lockfile and scripts declared by each package.
- **Provider integrations:** follow the [public provider integration guide](doc/public-provider-integration.md) / [公开 Provider 接入指南](doc/公开Provider接入指南.md). Link to the official protocol documentation. Reuse existing provider interfaces, task scheduling, capability validation and billing; do not create a separate task or balance system for one provider.
- **Database changes:** support MySQL 5.7, keep migrations repeatable, preserve existing user settings and document initialization and upgrade effects.
- **Translations:** keep Chinese and English setup requirements, limitations, commands and links consistent. Do not claim that a feature is implemented or verified without evidence.

## Validate the change / 验证

Choose checks that match the affected files and describe the results in the PR:

| Change | Expected evidence |
| --- | --- |
| Documentation or templates | Valid relative links, images, anchors, Markdown or YAML; preview the changed content |
| Java | Relevant reproduction and `mvn clean package -DskipTests`; packaging alone does not demonstrate behavior |
| Frontend | `npm ci`, relevant package checks and its production build; the creator interface uses `npm run generate` |
| SQL | First execution and repeat execution in an isolated MySQL 5.7 database; verify existing data is preserved |
| Deployment scripts | Syntax checks and relevant installation, upgrade and failure-recovery checks in an isolated environment |

Document checks you could not run and why. Remove temporary test harnesses, logs, downloads, build output and local environment files before submitting. Do not submit a `tests/` directory in the creator interface. Document reproduction steps and validation evidence in the PR instead.

仅文档变更无需编译应用；按实际影响验证。请如实写明已执行和未执行的检查，不提交临时测试文件、日志、构建产物或个人配置。

## Submit a pull request / 提交 PR

- Explain the problem, resulting behavior and affected components; link the related issue.
- Include screenshots for visible changes and reproduction steps for fixes. Redact personal data and credentials.
- Explain compatibility, configuration or migration requirements when applicable.
- Include user-facing documentation updates. For changes that affect release notes, use the single `## 未发布` section at the top of `version/version.md`; do not create a release version or tag in a PR.
- Run `git diff --check`, inspect the full diff and submit a focused PR to the public repository's default branch.

Maintainers review contributions and coordinate releases. A merged PR is not itself a released version; users should consult the [release page](https://github.com/gzxx-2025/aid-studio/releases).
