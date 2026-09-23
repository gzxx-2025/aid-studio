import React, { useMemo, useState } from 'react';
import { Alert, Button, Checkbox, Input, InputNumber, Select, Space, Switch, Tooltip, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import type { CapabilityModel, Model } from './types';
import {
  PRESET_SIZE, PRESET_ASPECT, PRESET_DURATION, GENERATION_SCENE_OPTIONS, classifySizeOption,
  compareSizeOptions, formatSizeLabel
} from './constants';

interface Props {
  modelType: string;
  form: Model;
  cap: CapabilityModel;
  onCapChange: (c: CapabilityModel) => void;
  onFormChange: (patch: Partial<Model>) => void;
}

/** 分组小标题：统一 13px 中黑，右侧可带键名提示 */
function GroupLabel({ text, keyName }: { text: string; keyName?: string }) {
  return (
    <div style={{ fontWeight: 500, fontSize: 13, marginBottom: 6, color: '#334155' }}>
      {text}
      {keyName && <code className="code-text" style={{ marginLeft: 6 }}>{keyName}</code>}
    </div>
  );
}

export default function CapabilityEditor({ modelType, form, cap, onCapChange, onFormChange }: Props) {
  const [newSize, setNewSize] = useState('');
  const [newWidth, setNewWidth] = useState<number | null>(null);
  const [newHeight, setNewHeight] = useState<number | null>(null);
  const [newAspect, setNewAspect] = useState('');
  const [newDuration, setNewDuration] = useState(5);
  const defaultAudioEnabled = cap.supportsAudio && cap.defaultAudio !== false;
  const defaultAudioLabel = cap.defaultAudio === true ? '开启'
    : cap.defaultAudio === false ? '关闭' : '未配置（兼容开启）';
  const configurableAsyncVideo = form.protocol === 'configurable-async-video';

  const presetSizes = PRESET_SIZE[modelType] || [];
  const presetAspects = PRESET_ASPECT[modelType] || [];
  const sizeChoices = useMemo(() => {
    const all = [...cap.sizeOptions];
    const labels = new Set(all.map((value) => formatSizeLabel(value).toLowerCase()));
    presetSizes.forEach((value) => {
      const key = formatSizeLabel(value).toLowerCase();
      if (!labels.has(key)) {
        labels.add(key);
        all.push(value);
      }
    });
    return all.sort(compareSizeOptions);
  }, [cap.sizeOptions, presetSizes]);
  const sizeGroups = useMemo(() => {
    const labels = { pixel: '像素尺寸', p: 'P 清晰度', k: 'K 清晰度', other: '模型专用规格' };
    return (['pixel', 'p', 'k', 'other'] as const).map((group) => ({
      label: labels[group],
      options: sizeChoices
        .filter((value) => classifySizeOption(value) === group)
        .map((value) => ({ label: formatSizeLabel(value), value }))
    })).filter((group) => group.options.length > 0);
  }, [sizeChoices]);
  const aspectChoices = useMemo(() => {
    const all = [...presetAspects];
    cap.aspectRatioOptions.forEach((v) => { if (!all.includes(v)) all.push(v); });
    return all;
  }, [cap.aspectRatioOptions, presetAspects]);
  const durationChoices = useMemo(() => {
    const all = [...PRESET_DURATION];
    cap.durationOptions.forEach((v) => { if (!all.includes(v)) all.push(v); });
    return all.sort((a, b) => a - b);
  }, [cap.durationOptions]);
  const generationSceneOptions = modelType === 'image'
    ? GENERATION_SCENE_OPTIONS.image
    : modelType === 'video' ? GENERATION_SCENE_OPTIONS.video : [];

  const updateCap = (patch: Partial<CapabilityModel>) => onCapChange({ ...cap, ...patch });

  const updateTextModalities = (values: string[]) => {
    const inputModalities = Array.from(new Set(['TEXT', ...values]));
    const supportsImageInput = inputModalities.includes('IMAGE');
    const maxInputImages = supportsImageInput ? cap.maxInputImages : 0;
    updateCap({ inputModalities, maxInputImages,
      maxInputVideos: inputModalities.includes('VIDEO') ? cap.maxInputVideos : 0,
      maxInputAudios: inputModalities.includes('AUDIO') ? cap.maxInputAudios : 0,
      maxInputDocuments: inputModalities.includes('DOCUMENT') ? cap.maxInputDocuments : 0 });
    onFormChange({ supportsImageInput,
      supportsMultiImageInput: supportsImageInput && (maxInputImages === -1 || Number(maxInputImages) > 1) });
  };

  const resolutionMappingValue = (source: string) => {
    const mapping = cap.upstreamResolutionMap || {};
    const key = Object.keys(mapping).find((item) => item.toLowerCase() === source.toLowerCase());
    return key ? mapping[key] : '';
  };

  const updateResolutionMapping = (source: string, target: string) => {
    const next = { ...(cap.upstreamResolutionMap || {}) };
    Object.keys(next).forEach((key) => {
      if (key.toLowerCase() === source.toLowerCase()) delete next[key];
    });
    if (target) next[source] = target;
    updateCap({ upstreamResolutionMap: next });
  };

  if (modelType === 'audio') {
    const formatChoices = Array.from(new Set([
      'mp3', 'wav', 'flac', 'pcm', 'pcm16', 'ogg_opus', ...(cap.audioFormatOptions || [])
    ]));
    const sampleRateChoices = Array.from(new Set([
      8000, 16000, 22050, 24000, 32000, 44100, 48000, ...(cap.audioSampleRateOptions || [])
    ])).sort((a, b) => a - b);
    return <div>
      {cap.parseError && <Alert type="error" showIcon style={{ marginBottom: 12 }}
        message="现有能力配置无法解析"
        description="为避免覆盖历史配置，请先通过已核验目录模板修复。" />}
      {Object.keys(cap.preservedCapability || {}).length > 0 && <Alert type="info" showIcon style={{ marginBottom: 12 }}
        message="存在系统保留的扩展能力，保存时会原样保留。" />}
      <Alert type="info" showIcon style={{ marginBottom: 14 }}
        message="语音合成、音色设计和参考样本复制分开配置；这些限制会在报价和预扣前生效。" />

      <div style={{ marginBottom: 16 }}>
        <GroupLabel text="业务模式与必填条件" />
        <Space wrap align="end">
          <div><GroupLabel text="音频操作" keyName="audioOperation" /><Select allowClear style={{ width: 190 }}
            value={cap.audioOperation || undefined} onChange={(value) => updateCap({ audioOperation: value || null })}
            options={[
              { value: 'synthesis', label: '语音合成' },
              { value: 'design', label: '音色设计' },
              { value: 'clone', label: '参考样本复制' }
            ]} /></div>
          <div><GroupLabel text="文本必填" keyName="ttsTextRequired" /><Switch checked={cap.ttsTextRequired === true}
            onChange={(value) => updateCap({ ttsTextRequired: value })} /></div>
          <div><GroupLabel text="音色必填" keyName="ttsVoiceRequired" /><Switch checked={cap.ttsVoiceRequired === true}
            onChange={(value) => updateCap({ ttsVoiceRequired: value })} /></div>
          <div><GroupLabel text="需要参考样本" keyName="voiceSampleRequired" /><Switch checked={cap.voiceSampleRequired === true}
            onChange={(value) => updateCap(value ? { voiceSampleRequired: true }
              : { voiceSampleRequired: false, voiceSampleFormats: [], voiceSampleMaxFileSizeMb: null })} /></div>
        </Space>
      </div>

      <div style={{ marginBottom: 16 }}>
        <GroupLabel text="输出音频" />
        <Space wrap align="end">
          <div><GroupLabel text="允许格式" keyName="audioFormatOptions" /><Select mode="multiple" style={{ width: 330 }}
            options={formatChoices.map((value) => ({ value, label: value }))} value={cap.audioFormatOptions || []}
            onChange={(values) => updateCap({ audioFormatOptions: values })} /></div>
          <div><GroupLabel text="默认格式" keyName="defaultAudioFormat" /><Select allowClear style={{ width: 150 }}
            value={cap.defaultAudioFormat || undefined} options={(cap.audioFormatOptions || []).map((value) => ({ value, label: value }))}
            onChange={(value) => updateCap({ defaultAudioFormat: value || null })} /></div>
          <div><GroupLabel text="允许采样率" keyName="audioSampleRateOptions" /><Select mode="multiple" style={{ width: 360 }}
            options={sampleRateChoices.map((value) => ({ value, label: `${value} Hz` }))}
            value={cap.audioSampleRateOptions || []} onChange={(values) => updateCap({ audioSampleRateOptions: values.sort((a, b) => a - b) })} /></div>
          <div><GroupLabel text="默认采样率" keyName="defaultAudioSampleRate" /><Select allowClear style={{ width: 150 }}
            value={cap.defaultAudioSampleRate ?? undefined}
            options={(cap.audioSampleRateOptions || []).map((value) => ({ value, label: `${value} Hz` }))}
            onChange={(value) => updateCap({ defaultAudioSampleRate: value ?? null })} /></div>
        </Space>
        <Space wrap align="end" style={{ marginTop: 10 }}>
          <div><GroupLabel text="支持流式" keyName="supportsAudioStreaming" /><Switch checked={cap.supportsAudioStreaming === true}
            onChange={(value) => updateCap({ supportsAudioStreaming: value })} /></div>
          <div><GroupLabel text="支持时间戳" keyName="supportsTimestamp" /><Switch checked={cap.supportsTimestamp === true}
            onChange={(value) => updateCap({ supportsTimestamp: value })} /></div>
          <div><GroupLabel text="预置音色" keyName="builtInVoiceOptions" /><Select mode="tags" style={{ width: 360 }}
            value={cap.builtInVoiceOptions || []} placeholder="仅填官方音色编码"
            onChange={(values) => updateCap({ builtInVoiceOptions: values })} /></div>
        </Space>
      </div>

      <div style={{ marginBottom: 16 }}>
        <GroupLabel text="音色控制范围" />
        <Space wrap align="end">
          <div><GroupLabel text="语速最小" keyName="speechRateMin" /><InputNumber precision={0} value={cap.speechRateMin ?? null}
            onChange={(value) => updateCap({ speechRateMin: value })} /></div>
          <div><GroupLabel text="语速最大" keyName="speechRateMax" /><InputNumber precision={0} value={cap.speechRateMax ?? null}
            onChange={(value) => updateCap({ speechRateMax: value })} /></div>
          <div><GroupLabel text="音量最小" keyName="loudnessRateMin" /><InputNumber precision={0} value={cap.loudnessRateMin ?? null}
            onChange={(value) => updateCap({ loudnessRateMin: value })} /></div>
          <div><GroupLabel text="音量最大" keyName="loudnessRateMax" /><InputNumber precision={0} value={cap.loudnessRateMax ?? null}
            onChange={(value) => updateCap({ loudnessRateMax: value })} /></div>
          <div><GroupLabel text="音调最小" keyName="pitchMin" /><InputNumber precision={0} value={cap.pitchMin ?? null}
            onChange={(value) => updateCap({ pitchMin: value })} /></div>
          <div><GroupLabel text="音调最大" keyName="pitchMax" /><InputNumber precision={0} value={cap.pitchMax ?? null}
            onChange={(value) => updateCap({ pitchMax: value })} /></div>
        </Space>
        <Space wrap align="end" style={{ marginTop: 10 }}>
          <div><GroupLabel text="情感选项" keyName="emotionOptions" /><Select mode="tags" style={{ width: 430 }}
            value={cap.emotionOptions || []} onChange={(values) => updateCap({ emotionOptions: values })} /></div>
          <div><GroupLabel text="情感强度" keyName="supportsEmotionScale" /><Switch checked={cap.supportsEmotionScale === true}
            onChange={(value) => updateCap({ supportsEmotionScale: value })} /></div>
        </Space>
      </div>

      <div style={{ marginBottom: 14 }}>
        <GroupLabel text="参考样本限制" />
        <Space wrap align="end">
          <div><GroupLabel text="样本格式" keyName="voiceSampleFormats" /><Select mode="multiple" style={{ width: 260 }}
            disabled={cap.voiceSampleRequired !== true} value={cap.voiceSampleFormats || []}
            options={['mp3', 'wav', 'm4a', 'flac', 'ogg'].map((value) => ({ value, label: value }))}
            onChange={(values) => updateCap({ voiceSampleFormats: values })} /></div>
          <div><GroupLabel text="单样本上限(MB)" keyName="voiceSampleMaxFileSizeMb" /><InputNumber min={0.01} step={0.1}
            disabled={cap.voiceSampleRequired !== true} value={cap.voiceSampleMaxFileSizeMb ?? null}
            onChange={(value) => updateCap({ voiceSampleMaxFileSizeMb: value })} /></div>
        </Space>
        <div className="help-text" style={{ marginTop: 4 }}>预置候选只是编辑辅助；未被模型配置选中的格式和参数不会自动启用。</div>
      </div>
    </div>;
  }

  if (modelType === 'text') {
    return <div>
      {cap.parseError && <Alert type="error" showIcon style={{ marginBottom: 12 }}
        message="现有能力配置无法解析"
        description="为避免把历史配置覆盖为空，当前结构化编辑结果不能保存；请先通过受控迁移或已核验目录模板修复配置。" />}
      {Object.keys(cap.preservedCapability || {}).length > 0 && (
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
          message="存在系统保留的扩展能力，保存时会原样保留。" />
      )}
      <Alert type="info" showIcon style={{ marginBottom: 12 }}
        message="这里声明模型能力；是否流式和是否思考由每次业务调用决定，供应商真实 usage 决定结算。" />
      <div style={{ marginBottom: 14 }}>
        <GroupLabel text="输入模态" keyName="inputModalities" />
        <Checkbox.Group
          options={['TEXT', 'IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT']}
          value={cap.inputModalities || ['TEXT']}
          onChange={(values) => updateTextModalities(values as string[])}
        />
      </div>
      <Space wrap align="end" style={{ marginBottom: 14 }}>
        {[
          ['IMAGE', '图片上限', 'maxInputImages'],
          ['VIDEO', '视频上限', 'maxInputVideos'],
          ['AUDIO', '音频上限', 'maxInputAudios'],
          ['DOCUMENT', '文档上限', 'maxInputDocuments']
        ].map(([modality, label, field]) => (
          <div key={field}>
            <GroupLabel text={label} keyName={field} />
            <InputNumber min={-1} precision={0} style={{ width: 120 }}
              disabled={!(cap.inputModalities || []).includes(modality)}
              value={(cap as any)[field] ?? null}
              placeholder="未核验可留空"
              onChange={(value) => {
                updateCap({ [field]: value } as Partial<CapabilityModel>);
                if (field === 'maxInputImages') {
                  onFormChange({ supportsMultiImageInput: value === -1 || Number(value) > 1 });
                }
              }} />
          </div>
        ))}
        <div>
          <GroupLabel text="全部输入媒体合计 MB" keyName="maxInputMediaTotalFileSizeMb" />
          <InputNumber min={0.01} step={0.1} style={{ width: 180 }}
            disabled={!(cap.inputModalities || []).some((value) => value !== 'TEXT')}
            value={cap.maxInputMediaTotalFileSizeMb ?? null}
            placeholder="与单文件上限独立"
            onChange={(value) => updateCap({ maxInputMediaTotalFileSizeMb: value })} />
        </div>
        <div>
          <GroupLabel text="媒体 URL 最大长度" keyName="inputMediaMaxUrlLength" />
          <InputNumber min={1} precision={0} style={{ width: 160 }}
            disabled={!(cap.inputModalities || []).some((value) => value !== 'TEXT')}
            value={cap.inputMediaMaxUrlLength ?? null}
            placeholder="未核验可留空"
            onChange={(value) => updateCap({ inputMediaMaxUrlLength: value })} />
        </div>
      </Space>
      <div style={{ marginBottom: 14 }}>
        <GroupLabel text="媒体允许的消息角色" keyName="inputMediaAllowedMessageRoles" />
        <Select mode="multiple" allowClear style={{ width: 460 }}
          disabled={!(cap.inputModalities || []).some((value) => value !== 'TEXT')}
          options={['user', 'developer', 'system', 'assistant', 'tool', 'function'].map((value) => ({ value, label: value }))}
          value={cap.inputMediaAllowedMessageRoles || []}
          placeholder="留空表示协议无额外角色限制"
          onChange={(value) => updateCap({ inputMediaAllowedMessageRoles: value })} />
      </div>
      <div style={{ marginBottom: 14 }}>
        <GroupLabel text="输出模态" keyName="outputModalities" />
        <Checkbox.Group
          options={['TEXT', 'IMAGE', 'VIDEO', 'AUDIO']}
          value={cap.outputModalities || ['TEXT']}
          onChange={(values) => updateCap({ outputModalities: values as string[] })}
        />
      </div>

      <Space wrap align="end" style={{ marginBottom: 14 }}>
        <div><GroupLabel text="上下文Token" keyName="contextWindowTokens" /><InputNumber min={1} precision={0} value={cap.contextWindowTokens ?? null} onChange={(v) => updateCap({ contextWindowTokens: v })} /></div>
        <div><GroupLabel text="最大输出Token" keyName="maxOutputTokens" /><InputNumber min={1} precision={0} value={cap.maxOutputTokens ?? null} onChange={(v) => updateCap({ maxOutputTokens: v })} /></div>
        <div><GroupLabel text="非中日韩提示词最大字符" keyName="maxPromptCharacters" /><InputNumber min={1} precision={0} value={cap.maxPromptCharacters ?? null} onChange={(v) => updateCap({ maxPromptCharacters: v })} /></div>
        <div><GroupLabel text="中日韩提示词最大字符" keyName="maxPromptCharactersCjk" /><InputNumber min={1} precision={0} value={cap.maxPromptCharactersCjk ?? null} placeholder="留空沿用通用上限" onChange={(v) => updateCap({ maxPromptCharactersCjk: v })} /></div>
        <div><GroupLabel text="文档最大页数" keyName="maxInputDocumentPages" /><InputNumber min={1} precision={0} disabled={!(cap.inputModalities || []).includes('DOCUMENT')} value={cap.maxInputDocumentPages ?? null} onChange={(v) => updateCap({ maxInputDocumentPages: v })} /></div>
        <div><GroupLabel text="视频最短秒数" keyName="minInputVideoDurationSeconds" /><InputNumber min={0.001} step={0.1} disabled={!(cap.inputModalities || []).includes('VIDEO')} value={cap.minInputVideoDurationSeconds ?? null} onChange={(v) => updateCap({ minInputVideoDurationSeconds: v })} /></div>
        <div><GroupLabel text="视频最长秒数" keyName="maxInputVideoDurationSeconds" /><InputNumber min={0.001} step={0.1} disabled={!(cap.inputModalities || []).includes('VIDEO')} value={cap.maxInputVideoDurationSeconds ?? null} onChange={(v) => updateCap({ maxInputVideoDurationSeconds: v })} /></div>
        <div><GroupLabel text="视频总秒数" keyName="maxInputVideoTotalDurationSeconds" /><InputNumber min={0.001} step={0.1} disabled={!(cap.inputModalities || []).includes('VIDEO')} value={cap.maxInputVideoTotalDurationSeconds ?? null} onChange={(v) => updateCap({ maxInputVideoTotalDurationSeconds: v })} /></div>
        <div><GroupLabel text="音频最短秒数" keyName="minInputAudioDurationSeconds" /><InputNumber min={0.001} step={0.1} disabled={!(cap.inputModalities || []).includes('AUDIO')} value={cap.minInputAudioDurationSeconds ?? null} onChange={(v) => updateCap({ minInputAudioDurationSeconds: v })} /></div>
        <div><GroupLabel text="音频最长秒数" keyName="maxInputAudioDurationSeconds" /><InputNumber min={0.001} step={0.1} disabled={!(cap.inputModalities || []).includes('AUDIO')} value={cap.maxInputAudioDurationSeconds ?? null} onChange={(v) => updateCap({ maxInputAudioDurationSeconds: v })} /></div>
        <div><GroupLabel text="音频总秒数" keyName="maxInputAudioTotalDurationSeconds" /><InputNumber min={0.001} step={0.1} disabled={!(cap.inputModalities || []).includes('AUDIO')} value={cap.maxInputAudioTotalDurationSeconds ?? null} onChange={(v) => updateCap({ maxInputAudioTotalDurationSeconds: v })} /></div>
      </Space>
      <Space wrap align="end" style={{ marginBottom: 14 }}>
        {[
          ['IMAGE', '单张图片 MB', 'maxInputImageFileSizeMb'],
          ['VIDEO', '单个视频 MB', 'maxInputVideoFileSizeMb'],
          ['AUDIO', '单个音频 MB', 'maxInputAudioFileSizeMb'],
          ['DOCUMENT', '单个文档 MB', 'maxInputDocumentFileSizeMb']
        ].map(([modality, label, field]) => (
          <div key={field}>
            <GroupLabel text={label} keyName={field} />
            <InputNumber min={0.01} step={0.1} style={{ width: 150 }}
              disabled={!(cap.inputModalities || []).includes(modality)}
              value={(cap as any)[field] ?? null}
              onChange={(value) => updateCap({ [field]: value } as Partial<CapabilityModel>)} />
          </div>
        ))}
      </Space>
      <Space wrap align="end" style={{ marginBottom: 14 }}>
        {[
          ['IMAGE', '图片格式', 'inputImageFormats'],
          ['VIDEO', '视频格式', 'inputVideoFormats'],
          ['AUDIO', '音频格式', 'inputAudioFormats'],
          ['DOCUMENT', '文档格式', 'inputDocumentFormats']
        ].map(([modality, label, field]) => (
          <div key={field}><GroupLabel text={label} keyName={field} /><Select mode="tags" style={{ width: 210 }}
            disabled={!(cap.inputModalities || []).includes(modality)} value={(cap as any)[field] || []}
            onChange={(value) => updateCap({ [field]: value } as Partial<CapabilityModel>)} /></div>
        ))}
      </Space>
      <div style={{ marginBottom: 14 }}>
        <GroupLabel text="图片输入画面限制" />
        <Space wrap align="end">
          <div><GroupLabel text="最小边长(px)" keyName="inputImageMinDimensionPixels" /><InputNumber min={1} precision={0}
            disabled={!(cap.inputModalities || []).includes('IMAGE')} value={cap.inputImageMinDimensionPixels ?? null}
            onChange={(value) => updateCap({ inputImageMinDimensionPixels: value })} /></div>
          <div><GroupLabel text="最大边长(px)" keyName="inputImageMaxDimensionPixels" /><InputNumber min={1} precision={0}
            disabled={!(cap.inputModalities || []).includes('IMAGE')} value={cap.inputImageMaxDimensionPixels ?? null}
            onChange={(value) => updateCap({ inputImageMaxDimensionPixels: value })} /></div>
          <div><GroupLabel text="高数量阈值(张)" keyName="inputImageHighCountThreshold" /><InputNumber min={1} precision={0}
            disabled={!(cap.inputModalities || []).includes('IMAGE')} value={cap.inputImageHighCountThreshold ?? null}
            onChange={(value) => updateCap({ inputImageHighCountThreshold: value })} /></div>
          <div><GroupLabel text="达到阈值后最大边长(px)" keyName="inputImageHighCountMaxDimensionPixels" /><InputNumber min={1} precision={0}
            disabled={!(cap.inputModalities || []).includes('IMAGE')} value={cap.inputImageHighCountMaxDimensionPixels ?? null}
            onChange={(value) => updateCap({ inputImageHighCountMaxDimensionPixels: value })} /></div>
          <div><GroupLabel text="最小总像素" keyName="inputImageMinPixels" /><InputNumber min={1} precision={0}
            disabled={!(cap.inputModalities || []).includes('IMAGE')} value={cap.inputImageMinPixels ?? null}
            onChange={(value) => updateCap({ inputImageMinPixels: value })} /></div>
          <div><GroupLabel text="最大总像素" keyName="inputImageMaxPixels" /><InputNumber min={1} precision={0}
            disabled={!(cap.inputModalities || []).includes('IMAGE')} value={cap.inputImageMaxPixels ?? null}
            onChange={(value) => updateCap({ inputImageMaxPixels: value })} /></div>
          <div><GroupLabel text="最小宽高比" keyName="inputImageMinAspectRatio" /><InputNumber min={0.001} precision={4}
            disabled={!(cap.inputModalities || []).includes('IMAGE')} value={cap.inputImageMinAspectRatio ?? null}
            onChange={(value) => updateCap({ inputImageMinAspectRatio: value })} /></div>
          <div><GroupLabel text="最大宽高比" keyName="inputImageMaxAspectRatio" /><InputNumber min={0.001} precision={4}
            disabled={!(cap.inputModalities || []).includes('IMAGE')} value={cap.inputImageMaxAspectRatio ?? null}
            onChange={(value) => updateCap({ inputImageMaxAspectRatio: value })} /></div>
        </Space>
      </div>
      <div style={{ marginBottom: 14 }}>
        <GroupLabel text="视频输入画面限制" />
        <Space wrap align="end">
          <div><GroupLabel text="最小边长(px)" keyName="inputVideoMinDimensionPixels" /><InputNumber min={1} precision={0}
            disabled={!(cap.inputModalities || []).includes('VIDEO')} value={cap.inputVideoMinDimensionPixels ?? null}
            onChange={(value) => updateCap({ inputVideoMinDimensionPixels: value })} /></div>
          <div><GroupLabel text="最大边长(px)" keyName="inputVideoMaxDimensionPixels" /><InputNumber min={1} precision={0}
            disabled={!(cap.inputModalities || []).includes('VIDEO')} value={cap.inputVideoMaxDimensionPixels ?? null}
            onChange={(value) => updateCap({ inputVideoMaxDimensionPixels: value })} /></div>
          <div><GroupLabel text="最小总像素" keyName="inputVideoMinPixels" /><InputNumber min={1} precision={0}
            disabled={!(cap.inputModalities || []).includes('VIDEO')} value={cap.inputVideoMinPixels ?? null}
            onChange={(value) => updateCap({ inputVideoMinPixels: value })} /></div>
          <div><GroupLabel text="最大总像素" keyName="inputVideoMaxPixels" /><InputNumber min={1} precision={0}
            disabled={!(cap.inputModalities || []).includes('VIDEO')} value={cap.inputVideoMaxPixels ?? null}
            onChange={(value) => updateCap({ inputVideoMaxPixels: value })} /></div>
          <div><GroupLabel text="最小宽高比" keyName="inputVideoMinAspectRatio" /><InputNumber min={0.001} precision={4}
            disabled={!(cap.inputModalities || []).includes('VIDEO')} value={cap.inputVideoMinAspectRatio ?? null}
            onChange={(value) => updateCap({ inputVideoMinAspectRatio: value })} /></div>
          <div><GroupLabel text="最大宽高比" keyName="inputVideoMaxAspectRatio" /><InputNumber min={0.001} precision={4}
            disabled={!(cap.inputModalities || []).includes('VIDEO')} value={cap.inputVideoMaxAspectRatio ?? null}
            onChange={(value) => updateCap({ inputVideoMaxAspectRatio: value })} /></div>
          <div><GroupLabel text="最小帧率" keyName="inputVideoMinFps" /><InputNumber min={0.001} precision={3}
            disabled={!(cap.inputModalities || []).includes('VIDEO')} value={cap.inputVideoMinFps ?? null}
            onChange={(value) => updateCap({ inputVideoMinFps: value })} /></div>
          <div><GroupLabel text="最大帧率" keyName="inputVideoMaxFps" /><InputNumber min={0.001} precision={3}
            disabled={!(cap.inputModalities || []).includes('VIDEO')} value={cap.inputVideoMaxFps ?? null}
            onChange={(value) => updateCap({ inputVideoMaxFps: value })} /></div>
        </Space>
        <div className="help-text" style={{ marginTop: 4 }}>
          仅填写原厂或 TokenDance 明确公开的硬约束；启用后服务端会读取真实素材元数据并在预扣前拒绝超限输入。
        </div>
      </div>
      <Space wrap align="start">
        <div><GroupLabel text="流式输出" keyName="supportsStreaming" /><Switch checked={cap.supportsStreaming === true} onChange={(v) => updateCap({ supportsStreaming: v })} /></div>
        <div><GroupLabel text="工具调用" keyName="supportsToolCalling" /><Switch checked={cap.supportsToolCalling === true} onChange={(v) => updateCap({ supportsToolCalling: v })} /></div>
        <div>
          <GroupLabel text="对话前缀续写" keyName="supportsChatPrefix" />
          <Switch aria-label="对话前缀续写" checked={cap.supportsChatPrefix === true} onChange={(v) => updateCap({ supportsChatPrefix: v })} />
          <div className="help-text">仅在当前协议支持时开启；最后一条助手消息作为续写前缀。</div>
        </div>
        <div><GroupLabel text="结构化输出" keyName="supportsStructuredOutput" /><Switch checked={cap.supportsStructuredOutput === true} onChange={(v) => updateCap({ supportsStructuredOutput: v })} /></div>
        <div><GroupLabel text="上下文缓存" keyName="supportsContextCaching" /><Switch checked={cap.supportsContextCaching === true} onChange={(v) => updateCap({ supportsContextCaching: v })} /></div>
        <div><GroupLabel text="内置工具" keyName="supportsBuiltinTools" /><Switch checked={cap.supportsBuiltinTools === true} onChange={(v) => updateCap({ supportsBuiltinTools: v })} /></div>
        <div><GroupLabel text="支持思考" keyName="supportsReasoning" /><Switch checked={cap.supportsReasoning === true} onChange={(v) => updateCap(v ? { supportsReasoning: true } : { supportsReasoning: false, supportsReasoningDisable: false, returnsReasoningContent: false, supportsReasoningBudget: false, defaultReasoningEnabled: false, reasoningApiStyle: undefined, outputTokenApiField: undefined, allowedReasoningLevels: [] })} /></div>
        <div><GroupLabel text="允许关闭" keyName="supportsReasoningDisable" /><Switch disabled={!cap.supportsReasoning} checked={cap.supportsReasoningDisable === true} onChange={(v) => updateCap({ supportsReasoningDisable: v })} /></div>
        <div><GroupLabel text="返回思考内容" keyName="returnsReasoningContent" /><Switch disabled={!cap.supportsReasoning} checked={cap.returnsReasoningContent === true} onChange={(v) => updateCap({ returnsReasoningContent: v })} /></div>
        <div><GroupLabel text="支持Token预算" keyName="supportsReasoningBudget" /><Switch disabled={!cap.supportsReasoning} checked={cap.supportsReasoningBudget === true} onChange={(v) => updateCap(v ? { supportsReasoningBudget: true } : { supportsReasoningBudget: false, defaultReasoningBudgetTokens: null, maxReasoningBudgetTokens: null })} /></div>
        <div><GroupLabel text="默认开启" keyName="defaultReasoningEnabled" /><Switch disabled={!cap.supportsReasoning} checked={cap.defaultReasoningEnabled === true} onChange={(v) => updateCap({ defaultReasoningEnabled: v })} /></div>
      </Space>
      <Space wrap align="end" style={{ marginTop: 14 }}>
        <div><GroupLabel text="协议映射" keyName="reasoningApiStyle" /><Select allowClear style={{ width: 160 }} value={cap.reasoningApiStyle}
          onChange={(v) => updateCap({ reasoningApiStyle: v })}
          options={['OPENAI', 'QWEN', 'DEEPSEEK', 'GEMINI', 'AGNES'].map((value) => ({ value, label: value }))} /></div>
        <div><GroupLabel text="输出上限字段" keyName="outputTokenApiField" /><Select allowClear style={{ width: 210 }} value={cap.outputTokenApiField}
          onChange={(v) => updateCap({ outputTokenApiField: v })}
          options={['max_tokens', 'max_completion_tokens', 'maxOutputTokens'].map((value) => ({ value, label: value }))} /></div>
        <div><GroupLabel text="允许档位" keyName="allowedReasoningLevels" /><Select mode="tags" style={{ width: 300 }} value={cap.allowedReasoningLevels || []}
          onChange={(v) => updateCap({ allowedReasoningLevels: v })}
          options={['minimal', 'low', 'medium', 'high', 'xhigh', 'max'].map((value) => ({ value, label: value }))} /></div>
        <div><GroupLabel text="默认档位" keyName="defaultReasoningLevel" /><Select allowClear style={{ width: 160 }} disabled={!cap.supportsReasoning}
          value={cap.defaultReasoningLevel} onChange={(v) => updateCap({ defaultReasoningLevel: v })}
          options={(cap.allowedReasoningLevels || []).map((value) => ({ value, label: value }))} /></div>
        <div><GroupLabel text="默认思考预算" keyName="defaultReasoningBudgetTokens" /><InputNumber min={1} precision={0} style={{ width: 170 }}
          disabled={!cap.supportsReasoning || !cap.supportsReasoningBudget} value={cap.defaultReasoningBudgetTokens ?? null}
          onChange={(v) => updateCap({ defaultReasoningBudgetTokens: v })} /></div>
        <div><GroupLabel text="最大思考预算" keyName="maxReasoningBudgetTokens" /><InputNumber min={1} precision={0} style={{ width: 170 }}
          disabled={!cap.supportsReasoning || !cap.supportsReasoningBudget} value={cap.maxReasoningBudgetTokens ?? null}
          onChange={(v) => updateCap({ maxReasoningBudgetTokens: v })} /></div>
      </Space>
    </div>;
  }
  const updateScene = (scene: string, patch: any) => {
    onCapChange({ ...cap, sceneRules: { ...cap.sceneRules, [scene]: { ...(cap.sceneRules as any)[scene], ...patch } } });
  };
  const sceneInputRules = (scene: keyof CapabilityModel['sceneRules']) => {
    const rule = cap.sceneRules[scene];
    const stringValues = (value: unknown) => Array.isArray(value)
      ? value.filter((item): item is string => typeof item === 'string') : [];
    const inputOptions = [
      { value: 'text', label: '文字' },
      { value: 'image', label: '参考图片' },
      { value: 'video', label: '参考视频' },
      { value: 'audio', label: '参考音频' },
      { value: 'firstFrame', label: '首帧图片' },
      { value: 'lastFrame', label: '尾帧图片' }
    ];
    return (
      <Space wrap align="end" style={{ marginTop: 6, marginBottom: 8 }}>
        <div><GroupLabel text="允许输入" /><Select mode="multiple" style={{ width: 250 }} options={inputOptions}
          value={stringValues(rule.allowedInputs)} onChange={(values) => updateScene(scene, { allowedInputs: values })} /></div>
        <div><GroupLabel text="全部必填" /><Select mode="multiple" style={{ width: 220 }} options={inputOptions}
          value={stringValues(rule.requiredInputs)} onChange={(values) => updateScene(scene, { requiredInputs: values })} /></div>
        <div><GroupLabel text="至少一项必填" /><Select mode="multiple" style={{ width: 220 }} options={inputOptions}
          value={stringValues(rule.requiredAnyOf)} onChange={(values) => updateScene(scene, { requiredAnyOf: values })} /></div>
      </Space>
    );
  };

  const addCustomSize = () => {
    const v = newSize.trim();
    if (!v) return;
    if (!cap.sizeOptions.includes(v)) updateCap({ sizeOptions: [...cap.sizeOptions, v] });
    setNewSize('');
  };
  const addPixelSize = () => {
    if (!newWidth || !newHeight) return;
    const value = `${Math.trunc(newWidth)}x${Math.trunc(newHeight)}`;
    if (!cap.sizeOptions.some((item) => formatSizeLabel(item) === formatSizeLabel(value))) {
      updateCap({ sizeOptions: [...cap.sizeOptions, value].sort(compareSizeOptions) });
    }
    setNewWidth(null);
    setNewHeight(null);
  };
  const addCustomAspect = () => {
    const v = newAspect.trim();
    if (!v || !/^\d+\s*:\s*\d+$/.test(v)) { message.error('格式应为 宽:高'); return; }
    const norm = v.replace(/\s+/g, '');
    if (!cap.aspectRatioOptions.includes(norm)) updateCap({ aspectRatioOptions: [...cap.aspectRatioOptions, norm] });
    setNewAspect('');
  };
  const addCustomDuration = () => {
    if (!newDuration || newDuration <= 0) return;
    if (!cap.durationOptions.includes(newDuration)) {
      updateCap({ durationOptions: [...cap.durationOptions, newDuration].sort((a, b) => a - b) });
    }
  };

  return (
    <div>
      {cap.parseError && <Alert type="error" showIcon style={{ marginBottom: 12 }}
        message="现有能力配置无法解析"
        description="为避免把历史配置覆盖为空，当前结构化编辑结果不能保存；请先通过受控迁移或已核验目录模板修复配置。" />}
      {Object.keys(cap.preservedCapability || {}).length > 0 && (
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
          message="存在系统保留的扩展能力，当前页面保存时不会覆盖。" />
      )}

      <Space wrap align="end" style={{ marginBottom: 14 }}>
        <div>
          <GroupLabel text="非中日韩提示词最大字符" keyName="maxPromptCharacters" />
          <InputNumber min={1} precision={0} value={cap.maxPromptCharacters ?? null}
            placeholder="未核验可留空" onChange={(value) => updateCap({ maxPromptCharacters: value })} />
        </div>
        <div>
          <GroupLabel text="中日韩提示词最大字符" keyName="maxPromptCharactersCjk" />
          <InputNumber min={1} precision={0} value={cap.maxPromptCharactersCjk ?? null}
            placeholder="留空沿用通用上限" onChange={(value) => updateCap({ maxPromptCharactersCjk: value })} />
        </div>
      </Space>

      <div style={{ marginBottom: 14 }}>
        <GroupLabel text="输出文件格式" keyName="outputFormatOptions" />
        <Space wrap align="end">
          <div>
            <GroupLabel text="官方支持格式" />
            <Select mode="tags" style={{ width: 300 }} value={cap.outputFormatOptions}
              placeholder="如 mp4、mov、png、jpeg、wav"
              onChange={(values) => {
                const normalized = values.map((value) => value.trim().toLowerCase()).filter(Boolean);
                updateCap({
                  outputFormatOptions: Array.from(new Set(normalized)),
                  defaultOutputFormat: cap.defaultOutputFormat && normalized.includes(cap.defaultOutputFormat)
                    ? cap.defaultOutputFormat : null
                });
              }} />
          </div>
          <div>
            <GroupLabel text="默认输出格式" keyName="defaultOutputFormat" />
            <Select allowClear style={{ width: 180 }} value={cap.defaultOutputFormat || undefined}
              placeholder="未核验可留空"
              options={cap.outputFormatOptions.map((value) => ({ value, label: value }))}
              onChange={(value) => updateCap({ defaultOutputFormat: value || null })} />
          </div>
        </Space>
        <div className="help-text" style={{ marginTop: 4 }}>只配置协议实际支持的输出格式；默认值必须来自已启用格式。</div>
      </div>

      {modelType === 'video' && (
        <div style={{ marginBottom: 14 }}>
          <GroupLabel text="输出帧率" keyName="outputFpsOptions" />
          <Space wrap align="end">
            <div>
              <GroupLabel text="官方支持帧率" />
              <Select mode="tags" style={{ width: 300 }}
                value={cap.outputFpsOptions.map(String)}
                options={[12, 15, 16, 18, 20, 23, 24, 25, 30, 48, 50, 60]
                  .map((value) => ({ value: String(value), label: `${value} fps` }))}
                placeholder="选择或输入官方帧率"
                onChange={(values) => {
                  const normalized = Array.from(new Set(values.map(Number)
                    .filter((value) => Number.isInteger(value) && value > 0))).sort((a, b) => a - b);
                  updateCap({
                    outputFpsOptions: normalized,
                    defaultOutputFps: cap.defaultOutputFps && normalized.includes(cap.defaultOutputFps)
                      ? cap.defaultOutputFps : null
                  });
                }} />
            </div>
            <div>
              <GroupLabel text="默认输出帧率" keyName="defaultOutputFps" />
              <Select allowClear style={{ width: 180 }} value={cap.defaultOutputFps ?? undefined}
                placeholder="未核验可留空"
                options={cap.outputFpsOptions.map((value) => ({ value, label: `${value} fps` }))}
                onChange={(value) => updateCap({ defaultOutputFps: value ?? null })} />
            </div>
          </Space>
          <div className="help-text" style={{ marginTop: 4 }}>输出帧率与参考视频输入帧率分开配置，固定帧率只启用一个值。</div>
        </div>
      )}

      {(modelType === 'image' || modelType === 'video') && <>
      {/* 规格 */}
      <div style={{ marginBottom: 14 }}>
        <GroupLabel text="规格选项" keyName="sizeOptions" />
        <Select
          mode="multiple"
          style={{ width: '100%', maxWidth: 820 }}
          placeholder="只选择官方明确支持的规格"
          value={cap.sizeOptions}
          options={sizeGroups}
          onChange={(values) => updateCap({ sizeOptions: values.sort(compareSizeOptions) })}
        />
        <div className="help-text" style={{ marginTop: 4 }}>
          像素尺寸、P 清晰度和 K 清晰度分别保存；候选项不会自动启用，2K 也不会自动换算成 1440P。
        </div>
        <Space size={4} style={{ marginTop: 6 }} wrap>
          <InputNumber size="small" min={1} precision={0} style={{ width: 100 }} placeholder="宽" value={newWidth} onChange={setNewWidth} />
          <span>×</span>
          <InputNumber size="small" min={1} precision={0} style={{ width: 100 }} placeholder="高" value={newHeight} onChange={setNewHeight} />
          <Button size="small" icon={<PlusOutlined />} onClick={addPixelSize}>添加像素尺寸</Button>
        </Space>
        <Space size={4} style={{ marginTop: 6 }}>
          <Input size="small" style={{ width: 180 }} placeholder="模型专用规格，如 auto" value={newSize} onChange={(e) => setNewSize(e.target.value)} onPressEnter={addCustomSize} />
          <Button size="small" icon={<PlusOutlined />} onClick={addCustomSize}>添加专用规格</Button>
        </Space>
      </div>

      {modelType === 'image' && (
        <div style={{ marginBottom: 14 }}>
          <GroupLabel text="输出画面总像素范围" />
          <Space wrap align="end">
            <div><GroupLabel text="最小总像素数" keyName="minOutputPixels" /><InputNumber min={1} precision={0}
              value={cap.minOutputPixels ?? null} onChange={(value) => updateCap({ minOutputPixels: value })} /></div>
            <div><GroupLabel text="最大总像素数" keyName="maxOutputPixels" /><InputNumber min={1} precision={0}
              value={cap.maxOutputPixels ?? null} onChange={(value) => updateCap({ maxOutputPixels: value })} /></div>
            <div><GroupLabel text="最小输出宽高比" keyName="minOutputAspectRatio" /><InputNumber min={0.01} precision={4} step={0.01}
              value={cap.minOutputAspectRatio ?? null} onChange={(value) => updateCap({ minOutputAspectRatio: value })} /></div>
            <div><GroupLabel text="最大输出宽高比" keyName="maxOutputAspectRatio" /><InputNumber min={0.01} precision={4} step={0.01}
              value={cap.maxOutputAspectRatio ?? null} onChange={(value) => updateCap({ maxOutputAspectRatio: value })} /></div>
          </Space>
          <div className="help-text" style={{ marginTop: 4 }}>
            总像素按宽×高计算，宽高比按宽÷高计算；留空表示官方未公布，不能据此推测上下限。
          </div>
          {cap.minOutputAspectRatio != null && cap.maxOutputAspectRatio != null
            && cap.minOutputAspectRatio > cap.maxOutputAspectRatio && (
            <div style={{ color: '#ff4d4f', marginTop: 4 }}>最小输出宽高比不能大于最大输出宽高比。</div>
          )}
        </div>
      )}

      {configurableAsyncVideo && (
        <div style={{ marginBottom: 14 }}>
          <GroupLabel text="上游分辨率映射" keyName="upstreamResolutionMap" />
          {cap.sizeOptions.length === 0 ? (
            <div className="help-text">请先配置规格选项，再填写对应的上游参数值。</div>
          ) : (
            <Space direction="vertical" size={6}>
              {cap.sizeOptions.map((source) => (
                <Space key={source} size={8} align="center">
                  <code className="code-text" style={{ minWidth: 70, display: 'inline-block' }}>{source}</code>
                  <span style={{ color: '#94a3b8' }}>→</span>
                  <Input
                    size="small"
                    style={{ width: 220 }}
                    value={resolutionMappingValue(source)}
                    placeholder="留空使用系统默认换算"
                    onChange={(event) => updateResolutionMapping(source, event.target.value)}
                  />
                </Space>
              ))}
            </Space>
          )}
          <div className="help-text" style={{ marginTop: 6, maxWidth: 620 }}>
            按上游接口实际枚举逐项填写，例如业务规格 <code>2K</code> 可映射为 <code>2k</code> 或 <code>1440p</code>。
            保存后自动写入能力配置，不需要手工编辑 JSON；留空继续使用协议默认换算。
          </div>
        </div>
      )}

      {/* 比例 */}
      <div style={{ marginBottom: 14 }}>
        <GroupLabel text="比例选项" keyName="aspectRatioOptions" />
        <Checkbox.Group value={cap.aspectRatioOptions} onChange={(v) => updateCap({ aspectRatioOptions: v as string[] })}>
          {aspectChoices.map((o) => <Checkbox key={o} value={o}>{o}</Checkbox>)}
        </Checkbox.Group>
        <Space size={4} style={{ marginTop: 6 }}>
          <Input size="small" style={{ width: 120 }} placeholder="如 5:4" value={newAspect} onChange={(e) => setNewAspect(e.target.value)} onPressEnter={addCustomAspect} />
          <Button size="small" icon={<PlusOutlined />} onClick={addCustomAspect}>新增</Button>
        </Space>
      </div>

      </>}

      {/* 时长（仅 video） */}
      {modelType === 'video' && (
        <div style={{ marginBottom: 14 }}>
          <GroupLabel text="时长选项" keyName="durationOptions" />
          <Checkbox.Group value={cap.durationOptions} onChange={(v) => updateCap({ durationOptions: (v as number[]).sort((a, b) => a - b) })}>
            {durationChoices.map((o) => <Checkbox key={o} value={o}>{o === -1 ? '自适应' : `${o} 秒`}</Checkbox>)}
          </Checkbox.Group>
          <Space size={4} style={{ marginTop: 6 }}>
            <InputNumber size="small" style={{ width: 80 }} min={0.1} max={600} step={0.1} value={newDuration} onChange={(v) => setNewDuration(v || 5)} />
            <Button size="small" icon={<PlusOutlined />} onClick={addCustomDuration}>新增</Button>
          </Space>
          <div style={{ marginTop: 8 }}>
            <Space wrap align="end">
              <div><GroupLabel text="最短生成时长(秒)" /><InputNumber min={0} step={0.1} value={cap.durationMin ?? null}
                onChange={(value) => updateCap({ durationMin: value })} /></div>
              <div><GroupLabel text="最长生成时长(秒)" /><InputNumber min={0} step={0.1} value={cap.durationMax ?? null}
                onChange={(value) => updateCap({ durationMax: value })} /></div>
            </Space>
          </div>
          <div className="help-text" style={{ marginTop: 4 }}>生成时长范围约束输出视频，与下方参考视频输入的单段及总时长约束相互独立。</div>
        </div>
      )}

      {modelType === 'video' && (
        <div style={{ marginBottom: 14 }}>
          <GroupLabel text="参考音频文件输入" keyName="supportsReferenceAudio" />
          <Space size={12} align="center" wrap>
            <span style={{ fontSize: 12, color: '#64748b' }}>
              支持传入参考音频文件
              <Switch
                size="small"
                style={{ marginLeft: 6 }}
                checked={cap.supportsReferenceAudio === true}
                onChange={(v) => updateCap({ supportsReferenceAudio: v })}
              />
            </span>
            <span style={{ fontSize: 12, color: '#64748b' }}>
              依赖音画同出
              <Switch
                size="small"
                style={{ marginLeft: 6 }}
                disabled={!cap.supportsReferenceAudio}
                checked={cap.referenceAudioRequiresGeneratedAudio !== false}
                onChange={(v) => updateCap({ referenceAudioRequiresGeneratedAudio: v })}
              />
            </span>
            <span style={{ fontSize: 12, color: '#64748b' }}>
              必须同时传图片或视频
              <Switch
                size="small"
                style={{ marginLeft: 6 }}
                disabled={!cap.supportsReferenceAudio}
                checked={cap.referenceAudioRequiresVisualInput === true}
                onChange={(v) => updateCap({ referenceAudioRequiresVisualInput: v })}
              />
            </span>
          </Space>
          <div style={{ marginTop: 6, color: '#94a3b8', fontSize: 12 }}>
            仅当上游要求参考音频与音画同出开关同时开启时勾选。
          </div>
          <div style={{ marginTop: 8 }}>
            <Space size={8} wrap>
              <span>最少数量</span>
              <InputNumber size="small" min={0} max={64} precision={0} disabled={!cap.supportsReferenceAudio}
                value={cap.minReferenceAudios ?? undefined}
                onChange={(v) => updateCap({ minReferenceAudios: v == null ? null : Math.trunc(Number(v)) })} />
              <span>最多数量</span>
              <InputNumber size="small" min={-1} max={64} precision={0} disabled={!cap.supportsReferenceAudio}
                value={cap.maxReferenceAudios ?? undefined}
                onChange={(v) => updateCap({ maxReferenceAudios: v == null ? null : Math.trunc(Number(v)) })} />
              <span>单段最短</span>
              <InputNumber size="small" min={0} max={600} step={0.1} addonAfter="秒" disabled={!cap.supportsReferenceAudio}
                value={cap.referenceAudioMinDurationSeconds ?? undefined}
                onChange={(v) => updateCap({ referenceAudioMinDurationSeconds: v })} />
              <span>单段最长</span>
              <InputNumber size="small" min={0} max={600} step={0.1} addonAfter="秒" disabled={!cap.supportsReferenceAudio}
                value={cap.referenceAudioMaxDurationSeconds ?? undefined}
                onChange={(v) => updateCap({ referenceAudioMaxDurationSeconds: v })} />
              <span>总时长上限</span>
              <InputNumber size="small" min={0} max={1800} step={0.1} addonAfter="秒" disabled={!cap.supportsReferenceAudio}
                value={cap.referenceAudioMaxTotalDurationSeconds ?? undefined}
                onChange={(v) => updateCap({ referenceAudioMaxTotalDurationSeconds: v })} />
              <span>单文件上限</span>
              <InputNumber size="small" min={0.01} max={10240} step={0.1} addonAfter="MB" disabled={!cap.supportsReferenceAudio}
                value={cap.referenceAudioMaxFileSizeMb ?? undefined}
                onChange={(v) => updateCap({ referenceAudioMaxFileSizeMb: v })} />
            </Space>
          </div>
          <div style={{ marginTop: 8 }}>
            <span style={{ marginRight: 8 }}>支持格式</span>
            <Checkbox.Group
              disabled={!cap.supportsReferenceAudio}
              options={[
                { label: '不限格式（*）', value: '*' },
                { label: 'WAV', value: 'wav' },
                { label: 'MP3', value: 'mp3' }
              ]}
              value={cap.referenceAudioFormats}
              onChange={(v) => {
                const values = v as string[];
                const hadWildcard = cap.referenceAudioFormats.includes('*');
                const next = values.includes('*')
                  ? (hadWildcard ? values.filter((item) => item !== '*') : ['*'])
                  : values;
                updateCap({ referenceAudioFormats: next });
              }}
            />
          </div>
          <div className="help-text" style={{ marginTop: 4, maxWidth: 660 }}>
            {cap.referenceAudioRequiresGeneratedAudio !== false
              ? '当前配置要求提交参考音频时同时开启生成声音。'
              : '当前配置允许独立提交参考音频，不要求开启生成声音。'}
            数量 -1 表示官方明确不限；留空表示尚未核验。超过数量、单段时长或总时长限制时，任务创建和预扣前直接拒绝。不限格式（*）只能单独选择。
          </div>
        </div>
      )}

      {(modelType === 'image' || modelType === 'video') && <>
      {/* 单次最多参考图张数 maxReferenceImages（image / video 通用，四态语义） */}
      <div style={{ marginBottom: 14 }}>
        <GroupLabel text="单次最多参考图张数" keyName="maxReferenceImages" />
        <Space size={8} align="center">
          <InputNumber
            size="small"
            style={{ width: 120 }}
            min={-1}
            max={64}
            precision={0}
            placeholder="留空=默认"
            value={cap.maxReferenceImages ?? undefined}
            onChange={(v) => {
              if (v == null) {
                updateCap({ maxReferenceImages: null });
                return;
              }
              const n = Math.trunc(Number(v));
              updateCap({ maxReferenceImages: Number.isFinite(n) && n >= -1 ? n : null });
            }}
          />
          <span className="help-text" style={{ maxWidth: 520 }}>
            四态：<b>留空</b>=未配置，运行时回退厂商默认；<b>-1</b>=无限；<b>0</b>=禁止参考图（该模型暂不支持图生图，将来开放改为 N 即可）；<b>N</b>=上限 N 张。
            留空表示尚未配置；-1 仅用于官方明确不限；0 表示禁止；正整数表示硬上限。超过上限时在任务创建和预扣前直接拒绝，不截断素材。
          </span>
        </Space>
      </div>

      <div style={{ marginBottom: 14 }}>
        <GroupLabel text="参考图片文件与画面限制" />
        <Space wrap align="end">
          <div><GroupLabel text="支持格式" /><Select mode="tags" style={{ width: 210 }} value={cap.referenceImageFormats || []}
            onChange={(values) => updateCap({ referenceImageFormats: values })} /></div>
          <div><GroupLabel text="单文件上限(MB)" /><InputNumber min={0.01} step={0.1} value={cap.referenceImageMaxFileSizeMb ?? null}
            onChange={(value) => updateCap({ referenceImageMaxFileSizeMb: value })} /></div>
          <div><GroupLabel text="最小边长(px)" /><InputNumber min={1} precision={0} value={cap.referenceImageMinDimensionPixels ?? null}
            onChange={(value) => updateCap({ referenceImageMinDimensionPixels: value })} /></div>
          <div><GroupLabel text="最大边长(px)" /><InputNumber min={1} precision={0} value={cap.referenceImageMaxDimensionPixels ?? null}
            onChange={(value) => updateCap({ referenceImageMaxDimensionPixels: value })} /></div>
          <div><GroupLabel text="单图最小总像素数" /><InputNumber min={1} precision={0} value={cap.referenceImageMinPixels ?? null}
            onChange={(value) => updateCap({ referenceImageMinPixels: value })} /></div>
          <div><GroupLabel text="单图最大总像素数" /><InputNumber min={1} precision={0} value={cap.referenceImageMaxPixels ?? null}
            onChange={(value) => updateCap({ referenceImageMaxPixels: value })} /></div>
          <div><GroupLabel text="最小宽高比" /><InputNumber min={0.001} precision={4} value={cap.referenceImageMinAspectRatio ?? null}
            onChange={(value) => updateCap({ referenceImageMinAspectRatio: value })} /></div>
          <div><GroupLabel text="最大宽高比" /><InputNumber min={0.001} precision={4} value={cap.referenceImageMaxAspectRatio ?? null}
            onChange={(value) => updateCap({ referenceImageMaxAspectRatio: value })} /></div>
        </Space>
        <div className="help-text" style={{ marginTop: 4 }}>
          总像素数按宽×高计算，与最小/最大边长是不同约束；只填写官方明确公开的硬约束，没有可靠资料时保持留空。
        </div>
      </div>

      </>}

      {modelType === 'video' && (
        <div style={{ marginBottom: 14 }}>
          <GroupLabel text="参考视频输入" keyName="supportsVideoInput" />
          <Space wrap align="center" style={{ marginBottom: 8 }}>
            <span>支持参考视频</span>
            <Switch checked={cap.supportsVideoInput === true}
              onChange={(checked) => updateCap({ supportsVideoInput: checked })} />
            <span>最少数量</span>
            <InputNumber min={0} max={64} precision={0} disabled={!cap.supportsVideoInput}
              value={cap.minReferenceVideos ?? null}
              onChange={(value) => updateCap({ minReferenceVideos: value })} />
            <span>最多数量</span>
            <InputNumber min={-1} max={64} precision={0} disabled={!cap.supportsVideoInput}
              value={cap.maxReferenceVideos ?? null}
              onChange={(value) => updateCap({ maxReferenceVideos: value })} />
          </Space>
          <Space wrap align="end">
            <div><GroupLabel text="支持格式" /><Select mode="tags" style={{ width: 190 }} disabled={!cap.supportsVideoInput}
              value={cap.referenceVideoFormats || []} onChange={(values) => updateCap({ referenceVideoFormats: values })} /></div>
            <div><GroupLabel text="单段最短(秒)" /><InputNumber min={0} step={0.1} disabled={!cap.supportsVideoInput}
              value={cap.referenceVideoMinDurationSeconds ?? null} onChange={(value) => updateCap({ referenceVideoMinDurationSeconds: value })} /></div>
            <div><GroupLabel text="单段最长(秒)" /><InputNumber min={0} step={0.1} disabled={!cap.supportsVideoInput}
              value={cap.referenceVideoMaxDurationSeconds ?? null} onChange={(value) => updateCap({ referenceVideoMaxDurationSeconds: value })} /></div>
            <div><GroupLabel text="总时长上限(秒)" /><InputNumber min={0} step={0.1} disabled={!cap.supportsVideoInput}
              value={cap.referenceVideoMaxTotalDurationSeconds ?? null} onChange={(value) => updateCap({ referenceVideoMaxTotalDurationSeconds: value })} /></div>
            <div><GroupLabel text="单文件上限(MB)" /><InputNumber min={0.01} step={0.1} disabled={!cap.supportsVideoInput}
              value={cap.referenceVideoMaxFileSizeMb ?? null} onChange={(value) => updateCap({ referenceVideoMaxFileSizeMb: value })} /></div>
          </Space>
          <Space wrap align="end" style={{ marginTop: 8 }}>
            <div><GroupLabel text="最小边长(px)" /><InputNumber min={1} precision={0} disabled={!cap.supportsVideoInput}
              value={cap.referenceVideoMinDimensionPixels ?? null} onChange={(value) => updateCap({ referenceVideoMinDimensionPixels: value })} /></div>
            <div><GroupLabel text="最大边长(px)" /><InputNumber min={1} precision={0} disabled={!cap.supportsVideoInput}
              value={cap.referenceVideoMaxDimensionPixels ?? null} onChange={(value) => updateCap({ referenceVideoMaxDimensionPixels: value })} /></div>
            <div><GroupLabel text="最小总像素" /><InputNumber min={1} precision={0} disabled={!cap.supportsVideoInput}
              value={cap.referenceVideoMinPixels ?? null} onChange={(value) => updateCap({ referenceVideoMinPixels: value })} /></div>
            <div><GroupLabel text="最大总像素" /><InputNumber min={1} precision={0} disabled={!cap.supportsVideoInput}
              value={cap.referenceVideoMaxPixels ?? null} onChange={(value) => updateCap({ referenceVideoMaxPixels: value })} /></div>
            <div><GroupLabel text="最小宽高比" /><InputNumber min={0.001} precision={4} disabled={!cap.supportsVideoInput}
              value={cap.referenceVideoMinAspectRatio ?? null} onChange={(value) => updateCap({ referenceVideoMinAspectRatio: value })} /></div>
            <div><GroupLabel text="最大宽高比" /><InputNumber min={0.001} precision={4} disabled={!cap.supportsVideoInput}
              value={cap.referenceVideoMaxAspectRatio ?? null} onChange={(value) => updateCap({ referenceVideoMaxAspectRatio: value })} /></div>
            <div><GroupLabel text="最低 FPS" /><InputNumber min={0.001} precision={3} disabled={!cap.supportsVideoInput}
              value={cap.referenceVideoMinFps ?? null} onChange={(value) => updateCap({ referenceVideoMinFps: value })} /></div>
            <div><GroupLabel text="最高 FPS" /><InputNumber min={0.001} precision={3} disabled={!cap.supportsVideoInput}
              value={cap.referenceVideoMaxFps ?? null} onChange={(value) => updateCap({ referenceVideoMaxFps: value })} /></div>
          </Space>
          <div className="help-text" style={{ marginTop: 4 }}>超过视频数量、单段时长、总时长或文件画面限制时直接拒绝；留空不会推测官方上限。</div>
        </div>
      )}

      {modelType === 'video' && (
        <div style={{ marginBottom: 14 }}>
          <GroupLabel text="视频场景与音频能力" />
          <Space wrap align="end">
            <div><GroupLabel text="通用视频场景" keyName="videoScenario" /><Select allowClear showSearch style={{ width: 190 }}
              value={cap.videoScenario || undefined} onChange={(value) => updateCap({ videoScenario: value || null })}
              options={['text', 'first_frame', 'first_last_frame', 'reference', 'edit'].map((value) => ({ value, label: value }))} /></div>
            {form.protocol === 'seedance:generations' && <div><GroupLabel text="Seedance 任务类型" keyName="seedanceTaskTypeOptions" />
              <Select mode="multiple" style={{ width: 300 }} value={cap.seedanceTaskTypeOptions}
                options={['reference', 'edit', 'extend', 'auto'].map((value) => ({ value, label: value }))}
                onChange={(values) => updateCap({ seedanceTaskTypeOptions: values })} /></div>}
            <div><GroupLabel text="可灵协议场景" keyName="klingScenario" /><Select allowClear showSearch style={{ width: 210 }}
              value={cap.klingScenario || undefined} onChange={(value) => updateCap({ klingScenario: value || null })}
              options={[
                'turbo_i2v', 'standard_i2v', 'standard_multi', 'omni_t2v', 'omni_i2v',
                'omni_first_last', 'omni_reference', 'omni_feature_video', 'omni_edit'
              ].map((value) => ({ value, label: value }))} /></div>
            <div><GroupLabel text="音频模式" keyName="audioModeOptions" /><Select mode="tags" style={{ width: 240 }}
              value={cap.audioModeOptions} options={['off', 'native', 'original'].map((value) => ({ value, label: value }))}
              onChange={(values) => updateCap({ audioModeOptions: Array.from(new Set(values.map((value) => value.trim().toLowerCase()).filter(Boolean))) })} /></div>
            <div><GroupLabel text="默认生成声音" keyName="defaultAudio" /><Select allowClear style={{ width: 190 }}
              disabled={!cap.supportsAudio} value={cap.defaultAudio == null ? undefined : cap.defaultAudio}
              placeholder="未配置（兼容开启）"
              options={[{ value: true, label: '默认开启' }, { value: false, label: '默认关闭' }]}
              onChange={(value) => updateCap({ defaultAudio: value == null ? null : value })} /></div>
            <div><GroupLabel text="音频类型" keyName="audioTypes" /><Select mode="multiple" style={{ width: 300 }}
              value={cap.audioTypes} disabled={!cap.supportsAudio}
              options={[
                { value: 'all', label: '全部声音' },
                { value: 'speech_only', label: '仅语音' },
                { value: 'sound_effect_only', label: '仅音效' }
              ]} onChange={(values) => updateCap({ audioTypes: values })} /></div>
          </Space>
          <Space wrap align="end" style={{ marginTop: 10 }}>
            <div><GroupLabel text="支持背景音乐" keyName="supportsBgm" /><Switch disabled={cap.supportsAudio !== true} checked={cap.supportsBgm === true}
              onChange={(checked) => updateCap({ supportsBgm: checked })} /></div>
            <div><GroupLabel text="支持参考音色 ID" keyName="supportsVoiceId" /><Switch checked={cap.supportsVoiceId === true}
              onChange={(checked) => updateCap({ supportsVoiceId: checked })} /></div>
            <div><GroupLabel text="支持音色控制" keyName="supportsVoiceControl" /><Switch disabled={cap.supportsAudio !== true} checked={cap.supportsVoiceControl === true}
              onChange={(checked) => updateCap({ supportsVoiceControl: checked })} /></div>
            <div><GroupLabel text="支持主体元素" keyName="supportsElements" /><Switch checked={cap.supportsElements === true}
              onChange={(checked) => updateCap(checked ? { supportsElements: true }
                : { supportsElements: false, maxElements: null, elementTypeRequired: false })} /></div>
            <div><GroupLabel text="主体元素上限" keyName="maxElements" /><InputNumber min={1} precision={0}
              disabled={cap.supportsElements !== true} value={cap.maxElements ?? null}
              onChange={(value) => updateCap({ maxElements: value })} /></div>
            <div><GroupLabel text="主体必须声明类型" keyName="elementTypeRequired" /><Switch disabled={cap.supportsElements !== true}
              checked={cap.elementTypeRequired === true} onChange={(checked) => updateCap({ elementTypeRequired: checked })} /></div>
          </Space>
          <div className="help-text" style={{ marginTop: 4 }}>场景、音频模式和主体能力只按对应协议官方枚举启用；预置候选不会自动写入模型。</div>
        </div>
      )}

      {modelType === 'video' && (
        <div style={{ marginBottom: 14 }}>
          <GroupLabel text="混合素材限制" />
          <Space wrap align="end">
            <div><GroupLabel text="图/视频/音频合计上限" /><InputNumber min={-1} precision={0} value={cap.maxReferenceMaterials ?? null}
              onChange={(value) => updateCap({ maxReferenceMaterials: value })} /></div>
            <div><GroupLabel text="全部输入媒体合计大小(MB)" /><InputNumber min={0.01} step={0.1} value={cap.maxInputMediaTotalFileSizeMb ?? null}
              onChange={(value) => updateCap({ maxInputMediaTotalFileSizeMb: value })} /></div>
            <div><GroupLabel text="输入与输出视频合计时长(秒)" /><InputNumber min={0.001} step={0.1} value={cap.maxInputOutputVideoDurationSeconds ?? null}
              onChange={(value) => updateCap({ maxInputOutputVideoDurationSeconds: value })} /></div>
          </Space>
          <div className="help-text" style={{ marginTop: 4 }}>仅按官方硬约束填写；-1 仅表示官方明确不限。</div>
        </div>
      )}

      {modelType === 'image' && (
        <div style={{ marginBottom: 14 }}>
          <GroupLabel text="全部输入媒体合计大小(MB)" keyName="maxInputMediaTotalFileSizeMb" />
          <InputNumber min={0.01} step={0.1} value={cap.maxInputMediaTotalFileSizeMb ?? null}
            placeholder="与单文件上限独立"
            onChange={(value) => updateCap({ maxInputMediaTotalFileSizeMb: value })} />
          <div className="help-text" style={{ marginTop: 4 }}>统计本次请求中全部输入媒体的文件大小总和。</div>
        </div>
      )}

      {(modelType === 'image' || modelType === 'video') && <>
      {/* 最少参考图张数 minReferenceImages（image / video 通用，缺图前置拦截） */}
      <div style={{ marginBottom: 14 }}>
        <GroupLabel text="最少参考图张数" keyName="minReferenceImages" />
        <Space size={8} align="center">
          <InputNumber
            size="small"
            style={{ width: 120 }}
            min={0}
            max={64}
            precision={0}
            placeholder="留空=不要求"
            value={cap.minReferenceImages ?? undefined}
            onChange={(v) => {
              if (v == null) {
                updateCap({ minReferenceImages: null });
                return;
              }
              const n = Math.trunc(Number(v));
              updateCap({ minReferenceImages: Number.isFinite(n) && n >= 0 ? n : null });
            }}
          />
          <span className="help-text" style={{ maxWidth: 520 }}>
            <b>留空 / 0</b>=不要求带图（纯文生可用）；<b>N≥1</b>=必须至少带 N 张输入图，缺图请求在建任务/扣费前被拦截（提示「至少传N张图」），避免到上游才失败空转一轮冻结-退款。
            配置参考：图生图 / 图生视频 / 参考图生视频=1；首尾帧=2（首帧+尾帧）；多帧=2（首帧+至少1个关键帧）。
          </span>
        </Space>
      </div>

      {/* 音画同出：仅 video；运营按官方文档勾选后，C 端才展示「生成声音」开关 */}
      </>}

      {modelType === 'video' && (
        <div style={{ marginBottom: 14 }}>
          <GroupLabel text="音画同出" keyName="supportsAudio" />
          <Space size={12} align="center" wrap>
            <span style={{ fontSize: 12, color: '#64748b' }}>
              支持用户选择生成声音
              <Switch
                size="small"
                style={{ marginLeft: 6 }}
                checked={cap.supportsAudio === true}
                onChange={(v) => updateCap(v ? { supportsAudio: true }
                  : {
                      supportsAudio: false, defaultAudio: false, supportsBgm: false,
                      supportsVoiceControl: false, audioTypes: []
                    })}
              />
            </span>
            {configurableAsyncVideo && (
              <>
                <span style={{ fontSize: 12, color: '#64748b' }}>上游音频开关</span>
                <Select
                  size="small"
                  style={{ width: 220 }}
                  disabled={cap.supportsAudio !== true}
                  value={cap.upstreamAudioField ?? 'generate_audio'}
                  onChange={(v: CapabilityModel['upstreamAudioField']) => updateCap({ upstreamAudioField: v })}
                  options={[
                    { value: 'generate_audio', label: 'generate_audio' },
                    { value: 'audio', label: 'audio' },
                    { value: 'none', label: '不下发（上游隐式处理）' }
                  ]}
                />
              </>
            )}
          </Space>
          <div className="help-text" style={{ marginTop: 4, maxWidth: 560 }}>
            按官方文档配置：Seedance 2.0 / Fast / Mini（<code>generate_audio</code>）、Vidu Q3 系列（<code>audio</code>）等可开。
            开启后写入 <code>capability.supportsAudio=true</code>，表示允许用户选择「生成声音」，并不决定默认是否开启。
            当前默认值为<strong>{defaultAudioEnabled ? '开启' : '关闭'}</strong>
            （历史默认配置：{defaultAudioLabel}）；
            C 端接口会据此返回 <code>capability.defaultGenerateAudio</code>。关闭或不支持时禁止选择，后端也会拒绝 <code>generateAudio=true</code>。
            {configurableAsyncVideo && <>
              只接受参考音频并自动完成音画同步的渠道模型选择“<strong>不下发</strong>”，页面仍展示音画同步能力，但请求不会携带 <code>audio</code> 或 <code>generate_audio</code>。
            </>}
            <br />
            固定无声、或文档未声明音频能力的模型请保持关闭，不要猜测开启。
          </div>
        </div>
      )}

      {/* Base64 传图：凡涉及图片传入的模型（图生图 / 图生视频 / 首尾帧 / 参考生视频）均可配置 */}
      {(modelType === 'image' || modelType === 'video') && (
        <div style={{ marginBottom: 14 }}>
          <GroupLabel text="Base64 传图" />
          <Space size={12} align="center" wrap>
            <span style={{ fontSize: 12, color: '#64748b' }}>
              官方支持 Base64 传图
              <Switch
                size="small"
                style={{ marginLeft: 6 }}
                checked={cap.supportsBase64Image === true}
                onChange={(v) => updateCap({ supportsBase64Image: v, base64ImageEnabled: v ? cap.base64ImageEnabled : false })}
              />
            </span>
            <Tooltip title={cap.supportsBase64Image ? '' : '该接口只允许 URL 传图（先按官方文档确认支持后，打开左侧「官方支持」再启用）'}>
              <span style={{ fontSize: 12, color: cap.supportsBase64Image ? '#64748b' : '#cbd5e1' }}>
                启用 Base64 传图
                <Switch
                  size="small"
                  style={{ marginLeft: 6 }}
                  disabled={cap.supportsBase64Image !== true}
                  checked={cap.base64ImageEnabled === true}
                  onChange={(v) => updateCap({ base64ImageEnabled: v })}
                />
              </span>
            </Tooltip>
          </Space>
          <div className="help-text" style={{ marginTop: 4, maxWidth: 560 }}>
            两个开关都关 = 该模型走 URL 传图（默认）。<b>官方支持</b>按模型文档勾选（这是接口事实，不是随意开）：支持时右侧「启用」才可点；不支持时「启用」灰置并提示「只允许 URL 传图」。
            <br />
            打开<b>启用</b>后，系统把参考图下载转 Base64 内联下发，用于上游网关无法回源业务 CDN（如 gpt-image-2 拉不到内网图 404）的场景。当前仅 gpt-image-2、Agnes 图片系已接入 Base64 内联。
          </div>
        </div>
      )}

      {/* 场景规则 */}
      {(modelType === 'image' || modelType === 'video') && <div style={{ marginBottom: 14 }}>
        <GroupLabel text="场景规则" keyName="sceneRules" />
          <Space style={{ marginBottom: 8 }}>
          <span style={{ fontSize: 12, color: '#64748b' }}>严格校验场景输入</span>
          <Switch size="small" checked={cap.strictSceneRules === true}
            onChange={(checked) => updateCap({ strictSceneRules: checked })} />
          </Space>
          <div style={{ marginBottom: 10 }}>
            <GroupLabel text="允许的生成场景" keyName="allowedScenes" />
            <Select mode="multiple" style={{ width: '100%', maxWidth: 720 }}
              value={cap.allowedScenes}
              options={generationSceneOptions}
              onChange={(values) => updateCap({ allowedScenes: values })} />
            <div className="help-text">只启用官方明确支持的场景；严格校验开启时，未列出的场景会在计费前拒绝。</div>
          </div>
        {modelType === 'image' && (
          <>
            <div style={{ marginBottom: 4 }}><span style={{ fontSize: 12, color: '#64748b' }}>文生图 textToImage：</span>
              <Checkbox checked={cap.sceneRules.textToImage.supportsAspectRatio} onChange={(e) => updateScene('textToImage', { supportsAspectRatio: e.target.checked })}>比例</Checkbox>
              <Checkbox checked={cap.sceneRules.textToImage.supportsSizePreset} onChange={(e) => updateScene('textToImage', { supportsSizePreset: e.target.checked })}>规格</Checkbox>
            </div>
            {sceneInputRules('textToImage')}
            <div><span style={{ fontSize: 12, color: '#64748b' }}>图生图 imageToImage：</span>
              <Checkbox checked={cap.sceneRules.imageToImage.supportsAspectRatio} onChange={(e) => updateScene('imageToImage', { supportsAspectRatio: e.target.checked })}>比例</Checkbox>
              <Checkbox checked={cap.sceneRules.imageToImage.supportsSizePreset} onChange={(e) => updateScene('imageToImage', { supportsSizePreset: e.target.checked })}>规格</Checkbox>
              <Checkbox checked={cap.sceneRules.imageToImage.aspectRatioFollowInput} onChange={(e) => updateScene('imageToImage', { aspectRatioFollowInput: e.target.checked })}>比例跟随输入</Checkbox>
            </div>
            {sceneInputRules('imageToImage')}
          </>
        )}
        {modelType === 'video' && (
          <>
            <div style={{ marginBottom: 4 }}><span style={{ fontSize: 12, color: '#64748b' }}>文生视频 textToVideo：</span>
              <Checkbox checked={cap.sceneRules.textToVideo.supportsAspectRatio} onChange={(e) => updateScene('textToVideo', { supportsAspectRatio: e.target.checked })}>比例</Checkbox>
              <Checkbox checked={cap.sceneRules.textToVideo.supportsSizePreset} onChange={(e) => updateScene('textToVideo', { supportsSizePreset: e.target.checked })}>规格</Checkbox>
              <Checkbox checked={cap.sceneRules.textToVideo.supportsDuration} onChange={(e) => updateScene('textToVideo', { supportsDuration: e.target.checked })}>时长</Checkbox>
            </div>
            {sceneInputRules('textToVideo')}
            <div><span style={{ fontSize: 12, color: '#64748b' }}>图生视频 imageToVideo：</span>
              <Checkbox checked={cap.sceneRules.imageToVideo.supportsAspectRatio} onChange={(e) => updateScene('imageToVideo', { supportsAspectRatio: e.target.checked })}>比例</Checkbox>
              <Checkbox checked={cap.sceneRules.imageToVideo.supportsSizePreset} onChange={(e) => updateScene('imageToVideo', { supportsSizePreset: e.target.checked })}>规格</Checkbox>
              <Checkbox checked={cap.sceneRules.imageToVideo.supportsDuration} onChange={(e) => updateScene('imageToVideo', { supportsDuration: e.target.checked })}>时长</Checkbox>
              <Checkbox checked={cap.sceneRules.imageToVideo.aspectRatioFollowInput} onChange={(e) => updateScene('imageToVideo', { aspectRatioFollowInput: e.target.checked })}>比例跟随输入</Checkbox>
            </div>
            {sceneInputRules('imageToVideo')}
            <div style={{ marginTop: 8 }}><span style={{ fontSize: 12, color: '#64748b' }}>首尾帧 startEndToVideo：</span>
              <Checkbox checked={cap.sceneRules.startEndToVideo.supportsAspectRatio} onChange={(e) => updateScene('startEndToVideo', { supportsAspectRatio: e.target.checked })}>比例</Checkbox>
              <Checkbox checked={cap.sceneRules.startEndToVideo.supportsSizePreset} onChange={(e) => updateScene('startEndToVideo', { supportsSizePreset: e.target.checked })}>规格</Checkbox>
              <Checkbox checked={cap.sceneRules.startEndToVideo.supportsDuration} onChange={(e) => updateScene('startEndToVideo', { supportsDuration: e.target.checked })}>时长</Checkbox>
              <Checkbox checked={cap.sceneRules.startEndToVideo.aspectRatioFollowInput} onChange={(e) => updateScene('startEndToVideo', { aspectRatioFollowInput: e.target.checked })}>比例跟随输入</Checkbox>
            </div>
            {sceneInputRules('startEndToVideo')}
            <div style={{ marginTop: 8 }}><span style={{ fontSize: 12, color: '#64748b' }}>多模态参考 referenceToVideo：</span>
              <Checkbox checked={cap.sceneRules.referenceToVideo.supportsAspectRatio} onChange={(e) => updateScene('referenceToVideo', { supportsAspectRatio: e.target.checked })}>比例</Checkbox>
              <Checkbox checked={cap.sceneRules.referenceToVideo.supportsSizePreset} onChange={(e) => updateScene('referenceToVideo', { supportsSizePreset: e.target.checked })}>规格</Checkbox>
              <Checkbox checked={cap.sceneRules.referenceToVideo.supportsDuration} onChange={(e) => updateScene('referenceToVideo', { supportsDuration: e.target.checked })}>时长</Checkbox>
            </div>
            {sceneInputRules('referenceToVideo')}
            <div style={{ marginTop: 8 }}><span style={{ fontSize: 12, color: '#64748b' }}>视频编辑／延长 videoToVideo：</span>
              <Checkbox checked={cap.sceneRules.videoToVideo.supportsAspectRatio} onChange={(e) => updateScene('videoToVideo', { supportsAspectRatio: e.target.checked })}>比例</Checkbox>
              <Checkbox checked={cap.sceneRules.videoToVideo.supportsSizePreset} onChange={(e) => updateScene('videoToVideo', { supportsSizePreset: e.target.checked })}>规格</Checkbox>
              <Checkbox checked={cap.sceneRules.videoToVideo.supportsDuration} onChange={(e) => updateScene('videoToVideo', { supportsDuration: e.target.checked })}>时长</Checkbox>
            </div>
            {sceneInputRules('videoToVideo')}
          </>
        )}
      </div>}

    </div>
  );
}
