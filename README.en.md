# AID Studio — AI Drama & Motion Comic Creation

[简体中文](README.md) | **English**

**Open-source, self-hosted AI drama and motion comic creation — from scripts and storyboards to video and voiceovers, with support for films and comics.**

[Live website](https://www.aidstudio.com.cn/) · [Quick start](#quick-start) · [Releases](https://github.com/gzxx-2025/aid-studio/releases) · [Help](SUPPORT.md) · [Contribute](CONTRIBUTING.md)

[![MIT License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Java 17](https://img.shields.io/badge/Java-17-orange.svg)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg)
![MySQL 5.7](https://img.shields.io/badge/MySQL-5.7-4479A1.svg)

![AID workflow canvas for scripts, assets, storyboards and video production](references/web/2.png)

### Community chat

Chinese-language open-source discussion and technical support, with no advertising or hidden charges. You can also use [GitHub Issues](https://github.com/gzxx-2025/aid-studio/issues/new/choose) in Chinese or English.

<p align="center">
  <a href="references/community-qr.png">
    <img src="references/community-qr.png" alt="QR code for the AID open-source community chat" width="220">
  </a><br>
  <sub>Click the QR code to open the original image.</sub>
</p>

## At a glance

- **One production workflow:** scripts, assets, storyboards, images, video and voiceovers in one project.
- **Multiple models:** choose configured text, image, video and audio models for each task.
- **Self-hosted:** Docker or systemd deployment with model, task and user management.

[Your first project](#your-first-project) · [Installation](#quick-start) · [Product screenshots](README.md#产品预览) · [Source layout](#source-layout-and-development)

The screenshots show the Chinese interface. This guide is in English. Full Docker and systemd install steps are in the [English deployment guide](deploy/README.en.md). Screen-by-screen usage tutorials remain in Chinese.

## What you can create

- **AI short dramas and animated comics:** organize episodes, recurring characters, storyboards, video clips and voiceovers.
- **AI films and trailers:** plan shots, keep reference assets together and manage generated footage.
- **AI comics and manga:** develop scripts, character references, scenes and sequential images within one project.

```text
Story & script → Episodes → Characters, props & scenes
             → Storyboards → Images → Video → Voiceover → Final preview
```

Two workflow interfaces are available: a step-by-step creation workflow and a connected workflow canvas. They offer different ways to organize production.

| Step-by-step storyboarding | Workflow canvas |
| --- | --- |
| ![Storyboard planning](references/web/7.png) | ![Connected workflow canvas](references/web/2.png) |

## Your first project

On a self-hosted installation, [configure and enable the required models](#3-open-the-application-and-configure-models) first. Start with one storyboard before generating a full episode.

1. Choose a creation direction and enter a short story or script.
2. Set up one episode and a small set of characters, props and scenes.
3. Generate or upload reference images that you are authorized to use.
4. Create a storyboard and review its script and image before generating video.
5. Select a configured video model and inspect its parameters and quoted cost.
6. Add voiceovers with a configured speech model, then review the output.

For an image-only comic, focus on scripts, reference assets and storyboard images. You do not need to generate video for every project. See the [usage tutorials (Chinese)](https://gzxxaitdb.feishu.cn/docx/LZ5zdesEgo1z4Mxc7OWc7zTHnJc) for screen-by-screen instructions.

## Features

<details>
<summary>Explore the full feature list</summary>

- **Script and project management:** scripts, episodes, assets, generation records and output previews.
- **Reusable references:** characters, props, scenes and styles to support visual consistency. Results depend on the chosen models and inputs.
- **Image generation:** text-to-image, image-to-image and reference-based editing where supported by the selected model.
- **Video generation:** image-to-video, start/end frames and multiple references where supported by the selected model.
- **Voice production:** text-to-speech, voice selection and lip-sync workflows with compatible providers.
- **Multiple providers:** configure text, image, video and audio models with their capabilities and credentials.
- **Task management:** queued work, concurrency controls, progress tracking, retry and result collection.
- **Administration:** users, models, suppliers, task monitoring, billing, payments, storage and configuration.
- **Self-hosted deployment:** Docker or systemd, backups, HTTPS configuration and an independent updater.

</details>

The source code is provided under the MIT license. AI generation requires credentials for suitable providers. Model calls, servers and storage may incur charges. Capabilities and output quality depend on the models configured by the administrator.

## Quick start

### 1. Prepare a server

Use a fresh 64-bit Linux server with administrator access. For an installation with local middleware and no RocketMQ, the documented minimum is **2 CPU cores, 4 GB RAM and 40 GB disk**; the recommended configuration is **4 CPU cores, 8 GB RAM and 100 GB or more disk**. Source compilation needs network access and additional temporary disk space. Installation time varies by hardware and network.

Docker is the recommended deployment mode. The installer also supports systemd deployments. See the [English deployment guide](deploy/README.en.md) for install commands, config paths, first confirmation, `aid` commands, HTTPS and external MySQL/Redis/RocketMQ. The [Chinese deployment guide](deploy/README.md) is the original.

### 2. Download and review the installer

Run the following in a Bash shell on the target server. The script is saved before execution so you can inspect it.

```bash
curl -fL --retry 3 --connect-timeout 15 \
  -o aid-install.sh \
  https://raw.githubusercontent.com/gzxx-2025/aid-studio/master/deploy/aid.sh
```

If GitHub is inaccessible, download the same installer from Gitee instead:

```bash
curl -fL --retry 3 --connect-timeout 15 \
  -o aid-install.sh \
  https://gitee.com/gzxx-2025/aid-studio/raw/master/deploy/aid.sh
```

Continue only after a successful download. Review `aid-install.sh`, then run:

```bash
sudo env AID_REMOTE_BOOTSTRAP=1 AID_RELEASE_CHANNEL=auto bash ./aid-install.sh install
```

`auto` prefers an available stable release. If no installable stable release exists, it falls back to Beta with a warning. Use `stable` to require a stable release or `beta` only when intentionally testing a prerelease. On an existing installation, `install` checks for updates; it does not create a second instance.

The installer asks you to review and confirm configuration before installing components or initializing the database. It builds the backend, admin interface, user interface and updater from one release tag. Default data is stored under `/data/aid`.

### 3. Open the application and configure models

1. Follow the installer output for the application and admin URLs. The admin path contains a generated access code.
2. Change the initial administrator password immediately. Use `sudo aid default` to redisplay the access instructions; its output may contain sensitive information.
3. In the admin interface, configure provider credentials and the text, image, video and audio models needed by your workflow. Review model capabilities and billing before enabling them.
4. Configure storage and the public URLs needed for media access. Follow the deployment guide when enabling HTTPS.
5. Create a small project and generate one storyboard before running a large batch.

The downloadable official media asset bundle is separate from source code and is not automatically included in installation. See [official assets](README.md#官方资产包) for availability and import instructions.


## Maintain your installation

| Command | Purpose |
| --- | --- |
| `sudo aid` | Open the management menu |
| `sudo aid status` | Check service status |
| `sudo aid logs` | Inspect logs locally; redact before sharing |
| `sudo aid config` | Review configuration |
| `sudo aid backup` | Create a backup |
| `sudo aid update` | Check and apply an update |
| `sudo aid progress` | View update progress |
| `sudo aid uninstall` | Stop AID; `--keep` retains data, `--purge` requires `DELETE-AID` |

Back up the database, configuration and media to a separate location before upgrading. Upgrades involve source compilation, database migrations and health checks; allow a maintenance window. Recovery options depend on the release and migration. Read the [English deployment guide](deploy/README.en.md) before upgrading or rolling back.

## Source layout and development

The public repository contains the backend at the root, the admin interface in `frontend/admin`, and the user interface in `frontend/web`. Releases use a single repository tag.

```text
aid-admin/       Spring Boot entry point and configuration
aid-business/    Controllers and web infrastructure
aid-interface/   Domain services, entities and persistence
aid-common/      Shared infrastructure
aid-consumer/    Message consumers
frontend/admin/  Admin interface
frontend/web/    Creator interface
deploy/         Installer, deployment configuration and Go updater
sql/            Database initialization and migrations
```

The backend uses Java 17, Spring Boot 3.5, MyBatis-Plus, MySQL 5.7 and Redis. RocketMQ is optional. See [local backend development](README.md#本地开发) and the [contribution guide](CONTRIBUTING.md) for setup, code boundaries and validation. Frontend dependencies and commands are defined in their respective `package.json` files.

## Help and contributions

- [Support](SUPPORT.md): installation, configuration and usage questions.
- [Issues](https://github.com/gzxx-2025/aid-studio/issues/new/choose): reproducible bugs and feature requests, in Chinese or English.
- [Contributing](CONTRIBUTING.md): documentation, translations, fixes and public model integrations.
- [Public provider integration guide](doc/public-provider-integration.md): text / image / video / audio entry points, capabilities, shared tasks and billing ([中文](doc/公开Provider接入指南.md)).
- [Code of conduct](CODE_OF_CONDUCT.md): expectations for community participation.
- [Security policy](SECURITY.md): report vulnerabilities privately.
- [Community chat](README.md#交流与反馈): Chinese-language open-source discussion and technical support, with no advertising or hidden charges.

If AID helps your work, consider starring the repository or sharing an authorized project example and what you learned.

## License and acknowledgements

[MIT License](LICENSE). Copyright (c) 2026 光子讯息(杭州)科技有限公司. Third-party attribution is preserved in [NOTICE](NOTICE).

Parts of the admin framework are based on [RuoYi-Vue](https://gitee.com/y_project/RuoYi-Vue), licensed under MIT. Thanks to the [Linux.do](https://linux.do/) community for supporting technical exchange and open-source projects.
