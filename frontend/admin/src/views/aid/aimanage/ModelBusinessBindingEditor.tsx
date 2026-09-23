import React, { useEffect, useState } from 'react';
import { Alert, Button, Card, Form, Radio, Select, Space } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { sharedReadRequest } from '@/utils/sharedReadRequest';
import ModelParameterPreview from './ModelParameterPreview';
import type { ModelCapabilityDefinition } from './modelDefinition';

export interface BusinessModelBinding {
  modelId?: number;
  funcCode: string;
  capabilityCode: string;
  defaultCapability: boolean;
  defaultsJson?: string | null;
}
interface FunctionOption { funcCode: string; funcName: string; modelType: string }
export function BusinessDefaults({ definition, value, onChange }: { definition?: ModelCapabilityDefinition; value?: string | null; onChange: (value: string | null) => void }) {
  if (!definition) return <Alert type="info" message="请先选择使用的模型能力" />;
  let defaults;
  try {
    defaults = value ? JSON.parse(value) : {};
    if (!defaults || Array.isArray(defaults) || typeof defaults !== 'object') throw new Error();
  } catch { return <Alert type="error" message="已有默认参数无法读取，请先核对配置来源" />; }
  const defaultFields = (definition.parameters || []).filter((field) => !field.materialRole);
  return <>{defaultFields.length !== (definition.parameters || []).length && <Alert type="info" showIcon style={{ marginBottom: 12 }} message="原图等素材由每次调用提供，这里只配置可复用的默认参数" />}
    <ModelParameterPreview showMaterialStatistics={false} fields={defaultFields} rules={definition.rules || []} value={defaults}
    onChange={(next) => onChange(JSON.stringify(next))} /><Button onClick={() => onChange(null)}>恢复使用模型默认值</Button></>;
}
const loadFunctions = (): Promise<FunctionOption[]> => sharedReadRequest('/aid/funcconfig/list', { pageNum: 1, pageSize: 1000 })
  .then((result) => result.rows || result.data || []);

export default function ModelBusinessBindingEditor({ value, capabilities, modelType, onChange }: { value: BusinessModelBinding[]; capabilities: ModelCapabilityDefinition[]; modelType: string; onChange: (value: BusinessModelBinding[]) => void }) {
  const [functions, setFunctions] = useState<FunctionOption[]>([]);
  const [failed, setFailed] = useState(false);
  useEffect(() => { let active = true; loadFunctions().then((all) => { if (active) setFunctions(all); }).catch(() => { if (active) setFailed(true); }); return () => { active = false; }; }, []);
  const update = (index: number, patch: Partial<BusinessModelBinding>) => onChange(value.map((b, i) => i === index ? { ...b, ...patch } : b));
  return <Space direction="vertical" style={{ width: '100%' }}>
    {failed && <Alert type="error" message="业务列表读取失败，请重新打开后重试" />}
    {value.map((binding, index) => <Card key={index} size="small" title={`业务绑定 ${index + 1}`} extra={<Button aria-label="删除业务绑定" icon={<DeleteOutlined />} onClick={() => onChange(value.filter((_, i) => i !== index))} />}>
      <Form.Item label="业务功能"><Select showSearch optionFilterProp="label" value={binding.funcCode || undefined} options={functions.filter((f) => f.modelType === modelType).map((f) => ({ value: f.funcCode, label: f.funcName }))} onChange={(funcCode) => update(index, { funcCode })} /></Form.Item>
      <Form.Item label="使用模型的能力"><Select value={binding.capabilityCode || undefined} options={capabilities.filter((c) => c.enabled).map((c) => ({ value: c.code, label: c.label }))} onChange={(capabilityCode) => update(index, { capabilityCode })} /></Form.Item>
      <Form.Item label="业务默认能力"><Radio checked={binding.defaultCapability} onChange={() => onChange(value.map((b, i) => b.funcCode === binding.funcCode ? { ...b, defaultCapability: i === index } : b))}>作为此业务的默认能力</Radio></Form.Item>
      <Form.Item label="业务默认参数"><BusinessDefaults definition={capabilities.find((capability) => capability.code === binding.capabilityCode)} value={binding.defaultsJson} onChange={(defaultsJson) => update(index, { defaultsJson })} /></Form.Item>
    </Card>)}
    <Button icon={<PlusOutlined />} onClick={() => onChange([...value, { funcCode: '', capabilityCode: '', defaultCapability: true }])}>添加业务绑定</Button>
  </Space>;
}
