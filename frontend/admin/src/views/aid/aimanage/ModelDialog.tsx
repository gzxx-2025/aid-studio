import React, { useEffect, useRef, useState } from 'react';
import { Alert, Col, Form, Input, InputNumber, Modal, Row, Select, Space, Switch, Tabs, Tag, message } from 'antd';
import { MODEL_TYPE_OPTIONS, ENABLE_STATUS_OPTIONS } from '@/utils/enums';
import ImageUpload from '@/components/ImageUpload';
import { resolveProviderLogo } from '@/utils/builtinImages';
import { useAuth } from '@/hooks/useAuth';
import ModelCapabilitiesEditor from './ModelCapabilitiesEditor';
import ModelBusinessBindingEditor from './ModelBusinessBindingEditor';
import ModelTemplatePicker from './ModelTemplatePicker';
import { definitionErrors, type ModelCapabilityDefinition } from './modelDefinition';
import { mergeMaxConcurrency, parseMaxConcurrency } from './helpers';
import type { Model, Provider } from './types';
import { getModelBillingOverview } from './billingSummary';
import { newApiManualImageDefinition, newApiManualModelCode, newApiManualTextDefinition } from './newApiManualModel';

const IMAGE_PROXY_PLACEHOLDER = '{url}';

const validateImageProxyTemplate = (enabled: boolean, value: unknown): string | null => {
  const template = String(value || '').trim();
  if (template.length > 1000) return '图片代理 URL 模板不能超过 1000 个字符';
  if (!enabled) return null;
  if (!template) return '启用图片代理拼接时必须填写 URL 模板';
  const first = template.indexOf(IMAGE_PROXY_PLACEHOLDER);
  if (first < 0 || first !== template.lastIndexOf(IMAGE_PROXY_PLACEHOLDER)) {
    return '模板必须且只能包含一个 {url} 占位符';
  }
  const schemeEnd = template.indexOf('://');
  if (schemeEnd < 0 || template.slice(0, schemeEnd).toLowerCase() !== 'https') {
    return '模板必须使用绝对 HTTPS 地址';
  }
  const authorityStart = schemeEnd + 3;
  const delimiters = ['/', '?', '#']
    .map((delimiter) => template.indexOf(delimiter, authorityStart))
    .filter((index) => index >= 0);
  const authorityEnd = delimiters.length ? Math.min(...delimiters) : template.length;
  if (first < authorityEnd) return '{url} 只能放在代理地址的路径或查询参数中';
  try {
    const parsed = new URL(template.replace(IMAGE_PROXY_PLACEHOLDER, 'encoded-source-url'));
    if (parsed.protocol !== 'https:' || !parsed.hostname) return '模板必须使用绝对 HTTPS 地址';
    if (parsed.username || parsed.password) return '模板不能包含账号或密码';
    if (parsed.hash) return '模板不能包含 Fragment';
  } catch {
    return '图片代理 URL 模板格式无效';
  }
  return null;
};

interface Props {
  open: boolean;
  title: string;
  provider: Provider | null;
  data?: Partial<Model>;
  onCancel: () => void;
  onOk: (values: Model) => Promise<void>;
}

