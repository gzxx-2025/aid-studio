'use client'

import { Button, Input, Tooltip } from 'antd'
import { LoadingOutlined, PlusOutlined, RightOutlined, UserOutlined } from '@ant-design/icons'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { AssetCardCancelIcon } from '~/components/common/AssetCardCancelIcon'
import { BillingQuoteConfirm } from '~/components/common/BillingQuoteConfirm'
import { shouldShowAssetImageRegenerateAction } from '~/utils/assetImageActionMode'
import { STEP3_EDIT_IMAGE_GENERATING_TOOLTIP } from '~/utils/step3EditImageGate'
import { getCharacterName, getCharacterPrefix, getFormName } from './scpRowUtils'
import { iconAutGenerate, iconDownload, iconEmptyFail, iconManual, iconPreview, iconReplace } from './scpIcons'
import { resolveScpFormImageBillingRequest } from './scpBillingQuote'
import { ScpPendingFormStrip } from './ScpPendingFormStrip'
import type { ScpCtx } from './types'

/** 角色 Tab：待生成形态横条 + 角色卡片（含形态列表 / 配音 / 形态图） */
export function ScpCharacterListView({ ctx }: { ctx: ScpCtx }) {
  const characterForms = ctx.characterForms.value
  const characterFormImages = ctx.characterFormImages.value
  const characterFormGenerationStatus = ctx.characterFormGenerationStatus.value
  const playingVoicePreviewKey = ctx.playingVoicePreviewKey.value

  return (
    <div className="character-generation-view">
      <ScpPendingFormStrip
        ctx={ctx}
        cards={ctx.pendingCharacterFormCards()}
        keyPrefix="pending-character"
        hint="以下角色需先「生成形态」后再编辑详情与配图"
        titleIcon={UserOutlined}
      />
      {/* 角色列表（顶栏说明文案在 .scp-topbar-hint） */}
      <div className="character-cards-container">
        {ctx.visibleManualCharactersList().length > 0 ? (
          <div className="character-cards-manual">
            {ctx.visibleManualCharactersList().map((character) => (
              <div
                key={`manual-character-${character.index}`}
                className="character-card-wrapper"
                data-scp-asset-tab="character"
                data-scp-asset-index={character.index}
              >
                {/* 头部横条：和卡片同级，占满一行 */}
                <div className="character-card-header-bar">
                  <div className="character-card-title-wrapper">
                    <span className="character-card-title-prefix">
                      {getCharacterPrefix(character.name)}
                    </span>
                    {ctx.editingCharacterIndex.value === character.index ? (
                      <Input
                        value={ctx.editingCharacterName.value}
                        onChange={(e) => ctx.editingCharacterName.set(e.target.value)}
                        size="small"
                        className="character-name-input"
                        onBlur={() => ctx.handleCharacterNameBlur(character.index)}
                        onPressEnter={() => ctx.handleCharacterNameBlur(character.index)}
                        onClick={(e) => e.stopPropagation()}
                      />
                    ) : (
                      <span
                        className="character-card-title-editable"
                        onClick={() => ctx.startEditCharacterName(character.index, character.name)}
                      >
                        {getCharacterName(character.name)}
                      </span>
                    )}
                  </div>
                  <div className="character-card-actions-header">
                    <Button size="small" danger onClick={() => ctx.removeCharacter(character.index)}>
                      删除角色
                    </Button>
                  </div>
                </div>

                {/* 形态列表 */}
                {characterForms[character.index] && characterForms[character.index].length > 0 ? (
                  <div className="character-forms-list">
                    {characterForms[character.index].map((form, formIndex) => (
                      <div key={`form-${character.index}-${formIndex}`} className="character-form-item">
                        <div className="character-form-header">
                          <div className="character-form-title-wrapper">
                            {ctx.editingFormIndex.value === `${character.index}-${formIndex}` ? (
                              <Input
                                value={ctx.editingFormName.value}
                                onChange={(e) => ctx.editingFormName.set(e.target.value)}
                                size="small"
                                className="form-name-input"
                                onBlur={() => ctx.handleFormNameBlur(character.index, formIndex)}
                                onPressEnter={() => ctx.handleFormNameBlur(character.index, formIndex)}
                                onClick={(e) => e.stopPropagation()}
                              />
                            ) : (
                              <span
                                className="character-form-title-editable"
                                onClick={() =>
                                  ctx.startEditFormName(character.index, formIndex, form.name)
                                }
                              >
                                {getFormName(form.name)}
                              </span>
                            )}
                            <div className="character-form-voiceover">
                              <span className="voiceover-label">配音:</span>
                              <Button
                                type="text"
                                size="small"
                                className="voiceover-btn"
                                onClick={() => ctx.openVoiceTimbrePicker(character.index, formIndex)}
                              >
                                {form.voiceover || '无配音'}
                                <RightOutlined />
                              </Button>
                              {form.voiceover ? (
                                <Button
                                  type="text"
                                  size="small"
                                  className={`voice-preview-btn${
                                    playingVoicePreviewKey === `${character.index}-${formIndex}`
                                      ? ' is-playing'
                                      : ''
                                  }`}
                                  onClick={() => ctx.toggleVoicePreview(character.index, formIndex)}
                                >
                                  {playingVoicePreviewKey === `${character.index}-${formIndex}` ? (
                                    <span className="voice-preview-eq" aria-hidden="true">
                                      <span className="voice-preview-eq-bar voice-preview-eq-bar-1" />
                                      <span className="voice-preview-eq-bar voice-preview-eq-bar-2" />
                                      <span className="voice-preview-eq-bar voice-preview-eq-bar-3" />
                                    </span>
                                  ) : null}
                                  {playingVoicePreviewKey === `${character.index}-${formIndex}`
                                    ? '播放中'
                                    : '试听'}
                                </Button>
                              ) : null}
                            </div>
                          </div>

                          <div className="character-form-actions">
                            <Button
                              size="small"
                              onClick={() =>
                                ctx.handleEditCharacterFormSetting(character.index, formIndex)
                              }
                            >
                              形态图设定
                            </Button>
                            {ctx.isCharacterFormEditImageDisabled(character.index, formIndex) ? (
                              <Tooltip
                                title={STEP3_EDIT_IMAGE_GENERATING_TOOLTIP}
                                mouseEnterDelay={0.2}
                              >
                                <span className="scp-edit-image-btn-wrap">
                                  <Button size="small" disabled>
                                    编辑形态图
                                  </Button>
                                </span>
                              </Tooltip>
                            ) : (
                              <Button
                                size="small"
                                onClick={() =>
                                  ctx.handleEditCharacterFormImage(character.index, formIndex)
                                }
                              >
                                编辑形态图
                              </Button>
                            )}
                            <Button
                              size="small"
                              onClick={() => ctx.handleCopyCharacterForm(character.index, formIndex)}
                            >
                              复制形态
                            </Button>
                            <Button
                              size="small"
                              danger
                              onClick={() => ctx.handleDeleteCharacterForm(character.index, formIndex)}
                            >
                              删除形态
                            </Button>
                          </div>
                        </div>
                        <div className="character-form-content">
                          {characterFormGenerationStatus[`${character.index}-${formIndex}`] ===
                          'generating' ? (
                            <div
                              className="asset-visual-generating-block"
                              role="status"
                              aria-live="polite"
                            >
                              <div
                                className="asset-visual-generating-block__shimmer"
                                aria-hidden="true"
                              />
                              <LoadingOutlined spin className="asset-visual-generating-block__icon" />
                              <p className="asset-visual-generating-block__text">
                                {ctx.characterFormGeneratingLabel(`${character.index}-${formIndex}`)}
                              </p>
                            </div>
                          ) : characterFormGenerationStatus[`${character.index}-${formIndex}`] ===
                            'failed' ? (
                            <div className="scene-card scene-card-failed character-form-generate-failed">
                              <div className="scene-card-failed-content">
                                <div className="scene-card-failed-icon">
                                  <img
                                    src={iconEmptyFail}
                                    alt=""
                                    className="scene-card-failed-icon-image"
                                  />
                                </div>
                                <div className="scene-card-failed-text">生成失败</div>
                                <BillingQuoteConfirm
                                  disabled={
                                    !ctx.canAutoGenerateCharacterFormImage(character.index, formIndex)
                                  }
                                  title="确认重新生成该角色形态图？"
                                  resolveRequest={() =>
                                    resolveScpFormImageBillingRequest(
                                      ctx,
                                      'character',
                                      character.index,
                                      formIndex
                                    )
                                  }
                                  onConfirm={() =>
                                    ctx.handleAutoGenerateCharacterForm(character.index, formIndex)
                                  }
                                >
                                  <Button
                                    type="primary"
                                    className="scene-card-failed-retry"
                                    disabled={
                                      !ctx.canAutoGenerateCharacterFormImage(
                                        character.index,
                                        formIndex
                                      )
                                    }
                                  >
                                    重新生成
                                  </Button>
                                </BillingQuoteConfirm>
                              </div>
                            </div>
                          ) : characterFormImages[`${character.index}-${formIndex}`] &&
                            characterFormImages[`${character.index}-${formIndex}`].length > 0 ? (
                            /* 如果有图片，显示图片列表 */
                            <div className="scene-card-list character-form-images-list">
                              {characterFormImages[`${character.index}-${formIndex}`].map(
                                (img: any, imgIdx: number) => (
                                  <div
                                    key={`character-form-${character.index}-${formIndex}-img-${imgIdx}`}
                                    className="scene-card scene-card-auto"
                                  >
                                    <div className="scene-card-header-with-image">
                                      {ctx.editingImageTitleIndex.value ===
                                      `character-form-${character.index}-${formIndex}-${imgIdx}` ? (
                                        <Input
                                          value={ctx.editingImageTitle.value}
                                          onChange={(e) => ctx.editingImageTitle.set(e.target.value)}
                                          size="small"
                                          className="image-title-input"
                                          onBlur={() =>
                                            ctx.handleCharacterFormImageTitleBlur(
                                              character.index,
                                              formIndex,
                                              imgIdx
                                            )
                                          }
                                          onPressEnter={() =>
                                            ctx.handleCharacterFormImageTitleBlur(
                                              character.index,
                                              formIndex,
                                              imgIdx
                                            )
                                          }
                                          onClick={(e) => e.stopPropagation()}
                                        />
                                      ) : (
                                        <span
                                          className="scene-card-title-editable"
                                          onClick={() =>
                                            ctx.startEditCharacterFormImageTitle(
                                              character.index,
                                              formIndex,
                                              imgIdx,
                                              img.title || `形态图${imgIdx + 1}`
                                            )
                                          }
                                        >
                                          {img.title || `形态图${imgIdx + 1}`}
                                        </span>
                                      )}
                                      <AssetCardCancelIcon
                                        label="取消角色"
                                        onClick={() =>
                                          ctx.handleDeleteCharacterFormImageByIndex(
                                            character.index,
                                            formIndex,
                                            imgIdx
                                          )
                                        }
                                      />
                                    </div>
                                    <div className="scene-card-image-body">
                                      <ShimmerImage
                                        src={img.url}
                                        imgClass="scene-main-image"
                                        objectFit="contain"
                                        revealDirection="fade"
                                        onClick={() =>
                                          ctx.handleEditCharacterFormImageWithIndex(
                                            character.index,
                                            formIndex,
                                            imgIdx
                                          )
                                        }
                                      />
                                    </div>
                                    <div className="scene-card-image-footer asset-action-footer">
                                      <Button
                                        icon={
                                          <img src={iconPreview} alt="" className="footer-action-icon" />
                                        }
                                        onClick={() =>
                                          ctx.handlePreviewCharacterFormImageByIndex(
                                            character.index,
                                            formIndex,
                                            imgIdx
                                          )
                                        }
                                      >
                                        预览
                                      </Button>
                                      {shouldShowAssetImageRegenerateAction(
                                        img,
                                        ctx.canAutoGenerateCharacterFormImage(
                                          character.index,
                                          formIndex
                                        )
                                      ) ? (
                                        <BillingQuoteConfirm
                                          title="确认重新生成该角色形态图？"
                                          resolveRequest={() =>
                                            resolveScpFormImageBillingRequest(
                                              ctx,
                                              'character',
                                              character.index,
                                              formIndex
                                            )
                                          }
                                          onConfirm={() =>
                                            ctx.handleCharacterFormImageMiddleActionByIndex(
                                              character.index,
                                              formIndex,
                                              imgIdx
                                            )
                                          }
                                        >
                                          <Button
                                            icon={
                                              <img
                                                src={iconAutGenerate}
                                                alt=""
                                                className="footer-action-icon"
                                              />
                                            }
                                          >
                                            重新生成
                                          </Button>
                                        </BillingQuoteConfirm>
                                      ) : (
                                        <Button
                                          icon={
                                            <img
                                              src={iconReplace}
                                              alt=""
                                              className="footer-action-icon"
                                            />
                                          }
                                          onClick={() =>
                                            ctx.handleCharacterFormImageMiddleActionByIndex(
                                              character.index,
                                              formIndex,
                                              imgIdx
                                            )
                                          }
                                        >
                                          替换
                                        </Button>
                                      )}
                                      <Button
                                        icon={
                                          <img src={iconDownload} alt="" className="footer-action-icon" />
                                        }
                                        onClick={() =>
                                          ctx.handleDownloadCharacterFormImageByIndex(
                                            character.index,
                                            formIndex,
                                            imgIdx
                                          )
                                        }
                                      >
                                        下载
                                      </Button>
                                    </div>
                                  </div>
                                )
                              )}
                            </div>
                          ) : (
                            /* 没有图片时显示按钮 */
                            <div className="character-form-card manual-generate-card">
                              <div className="asset-generate-card__actions">
                                <BillingQuoteConfirm
                                  disabled={
                                    !ctx.canAutoGenerateCharacterFormImage(character.index, formIndex) ||
                                    characterFormGenerationStatus[`${character.index}-${formIndex}`] ===
                                      'generating'
                                  }
                                  title="确认生成该角色形态图？"
                                  resolveRequest={() =>
                                    resolveScpFormImageBillingRequest(
                                      ctx,
                                      'character',
                                      character.index,
                                      formIndex
                                    )
                                  }
                                  onConfirm={() =>
                                    ctx.handleAutoGenerateCharacterForm(character.index, formIndex)
                                  }
                                >
                                  <Button
                                    type="primary"
                                    className="asset-generate-card__action asset-generate-card__action--primary"
                                    disabled={
                                      !ctx.canAutoGenerateCharacterFormImage(
                                        character.index,
                                        formIndex
                                      ) ||
                                      characterFormGenerationStatus[
                                        `${character.index}-${formIndex}`
                                      ] === 'generating'
                                    }
                                  >
                                    <img
                                      src={iconAutGenerate}
                                      alt=""
                                      className="asset-generate-card__action-icon"
                                      aria-hidden="true"
                                    />
                                    自动生成
                                  </Button>
                                </BillingQuoteConfirm>
                                <div className="asset-generate-card__or">或</div>
                                <Button
                                  className="asset-generate-card__action"
                                  onClick={() =>
                                    ctx.handleImportCharacterFormImage(character.index, formIndex)
                                  }
                                >
                                  <img
                                    src={iconManual}
                                    alt=""
                                    className="asset-generate-card__action-icon"
                                    aria-hidden="true"
                                  />
                                  图片导入
                                </Button>
                              </div>
                            </div>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                ) : null}

                {/* 新增形态按钮 */}
                <div className="character-add-form">
                  <Button
                    type="primary"
                    icon={<PlusOutlined />}
                    onClick={() => ctx.handleAddCharacterForm(character.index)}
                  >
                    <div className="text-gradient">新增形态</div>
                  </Button>
                </div>
              </div>
            ))}
          </div>
        ) : null}
      </div>
    </div>
  )
}
