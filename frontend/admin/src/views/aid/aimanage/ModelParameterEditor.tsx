import React from 'react';
import { Button, Card, Col, Collapse, Form, Input, InputNumber, Row, Select, Space, Switch } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import StructuredValueEditor from './StructuredValueEditor';
import type { ModelParameter } from './modelDefinition';
import { requestFieldDefinition, requestFieldOptions } from './modelRequestFields';

const types = [['string', '文本'], ['number', '数字'], ['integer', '整数'], ['boolean', '开关'], ['object', '分组'], ['array', '列表']].map(([value, label]) => ({ value, label }));
export const emptyParameter = (name = 'parameter'): ModelParameter => ({ name, label: '新参数', type: 'string' });

export default function ModelParameterEditor({ value, onChange, depth = 0, single = false, modelType }: { value: ModelParameter[]; onChange: (value: ModelParameter[]) => void; depth?: number; single?: boolean; modelType?: string }) {
  const update = (index: number, patch: Partial<ModelParameter>) => onChange(value.map((field, i) => i === index ? { ...field, ...patch } : field));
  const sources = modelType ? requestFieldOptions(modelType) : [];
  const available = sources.filter((source) => !value.some((field) => field.name === source.value));
  return <Space direction="vertical" style={{ width: '100%' }}>
    {value.map((field, index) => <Card size="small" key={index} title={field.label || '新参数'} extra={!single && <Button aria-label={`删除参数 ${field.label}`} icon={<DeleteOutlined />} onClick={() => onChange(value.filter((_, i) => i !== index))} />}>
      <Row gutter={12}>
        <Col span={8}><Form.Item label="字段名称" validateStatus={!/^[a-zA-Z][a-zA-Z0-9_]*$/.test(field.name) ? 'error' : undefined} help={!field.name ? '请填写字段名称' : undefined}>{modelType ? <Select showSearch value={field.name} options={sources} onChange={(name) => update(index, requestFieldDefinition(name))} /> : <Input value={field.name} onChange={(e) => update(index, { name: e.target.value })} />}</Form.Item></Col>
        <Col span={8}><Form.Item label="显示名称"><Input value={field.label} onChange={(e) => update(index, { label: e.target.value })} /></Form.Item></Col>
        <Col span={8}><Form.Item label="参数类型"><Select value={field.type} options={types} onChange={(type) => update(index, { type, widget: undefined, minimum: undefined, maximum: undefined, step: undefined, defaultValue: undefined, choices: undefined, materialRole: undefined, formats: undefined, minDurationSeconds: undefined, maxDurationSeconds: undefined, maxTotalDurationSeconds: undefined, maxFileSizeMb: undefined, maxFileSizeBytes: undefined, clipDurationSeconds: undefined, items: type === 'array' ? emptyParameter('item') : undefined, properties: type === 'object' ? [] : undefined })} /></Form.Item></Col>
        <Col span={8}><Form.Item label="必填"><Switch checked={field.required} onChange={(required) => update(index, { required })} /></Form.Item></Col>
        <Col span={8}><Form.Item label="单位"><Input value={field.unit} onChange={(e) => update(index, { unit: e.target.value })} /></Form.Item></Col>
        <Col span={8}><Form.Item label="输入控件"><Select allowClear value={field.widget} options={['input', 'textarea', 'number', 'switch', 'select', 'multiselect', 'image', 'video', 'audio'].map((value, i) => ({ value, label: ['输入框', '多行文本', '数字框', '开关', '单选', '多选', '图片', '视频', '音频'][i] }))} onChange={(widget) => update(index, { widget })} /></Form.Item></Col>
        <Col span={24}><Form.Item label="填写说明"><Input value={field.description} onChange={(e) => update(index, { description: e.target.value })} /></Form.Item></Col>
      </Row>
      {field.type === 'object' && <ModelParameterEditor value={field.properties || []} depth={depth + 1} onChange={(properties) => update(index, { properties })} />}
      {field.type === 'array' && <ModelParameterEditor single value={[field.items || emptyParameter('item')]} depth={depth + 1} onChange={(items) => update(index, { items: items[0] || emptyParameter('item') })} />}
      <Collapse size="small" items={[{ key: 'constraints', label: '范围、默认值与素材要求', children: <>
        <Row gutter={12}>
          {!['boolean', 'object'].includes(field.type) && <><Col span={8}><Form.Item label={field.type === 'array' ? '最少项数' : field.type === 'string' ? '最少字符' : '最小值'}><InputNumber value={field.minimum} onChange={(minimum) => update(index, { minimum: minimum ?? undefined })} /></Form.Item></Col>
          <Col span={8}><Form.Item label={field.type === 'array' ? '最多项数' : field.type === 'string' ? '最多字符' : '最大值'}><InputNumber value={field.maximum} onChange={(maximum) => update(index, { maximum: maximum ?? undefined })} /></Form.Item></Col></>}
          {['number', 'integer'].includes(field.type) && <Col span={8}><Form.Item label="步长"><InputNumber value={field.step} min={0.000001} onChange={(step) => update(index, { step: step ?? undefined })} /></Form.Item></Col>}
          <Col span={12}><Form.Item label="素材角色"><Select allowClear disabled={!['string', 'object', 'array'].includes(field.type)} value={field.materialRole} options={['first_frame', 'last_frame', 'reference_image', 'mask', 'reference_video', 'reference_audio'].map((value, i) => ({ value, label: ['首帧', '尾帧', '参考图片', '编辑蒙版', '参考视频', '参考音频'][i] }))} onChange={(materialRole) => update(index, { materialRole, minDurationSeconds: undefined, maxDurationSeconds: undefined, maxTotalDurationSeconds: undefined, clipDurationSeconds: undefined, ...(!materialRole ? { formats: undefined, maxFileSizeMb: undefined, maxFileSizeBytes: undefined } : {}) })} /></Form.Item></Col>
          <Col span={12}><Form.Item label="文件格式"><Select mode="tags" disabled={!field.materialRole} value={field.formats || []}
            options={['reference_image', 'mask', 'first_frame', 'last_frame'].includes(field.materialRole || '') ? ['png', 'jpeg', 'jpg', 'webp'].map((format) => ({ value: format, label: format.toUpperCase() })) : []}
            onChange={(formats) => update(index, { formats: Array.from(new Set(formats.map((format) => format.trim().toLowerCase()).filter(Boolean))) })} placeholder="选择或输入格式后按回车" /></Form.Item></Col>
          {field.materialRole && ([['minDurationSeconds', '单个最小时长（秒）'], ['maxDurationSeconds', '单个最大时长（秒）'], ['maxTotalDurationSeconds', '总时长上限（秒）'], ['maxFileSizeMb', '单个文件上限（MB）']] as const).filter(([key]) => key === 'maxFileSizeMb' || ['reference_video', 'reference_audio'].includes(field.materialRole!)).map(([key, label]) => <Col span={12} key={key}><Form.Item label={label}><InputNumber min={0} value={field[key]} onChange={(next) => update(index, { [key]: next ?? undefined })} /></Form.Item></Col>)}
          {field.materialRole && <Col span={12}><Form.Item label="精确文件上限（字节）"><InputNumber style={{ width: '100%' }} min={1} precision={0} value={field.maxFileSizeBytes} onChange={(maxFileSizeBytes) => update(index, { maxFileSizeBytes: maxFileSizeBytes ?? undefined })} /></Form.Item></Col>}
          {['reference_video', 'reference_audio'].includes(field.materialRole || '') && <Col span={12}><Form.Item label="超过后取片头（秒）"><InputNumber min={1} value={field.clipDurationSeconds} onChange={(clipDurationSeconds) => update(index, { clipDurationSeconds: clipDurationSeconds ?? undefined })} /></Form.Item></Col>}
        </Row>
        <Form.Item label="默认值"><Space direction="vertical" style={{ width: '100%' }}><Switch checked={field.defaultValue !== undefined} checkedChildren="已配置" unCheckedChildren="未配置" onChange={(enabled) => update(index, { defaultValue: enabled ? field.type === 'boolean' ? false : ['number', 'integer'].includes(field.type) ? 0 : field.type === 'array' ? [] : field.type === 'object' ? {} : '' : undefined })} />
          {field.defaultValue !== undefined && <StructuredValueEditor value={field.defaultValue} fixedType={field.type === 'integer' ? 'number' : field.type} onChange={(defaultValue) => update(index, { defaultValue })} />}</Space></Form.Item>
        <Form.Item label="允许的选项"><StructuredValueEditor value={field.choices || []} fixedType="array" onChange={(choices) => update(index, { choices: Array.isArray(choices) ? choices : [] })} /></Form.Item>
      </> }]} />
    </Card>)}
    {!single && <Button icon={<PlusOutlined />} disabled={depth >= 16 || Boolean(modelType && !available.length)} onClick={() => { if (modelType) { if (available[0]) onChange([...value, requestFieldDefinition(available[0].value)]); return; } let index = value.length + 1; while (value.some((p) => p.name === `parameter_${index}`)) index++; onChange([...value, emptyParameter(`parameter_${index}`)]); }}>添加参数</Button>}
  </Space>;
}