/** 模型层维护统一身份与运营设置，能力和调用参数只有一个编辑入口。 */
export default function ModelDialog({ open, title, provider, data, onCancel, onOk }: Props) {
  const [form] = Form.useForm();
  const { hasPermi } = useAuth();
  const canEditBindings = hasPermi('aid:funcconfig:edit');
  const [model, setModel] = useState<Model>({} as Model);
  const [definitions, setDefinitions] = useState<ModelCapabilityDefinition[]>([]);
  const [tab, setTab] = useState('basic');
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  const manualNewApi = provider?.integrationType === 'NEW_API' && !data?.id;
  const manualNewApiImage = manualNewApi && model.modelType === 'image';

  useEffect(() => {
    if (!open) return;
    const next = { modelType: manualNewApi ? 'text' : '', priority: 1, status: '0', billingMultiplier: 1,
      ...(manualNewApi ? { newApiInputPrice: 1, newApiOutputPrice: 1, newApiImagePrice: 1 } : {}),
      ...data, isFree: data?.isFree === true, imageUrlProxyEnabled: data?.imageUrlProxyEnabled === true } as Model;
    setModel(next);
    setDefinitions(data?.capabilities || []);
    form.resetFields();
    form.setFieldsValue({ ...next, maxConcurrency: parseMaxConcurrency(next.scheduleStrategyJson) });
    setTab(data?.id ? 'capabilities' : 'basic');
  }, [open, data, form, manualNewApi]);

  const save = async () => {
    if (savingRef.current) return;
    savingRef.current = true;
    setSaving(true);
    try {
      let values;
      try { values = await form.validateFields(); }
      catch (error: any) {
        const field = error?.errorFields?.[0]?.name?.[0];
        setTab(['billingMultiplier', 'maxConcurrency', 'priority', 'status', 'isFree'].includes(field) ? 'operations' : 'basic');
        return;
      }
      const effectiveDefinitions = manualNewApi
        ? [values.modelType === 'image'
          ? newApiManualImageDefinition(String(values.realModelCode).trim(), Number(values.newApiImagePrice))
          : newApiManualTextDefinition(String(values.realModelCode).trim(), Number(values.newApiInputPrice), Number(values.newApiOutputPrice))]
        : definitions;
      const errors = definitionErrors(effectiveDefinitions);
      if (errors.length) { message.error(errors[0]); setTab('capabilities'); return; }
      const invalidOutputRange = effectiveDefinitions.some((definition) => definition.bindings.some((binding) => {
        const capability = binding.capability || {};
        const invalid = (minimum: unknown, maximum: unknown) => typeof minimum === 'number'
          && typeof maximum === 'number' && minimum > maximum;
        return invalid(capability.minOutputPixels, capability.maxOutputPixels)
          || invalid(capability.minOutputAspectRatio, capability.maxOutputAspectRatio);
      }));
      if (invalidOutputRange) {
        message.error('输出画面最小值不能大于最大值');
        setTab('capabilities');
        return;
      }
      const capability = effectiveDefinitions.find((item) => item.enabled && item.defaultCapability)!;
      const route = capability.bindings.find((item) => item.enabled && item.defaultBinding)!;
      if (manualNewApi) {
        values.realModelCode = String(values.realModelCode).trim();
        values.modelName = values.realModelCode.slice(0, 100);
        values.modelCode = newApiManualModelCode(provider!.id, values.realModelCode);
        values.modelType = values.modelType === 'image' ? 'image' : 'text';
      }
      const result: Model = {
        ...model, ...values, providerId: provider?.id,
        ...capability.presentation, ...route.presentation,
        capabilities: effectiveDefinitions,
        generateMode: capability.generateMode,
        protocol: route.protocol, apiVersion: route.apiVersion, apiSuffix: route.apiSuffix,
        capabilityJson: JSON.stringify(route.capability || {}),
        paramMappingJson: JSON.stringify(route.parameterMapping || {}),
        extraBody: JSON.stringify(route.fixedParameters || {}),
        billingMode: route.billingMode || 'FIXED',
        billingRuleJson: JSON.stringify(route.billingRule || { mode: 'FIXED', preHold: true }),
        costCredits: route.billingMode === 'SKU' ? null : route.costCredits ?? null,
        scheduleStrategyJson: mergeMaxConcurrency(model.scheduleStrategyJson, values.maxConcurrency)
      };
      delete (result as any).maxConcurrency;
      delete (result as any).newApiInputPrice;
      delete (result as any).newApiOutputPrice;
      delete (result as any).newApiImagePrice;
      if (!canEditBindings) delete result.businessBindings;
      await onOk(result);
    } finally { savingRef.current = false; setSaving(false); }
  };

  const bodyStyle: React.CSSProperties = { maxHeight: '72vh', overflowY: 'auto', paddingRight: 8, paddingTop: 8 };
  const billingOverview = getModelBillingOverview({ ...model, capabilities: definitions });
  const billingTabLabel = <Space size={4}>
    <span>能力、调用与计费</span>
    {billingOverview.skuRouteCount > 0
      ? <Tag color="purple">SKU {billingOverview.enabledSkuCount}</Tag>
      : billingOverview.fixedRouteCount > 0 ? <Tag color="blue">固定价</Tag> : null}
    {billingOverview.issueCount > 0 && <Tag color="error">待补价格</Tag>}
  </Space>;
  return <Modal open={open} title={title} width={1280} style={{ top: 24 }} destroyOnClose maskClosable={false}
    confirmLoading={saving} onOk={save} onCancel={() => { if (!savingRef.current) onCancel(); }}>
    <Form form={form} layout="vertical" onValuesChange={(changed) => setModel((current) => ({ ...current, ...changed }))}>
      <Tabs activeKey={tab} onChange={setTab} items={[
        { key: 'basic', label: '基本信息', forceRender: true, children: <div style={bodyStyle}>
          <Alert type="info" showIcon message={manualNewApi ? '填写本站真实模型标识并选择文本或图片；缺价默认文本每百万 Token 1 元、图片每张 1 元，均可修改。协议、能力和 SKU 自动初始化，保存前会实际调用。' : '一个真实模型只维护一行，首尾帧、多参考等用法在能力中配置。不同供应商和实际版本保持独立。'} style={{ marginBottom: 16 }} />
          <Row gutter={16}>
            {manualNewApi ? <>
              <Col span={24}><Form.Item name="modelType" label="模型类型" rules={[{ required: true }]}><Select options={[{ value: 'text', label: '文本生成' }, { value: 'image', label: '图片生成' }]} /></Form.Item></Col>
              <Col span={24}><Form.Item name="realModelCode" label="上游模型标识" rules={[{ required: true, whitespace: true }]}><Input maxLength={255} placeholder="与上游 /v1/models 返回的 id 一致" /></Form.Item></Col>
              {manualNewApiImage
                ? <Col span={12}><Form.Item name="newApiImagePrice" label="图片价（人民币/张）" rules={[{ required: true }]}><InputNumber min={0} max={1000000} precision={6} style={{ width: '100%' }} /></Form.Item></Col>
                : <><Col span={12}><Form.Item name="newApiInputPrice" label="输入价（人民币/百万 Token）" rules={[{ required: true }]}><InputNumber min={0} max={1000000} precision={6} style={{ width: '100%' }} /></Form.Item></Col>
                  <Col span={12}><Form.Item name="newApiOutputPrice" label="输出价（人民币/百万 Token）" rules={[{ required: true }]}><InputNumber min={0} max={1000000} precision={6} style={{ width: '100%' }} /></Form.Item></Col></>}
            </> : <>
            <Col span={12}><Form.Item name="modelName" label="模型名称" rules={[{ required: true, whitespace: true }]}><Input placeholder="如：可灵 3.0 Omni" maxLength={100} /></Form.Item></Col>
            <Col span={12}><Form.Item name="modelType" label="模型分类" rules={[{ required: true }]}><Select disabled={Boolean(data?.id)} options={MODEL_TYPE_OPTIONS.map((item) => ({ value: item.value, label: item.label }))} /></Form.Item></Col>
            <Col span={12}><Form.Item name="modelCode" label="平台模型编码" rules={[{ required: true, whitespace: true }]} tooltip="系统内唯一的稳定引用，不按业务能力添加后缀。"><Input disabled={Boolean(data?.id)} maxLength={100} placeholder="如：kling-3-omni" /></Form.Item></Col>
            <Col span={12}><Form.Item name="realModelCode" label="真实模型标识" rules={[{ required: true, whitespace: true }]} tooltip="填写原厂真实模型及版本标识。渠道 Endpoint ID、req_key 等路由信息在调用配置中维护。"><Input maxLength={255} placeholder="如：kling-3.0-omni" /></Form.Item></Col>
            </>}
            <Col span={24}>
              <div style={{ border: '1px solid #dbeafe', borderRadius: 8, background: '#f8fbff', padding: '14px 16px', marginBottom: 16 }}>
                <div style={{ fontWeight: 600, marginBottom: 4 }}>上游图片访问兼容</div>
                <div style={{ color: '#64748b', fontSize: 13, marginBottom: 14 }}>
                  仅当上游无法读取当前对象存储地址时启用。系统自有图片会改用站长配置的资源访问域名，不生成临时签名且移除查询参数，再完整编码并代入代理模板。
                </div>
                <Form.Item name="imageUrlProxyEnabled" label="启用图片代理拼接" valuePropName="checked" style={{ marginBottom: 12 }}>
                  <Switch checkedChildren="已启用" unCheckedChildren="已关闭" />
                </Form.Item>
                <Form.Item noStyle shouldUpdate={(previous, current) => previous.imageUrlProxyEnabled !== current.imageUrlProxyEnabled}>
                  {({ getFieldValue }) => getFieldValue('imageUrlProxyEnabled') ? <>
                    <Form.Item name="imageUrlProxyTemplate" label="图片代理 URL 模板" required
                      dependencies={['imageUrlProxyEnabled']}
                      rules={[{ validator: async (_, value) => {
                        const error = validateImageProxyTemplate(Boolean(form.getFieldValue('imageUrlProxyEnabled')), value);
                        if (error) throw new Error(error);
                      } }]}
                      extra="必须使用绝对 HTTPS 地址并包含唯一 {url} 占位符，例如：https://images.weserv.nl/?url={url}&output=jpg">
                      <Input maxLength={1000} showCount placeholder="https://proxy.example.com/image?url={url}" />
                    </Form.Item>
                    <Alert type="warning" showIcon message="代理服务可以读取资源访问域名中的图片内容，请只填写可信服务。该配置仅处理发送给此模型的图片；视频、音频、文档、回调和生成结果地址不会处理。" />
                  </> : null}
                </Form.Item>
              </div>
            </Col>
            <Col span={24}><Form.Item name="logoUrl" label="模型图标" extra="可选。未上传时使用所属服务商图标；服务商也未上传时使用内置图标。"><ImageUpload maxCount={1} maxSize={5} accept="image/*" /></Form.Item>{!model.logoUrl && resolveProviderLogo(provider?.providerCode, provider?.logoUrl) && <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: -16, marginBottom: 12, color: '#64748b' }}><img src={resolveProviderLogo(provider?.providerCode, provider?.logoUrl)} width={24} height={24} alt="默认模型图标" style={{ objectFit: 'contain' }} />当前默认图标</div>}</Col>
            <Col span={24}><Form.Item name="remark" label="备注"><Input.TextArea rows={2} /></Form.Item></Col>
          </Row>
        </div> },
        ...(!manualNewApi ? [{ key: 'capabilities', label: billingTabLabel, children: <div style={bodyStyle}><ModelTemplatePicker modelId={model.id} current={definitions} modelType={model.modelType || ''} upstreamModel={model.realModelCode || model.modelCode} onChange={setDefinitions} /><ModelCapabilitiesEditor value={definitions} modelType={model.modelType || ''} upstreamModel={model.realModelCode || model.modelCode} onChange={setDefinitions} /></div> }] : []),
        { key: 'operations', label: '统一运营配置', forceRender: true, children: <div style={bodyStyle}><Row gutter={16}>
          <Col span={12}><Form.Item name="status" label="模型状态"><Select options={ENABLE_STATUS_OPTIONS.map((item) => ({ value: item.value, label: item.label }))} /></Form.Item></Col>
          <Col span={12}><Form.Item name="priority" label="调度优先级"><InputNumber min={1} max={999} style={{ width: '100%' }} /></Form.Item></Col>
          <Col span={12}><Form.Item name="billingMultiplier" label="单模型倍率" rules={[{ required: true }]} tooltip="应用于这个模型的所有能力；具体规格原价在能力的 SKU 中维护。"><InputNumber min={0.01} precision={4} style={{ width: '100%' }} /></Form.Item></Col>
          <Col span={12}><Form.Item name="isFree" label="免费使用" valuePropName="checked"><Switch checkedChildren="免费" unCheckedChildren="正常收费" /></Form.Item></Col>
          <Col span={12}><Form.Item name="maxConcurrency" label="模型并发上限" tooltip="所有能力共享。留空时使用供应商与全局限制。"><InputNumber min={1} max={1000} style={{ width: '100%' }} /></Form.Item></Col>
        </Row></div> },
        ...(canEditBindings && !manualNewApi ? [{ key: 'business', label: '业务绑定', children: <div style={bodyStyle}><ModelBusinessBindingEditor value={model.businessBindings || []} capabilities={definitions} modelType={model.modelType || ''} onChange={(businessBindings) => setModel((current) => ({ ...current, businessBindings }))} /></div> }] : [])
      ]} />
    </Form>
  </Modal>;
}
