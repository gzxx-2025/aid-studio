import React, { useState } from 'react';
import { Alert, Button, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { sharedReadRequest } from '@/utils/sharedReadRequest';
import type { Model } from './types';
import { parameterPaths, type ModelCapabilityDefinition, type ModelParameter } from './modelDefinition';

function addMissingFields(current: ModelParameter[], incoming: ModelParameter[]): ModelParameter[] {
  return [...current.map((field) => {
    const source = incoming.find((item) => item.name === field.name && item.type === field.type);
    return source && field.type === 'object' ? { ...field, properties: addMissingFields(field.properties || [], source.properties || []) } : field;
  }), ...incoming.filter((field) => !current.some((item) => item.name === field.name)).map((field) => structuredClone(field))];
}

interface OfficialImageTemplate {
  supported: boolean;
  modelId: number;
  capabilities: ModelCapabilityDefinition[];
  addedCapabilityCodes: string[];
  sourceUrls: string[];
}

/** 复用已配置模型的结构，价格和业务绑定由新模型单独确认。 */
export default function ModelTemplatePicker({ modelId, modelType, upstreamModel, current, onChange }: { modelId?: number; modelType: string; upstreamModel?: string; current: ModelCapabilityDefinition[]; onChange: (value: ModelCapabilityDefinition[]) => void }) {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [models, setModels] = useState<Model[]>([]);
  const [selected, setSelected] = useState<number>();
  const [error, setError] = useState('');
  const [officialOpen, setOfficialOpen] = useState(false);
  const [officialLoading, setOfficialLoading] = useState(false);
  const [officialError, setOfficialError] = useState('');
  const [officialTemplate, setOfficialTemplate] = useState<OfficialImageTemplate>();
  const template = models.find((model) => model.id === selected);
  const incoming = template?.capabilities || [];
  const additions = incoming.filter((capability) => !current.some((existing) => existing.code === capability.code));
  const differences = incoming.map((capability) => {
    const existing = current.find((item) => item.code === capability.code);
    return { capability, existing, fields: parameterPaths(capability.parameters || []).filter((path) => !parameterPaths(existing?.parameters || []).includes(path)),
      routes: capability.bindings.filter((route) => !existing?.bindings.some((item) => item.protocol === route.protocol)) };
  });
  const canApply = differences.some((difference) => !difference.existing || difference.fields.length || difference.routes.length);
  const officialCanApply = Boolean(officialTemplate?.capabilities?.some((capability) => {
    const existing = current.find((item) => item.code === capability.code);
    return !existing || parameterPaths(capability.parameters || []).some((path) => !parameterPaths(existing.parameters || []).includes(path));
  }));
  const newRoute = (route: ModelCapabilityDefinition['bindings'][number]) => ({ ...structuredClone(route), upstreamModel, enabled: false, billingMode: 'FIXED', costCredits: undefined, billingRule: undefined,
    fixedParameters: Object.fromEntries(Object.entries(route.fixedParameters || {}).filter(([key]) => !['model', 'model_name', 'api_key', 'apiKey', 'Authorization', 'authorization'].includes(key))) });
  return <>
    <Space wrap style={{ marginBottom: 16 }}>
    <Button disabled={!modelType} onClick={async () => {
      setOpen(true); setLoading(true); setError(''); setSelected(undefined);
      try { const result = await sharedReadRequest('/aid/aidmodel/list', { modelType, pageNum: 1, pageSize: 1000 }); setModels((result.rows || []).filter((model: Model) => model.capabilities?.length)); }
      catch { setError('模型模板读取失败，请重试'); }
      finally { setLoading(false); }
    }}>选择已有模型模板</Button>
    {modelType === 'image' && <Button disabled={!modelId} onClick={async () => {
      if (!modelId) return;
      setOfficialOpen(true); setOfficialLoading(true); setOfficialError(''); setOfficialTemplate(undefined);
      try {
        const result = await sharedReadRequest(`/aid/aidmodel/image-capability-template/${modelId}`);
        setOfficialTemplate(result.data || result);
      } catch { setOfficialError('官方图片能力读取失败，请检查模型真实标识和调用协议后重试'); }
      finally { setOfficialLoading(false); }
    }}>补齐官方图片能力</Button>}
    {modelType === 'image' && !modelId && <Typography.Text type="secondary">请先保存模型，再根据真实标识补齐官方能力</Typography.Text>}
    </Space>
    <Modal open={open} title="预览模型模板" confirmLoading={loading} okButtonProps={{ disabled: !template || !canApply }} onCancel={() => setOpen(false)} onOk={() => {
      const next = additions.map((capability) => ({ ...structuredClone(capability), defaultCapability: current.length === 0 && capability.defaultCapability,
        bindings: capability.bindings.map(newRoute) }));
      const updated = current.map((capability) => {
        const difference = differences.find((item) => item.capability.code === capability.code);
        if (!difference) return capability;
        const codes = new Set(capability.bindings.map((route) => route.code));
        const routes = difference.routes.map((route) => {
          let code = route.code, suffix = 2;
          while (codes.has(code)) code = `${route.code.slice(0, 85)}_${suffix++}`;
          codes.add(code);
          return { ...newRoute(route), code, defaultBinding: false, enabled: false };
        });
        return { ...capability, parameters: addMissingFields(capability.parameters || [], difference.capability.parameters || []), bindings: [...capability.bindings, ...routes] };
      });
      onChange([...updated, ...next]); setOpen(false);
    }}>
      <Space direction="vertical" style={{ width: '100%' }}>
        <Alert type="info" message="补充缺失字段与协议，保留已有参数、规则、价格和业务绑定。新增协议默认停用；模型标识、固定路由参数与价格需逐项核对。" />
        {error && <Alert type="error" message={error} />}
        <Select style={{ width: '100%' }} loading={loading} showSearch optionFilterProp="label" value={selected} placeholder="选择模板模型" options={models.map((model) => ({ value: model.id, label: model.modelName }))} onChange={setSelected} />
        <Table rowKey="code" size="small" pagination={false} dataSource={incoming} columns={[
          { title: '能力', dataIndex: 'label' },
          { title: '参数', render: (_, capability) => parameterPaths(capability.parameters || []).join('、') || '沿用协议参数' },
          { title: '协议', render: (_, capability) => capability.bindings.map((route) => route.protocol).join('、') },
          { title: '变更预览', render: (_, capability) => { const difference = differences.find((item) => item.capability.code === capability.code)!; return difference.existing ? `补充 ${difference.fields.length} 个字段、${difference.routes.length} 个协议；保留已有配置` : '新增能力'; } }
        ]} />
      </Space>
    </Modal>
    <Modal open={officialOpen} title="预览官方图片能力" confirmLoading={officialLoading} width={960}
      styles={{ body: { maxHeight: '68vh', overflowY: 'auto', paddingRight: 8 } }}
      okText="应用到表单" okButtonProps={{ disabled: !officialTemplate?.supported || !officialCanApply }}
      onCancel={() => setOfficialOpen(false)} onOk={() => {
        if (!officialTemplate?.supported) return;
        const updated = current.map((capability) => {
          const candidate = officialTemplate.capabilities.find((item) => item.code === capability.code);
          return candidate ? { ...capability, parameters: addMissingFields(capability.parameters || [], candidate.parameters || []) } : capability;
        });
        const additions = officialTemplate.capabilities
          .filter((capability) => !current.some((item) => item.code === capability.code))
          .map((capability) => structuredClone(capability));
        onChange([...updated, ...additions]);
        setOfficialOpen(false);
      }}>
      <Space direction="vertical" style={{ width: '100%' }}>
        <Alert type="info" showIcon message="候选能力由模型真实标识和已配协议判定。应用后仅向当前表单补充缺失能力和参数，不覆盖未保存的编辑、价格或调用配置；新增能力默认停用，仍需核对后手动保存。" />
        {officialError && <Alert type="error" showIcon message={officialError} />}
        {officialTemplate && !officialTemplate.supported && <Alert type="warning" showIcon message="当前模型的真实标识或协议未匹配已核验的官方图片能力，本次不会修改表单。" />}
        <Table rowKey="code" size="small" pagination={false} loading={officialLoading}
          dataSource={officialTemplate?.capabilities || []} columns={[
            { title: '能力', render: (_, capability) => <Space wrap><span>{capability.label}</span><code>{capability.code}</code>{officialTemplate?.addedCapabilityCodes?.includes(capability.code) && <Tag color="blue">本次新增</Tag>}</Space> },
            { title: '状态', render: (_, capability) => <Tag color={capability.enabled ? 'green' : 'default'}>{capability.enabled ? '已启用' : '待核对'}</Tag> },
            { title: '参数', render: (_, capability) => parameterPaths(capability.parameters || []).join('、') || '无' }
          ]} />
        {Boolean(officialTemplate?.sourceUrls?.length) && <div>
          <Typography.Text type="secondary">官方依据：</Typography.Text>
          <Space direction="vertical" size={2} style={{ width: '100%', marginTop: 4 }}>
            {officialTemplate!.sourceUrls.map((url) => <Typography.Link key={url} href={url} target="_blank" rel="noreferrer">{url}</Typography.Link>)}
          </Space>
        </div>}
      </Space>
    </Modal>
  </>;
}
