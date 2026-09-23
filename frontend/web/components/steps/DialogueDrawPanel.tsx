'use client'

import RichTextEditor from '~/components/common/RichTextEditor'
import type { ModelOption } from '~/types/modelAgentOptions'
import type { SelectOption } from '~/utils/modelCapability'
import './DialogueDrawPanel.css'
import GenerateModelConfigBlock from './GenerateModelConfigBlock'
import GenerateSourceImagesStrip from './GenerateSourceImagesStrip'
import ModelSelectDropdown from './ModelSelectDropdown'
import { EDIT_ASSET_PROMPT_MAX_CHARS } from '~/utils/htmlPlain'

type DialogueSourceType = 'storyboard' | 'asset'
type DialogueSourceImage = { url: string; title?: string }

export interface DialogueDrawPanelProps {
  sourceType: DialogueSourceType
  sourceImages: DialogueSourceImage[]
  instructionHtml: string
  modelValue: ModelOption
  modelOptions: ModelOption[]
  modelExpanded: boolean
  aspectRatio: string
  count: number
  quality: string
  aspectRatioOptions?: SelectOption<string>[]
  countOptions?: SelectOption<number>[]
  qualityOptions?: SelectOption<string>[]
  promptMaxLength?: number
  /** 参考图上限；分镜对话作图接口仅允许 1 张 */
  maxSourceCount?: number
  onRemoveSourceImage: (index: number) => void
  onOpenSourcePicker: () => void
  onInstructionHtmlChange: (value: string) => void
  onModelExpandedChange: (value: boolean) => void
  onSelectModel: (model: ModelOption) => void
  onAspectRatioChange: (value: string) => void
  onCountChange: (value: number) => void
  onQualityChange: (value: string) => void
}

export function DialogueDrawPanel({
  sourceType,
  sourceImages,
  instructionHtml,
  modelValue,
  modelOptions,
  modelExpanded,
  aspectRatio,
  count,
  quality,
  aspectRatioOptions,
  countOptions,
  qualityOptions,
  promptMaxLength = EDIT_ASSET_PROMPT_MAX_CHARS,
  maxSourceCount = 4,
  onRemoveSourceImage,
  onOpenSourcePicker,
  onInstructionHtmlChange,
  onModelExpandedChange,
  onSelectModel,
  onAspectRatioChange,
  onCountChange,
  onQualityChange
}: DialogueDrawPanelProps) {
  return (
    <div className="dialogue-draw-panel create-modal-tab-panel" data-source-type={sourceType}>
      <div className="create-modal-tab-chrome dialogue-draw-panel__chrome">
        <GenerateSourceImagesStrip
          images={sourceImages}
          showAdder={sourceImages.length < maxSourceCount}
          showAdderText={!sourceImages.length}
          adderText="导入参考图"
          emptyHint={maxSourceCount === 1 && !sourceImages.length ? '仅支持 1 张参考图' : ''}
          onRemove={(index) => onRemoveSourceImage(index)}
          onOpenAdder={() => onOpenSourcePicker()}
        />
      </div>

      <div className="create-modal-prompt-shell">
        <RichTextEditor
          value={instructionHtml}
          className="dialogue-instruction"
          placeholder="请输入修改要求，例如：把画面改成夕阳氛围、人物表情更开心、增加气氛光效等"
          maxLength={promptMaxLength}
          showCount
          onChange={(value: string) => onInstructionHtmlChange(value)}
        />
      </div>

      <GenerateModelConfigBlock
        aspectRatio={aspectRatio}
        count={count}
        quality={quality}
        aspectRatioOptions={aspectRatioOptions}
        countOptions={countOptions}
        qualityOptions={qualityOptions}
        selectClass="setting-select"
        density="scene"
        showQuality3k={true}
        showAction={false}
        onAspectRatioChange={(v) => onAspectRatioChange(v)}
        onCountChange={(v) => onCountChange(v)}
        onQualityChange={(v) => onQualityChange(v)}
        modelSlot={
          <ModelSelectDropdown
            value={modelValue}
            options={modelOptions}
            expanded={modelExpanded}
            onToggle={() => onModelExpandedChange(!modelExpanded)}
            onClose={() => onModelExpandedChange(false)}
            onSelect={(model: ModelOption) => onSelectModel(model)}
          />
        }
      />
    </div>
  )
}

export default DialogueDrawPanel
