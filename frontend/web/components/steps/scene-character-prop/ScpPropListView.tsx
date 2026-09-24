'use client'

import { Button, Input, Tooltip } from 'antd'
import { BlockOutlined, LoadingOutlined, PlusOutlined } from '@ant-design/icons'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { AssetCardCancelIcon } from '~/components/common/AssetCardCancelIcon'
import { BillingQuoteConfirm } from '~/components/common/BillingQuoteConfirm'
import { shouldShowAssetImageRegenerateAction } from '~/utils/assetImageActionMode'
import { STEP3_EDIT_IMAGE_GENERATING_TOOLTIP } from '~/utils/step3EditImageGate'
import { getPropFormName, getPropName, getPropPrefix } from './scpRowUtils'
import { iconAutGenerate, iconDownload, iconEmptyFail, iconManual, iconPreview, iconReplace } from './scpIcons'
import { resolveScpFormImageBillingRequest } from './scpBillingQuote'
import { ScpPendingFormStrip } from './ScpPendingFormStrip'
import type { ScpCtx } from './types'

/** 道具 Tab：待生成形态横条 + 道具卡片（含形态列表 / 形态图） */
export function ScpPropListView({ ctx }: { ctx: ScpCtx }) {
  const propForms = ctx.propForms.value
  const propFormImages = ctx.propFormImages.value
  const propFormGenerationStatus = ctx.propFormGenerationStatus.value

  return (
    <div className="prop-generation-view">
      <ScpPendingFormStrip
        ctx={ctx}
        cards={ctx.pendingPropFormCards()}
        keyPrefix="pending-prop"
        hint="以下道具需先「生成形态」后再编辑详情与配图"
        titleIcon={BlockOutlined}
      />
      {/* 道具列表（顶栏说明文案在 .scp-topbar-hint） */}
      <div className="prop-cards-container">
        {ctx.visibleManualPropsList().length > 0 ? (
          <div className="prop-cards-manual">
            {ctx.visibleManualPropsList().map((prop) => (
              <div
                key={`manual-prop-${prop.index}`}
                className="prop-card-wrapper"
                data-scp-asset-tab="prop"
                data-scp-asset-index={prop.index}
              >
                {/* 头部横条：和卡片同级，占满一行 */}
                <div className="prop-card-header-bar">
                  <div className="prop-card-title-wrapper">
                    <span className="prop-card-title-prefix">{getPropPrefix(prop.name)}</span>
                    {ctx.editingPropIndex.value === prop.index ? (
                      <Input
                        value={ctx.editingPropName.value}
                        onChange={(e) => ctx.editingPropName.set(e.target.value)}
                        size="small"
                        className="prop-name-input"
                        onBlur={() => ctx.handlePropNameBlur(prop.index)}
                        onPressEnter={() => ctx.handlePropNameBlur(prop.index)}
                        onClick={(e) => e.stopPropagation()}
                      />
                    ) : (
                      <span
                        className="prop-card-title-editable"
                        onClick={() => ctx.startEditPropName(prop.index, prop.name)}
                      >
                        {getPropName(prop.name)}
                      </span>
                    )}
                  </div>
                  <div className="prop-card-actions-header">
                    <Button size="small" danger onClick={() => ctx.removeProp(prop.index)}>
                      删除道具
                    </Button>
                  </div>
                </div>

                {/* 形态列表 */}
                {propForms[prop.index] && propForms[prop.index].length > 0 ? (
                  <div className="prop-forms-list">
                    {propForms[prop.index].map((form, formIndex) => (
                      <div key={`prop-form-${prop.index}-${formIndex}`} className="prop-form-item">
                        <div className="prop-form-header">
                          <div className="prop-form-title-wrapper">
                            {ctx.editingPropFormIndex.value === `${prop.index}-${formIndex}` ? (
                              <Input
                                value={ctx.editingPropFormName.value}
                                onChange={(e) => ctx.editingPropFormName.set(e.target.value)}
                                size="small"
                                className="prop-form-name-input"
                                onBlur={() => ctx.handlePropFormNameBlur(prop.index, formIndex)}
                                onPressEnter={() => ctx.handlePropFormNameBlur(prop.index, formIndex)}
                                onClick={(e) => e.stopPropagation()}
                              />
                            ) : (
                              <span
                                className="prop-form-title-editable"
                                onClick={() =>
                                  ctx.startEditPropFormName(prop.index, formIndex, form.name)
                                }
                              >
                                {getPropFormName(form.name)}
                              </span>
                            )}
                          </div>
                          <div className="prop-form-actions">
                            <Button
                              size="small"
                              onClick={() => ctx.handleEditPropFormSetting(prop.index, formIndex)}
                            >
                              形态图设定
                            </Button>
                            {ctx.isPropFormEditImageDisabled(prop.index, formIndex) ? (
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
                                onClick={() => ctx.handleEditPropFormImage(prop.index, formIndex)}
                              >
                                编辑形态图
                              </Button>
                            )}
                            <Button
                              size="small"
                              onClick={() => ctx.handleCopyPropForm(prop.index, formIndex)}
                            >
                              复制形态
                            </Button>
                            <Button
                              size="small"
                              danger
                              onClick={() => ctx.handleDeletePropForm(prop.index, formIndex)}
                            >
                              删除形态
                            </Button>
                          </div>
                        </div>
                        <div className="prop-form-content">
                          {propFormGenerationStatus[`${prop.index}-${formIndex}`] === 'generating' ? (
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
                                正在生成道具形态图…
                              </p>
                            </div>
                          ) : propFormGenerationStatus[`${prop.index}-${formIndex}`] === 'failed' ? (
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
                                  disabled={!ctx.canAutoGeneratePropFormImage(prop.index, formIndex)}
                                  title="确认重新生成该道具形态图？"
                                  resolveRequest={() =>
                                    resolveScpFormImageBillingRequest(
                                      ctx,
                                      'prop',
                                      prop.index,
                                      formIndex
                                    )
                                  }
                                  onConfirm={() =>
                                    ctx.handleAutoGeneratePropForm(prop.index, formIndex)
                                  }
                                >
                                  <Button
                                    type="primary"
                                    className="scene-card-failed-retry"
                                    disabled={!ctx.canAutoGeneratePropFormImage(prop.index, formIndex)}
                                  >
                                    重新生成
                                  </Button>
                                </BillingQuoteConfirm>
                              </div>
                            </div>
                          ) : propFormImages[`${prop.index}-${formIndex}`] &&
                            propFormImages[`${prop.index}-${formIndex}`].length > 0 ? (
                            /* 如果有图片，显示图片列表 */
                            <div className="scene-card-list prop-form-images-list">
                              {propFormImages[`${prop.index}-${formIndex}`].map(
                                (img: any, imgIdx: number) => (
                                  <div
                                    key={`prop-form-${prop.index}-${formIndex}-img-${imgIdx}`}
                                    className="scene-card scene-card-auto"
                                  >
                                    <div className="scene-card-header-with-image">
                                      {ctx.editingImageTitleIndex.value ===
                                      `prop-form-${prop.index}-${formIndex}-${imgIdx}` ? (
                                        <Input
                                          value={ctx.editingImageTitle.value}
                                          onChange={(e) => ctx.editingImageTitle.set(e.target.value)}
                                          size="small"
                                          className="image-title-input"
                                          onBlur={() =>
                                            ctx.handlePropFormImageTitleBlur(
                                              prop.index,
                                              formIndex,
                                              imgIdx
                                            )
                                          }
                                          onPressEnter={() =>
                                            ctx.handlePropFormImageTitleBlur(
                                              prop.index,
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
                                            ctx.startEditPropFormImageTitle(
                                              prop.index,
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
                                        label="取消道具"
                                        onClick={() =>
                                          ctx.handleDeletePropFormImageByIndex(
                                            prop.index,
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
                                        objectFit="cover"
                                        revealDirection="fade"
                                        onClick={() =>
                                          ctx.handleEditPropFormImageWithIndex(
                                            prop.index,
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
                                          ctx.handlePreviewPropFormImageByIndex(
                                            prop.index,
                                            formIndex,
                                            imgIdx
                                          )
                                        }
                                      >
                                        预览
                                      </Button>
                                      {shouldShowAssetImageRegenerateAction(
                                        img,
                                        ctx.canAutoGeneratePropFormImage(prop.index, formIndex)
                                      ) ? (
                                        <BillingQuoteConfirm
                                          title="确认重新生成该道具形态图？"
                                          resolveRequest={() =>
                                            resolveScpFormImageBillingRequest(
                                              ctx,
                                              'prop',
                                              prop.index,
                                              formIndex
                                            )
                                          }
                                          onConfirm={() =>
                                            ctx.handlePropFormImageMiddleActionByIndex(
                                              prop.index,
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
                                            ctx.handlePropFormImageMiddleActionByIndex(
                                              prop.index,
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
                                          ctx.handleDownloadPropFormImageByIndex(
                                            prop.index,
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
                            <div className="prop-form-card manual-generate-card">
                              <div className="asset-generate-card__actions">
                                <BillingQuoteConfirm
                                  disabled={
                                    !ctx.canAutoGeneratePropFormImage(prop.index, formIndex) ||
                                    propFormGenerationStatus[`${prop.index}-${formIndex}`] ===
                                      'generating'
                                  }
                                  title="确认生成该道具形态图？"
                                  resolveRequest={() =>
                                    resolveScpFormImageBillingRequest(
                                      ctx,
                                      'prop',
                                      prop.index,
                                      formIndex
                                    )
                                  }
                                  onConfirm={() =>
                                    ctx.handleAutoGeneratePropForm(prop.index, formIndex)
                                  }
                                >
                                  <Button
                                    type="primary"
                                    className="asset-generate-card__action asset-generate-card__action--primary"
                                    disabled={
                                      !ctx.canAutoGeneratePropFormImage(prop.index, formIndex) ||
                                      propFormGenerationStatus[`${prop.index}-${formIndex}`] ===
                                        'generating'
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
                                  onClick={() => ctx.handleImportPropFormImage(prop.index, formIndex)}
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
                    onClick={() => ctx.handleAddPropForm(prop.index)}
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
