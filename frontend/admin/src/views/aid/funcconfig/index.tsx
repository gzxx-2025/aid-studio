import React, { useEffect, useRef, useState } from 'react';
import { Button, Card, Col, Form, Input, Modal, Popconfirm, Row, Select, Space, Table, Tag, message } from 'antd';
import { AppstoreOutlined, DeleteOutlined, DownloadOutlined, EditOutlined, PlusOutlined, RedoOutlined, SearchOutlined } from '@ant-design/icons';
import {
  listFuncconfig, getFuncconfig, addFuncconfig, updateFuncconfig, delFuncconfig,
  listModelForFuncconfig
} from '@/api/aid/funcconfig';
import { listProvider } from '@/api/aid/aimanage';
import { MODEL_TYPE_OPTIONS, GENERATE_MODE_OPTIONS, INPUT_REQUIREMENT_OPTIONS, getLabelByValue } from '@/utils/enums';
import Auth from '@/components/Auth';
import SectionTitle from '@/components/SectionTitle';
import { download } from '@/utils/request';
import ModelPoolSelector, { modelMatchesBusinessContext, type PoolModel } from './ModelPoolSelector';
import FunctionCapabilityEditor from './FunctionCapabilityEditor';
import { confirmModelPoolRemoval } from './confirmModelPoolRemoval';
import { normalizeFunctionCapabilityBindings, usesStructuredCapabilities } from './functionCapabilityBindings';
import type { BusinessModelBinding } from '../aimanage/ModelBusinessBindingEditor';

const STATUS_OPTIONS = [
  { label: '启用', value: '0' },
  { label: '停用', value: '1' }
];

export default function FuncconfigPage() {
  const [loading, setLoading] = useState(false);
  const [list, setList] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<any>({ pageNum: 1, pageSize: 10 });
  const [searchForm] = Form.useForm();
  const [modelPool, setModelPool] = useState<PoolModel[]>([]);
  const [providerNameMap, setProviderNameMap] = useState<Record<number, string>>({});
  const [dlgOpen, setDlgOpen] = useState(false);
  const [dlgTitle, setDlgTitle] = useState('');
  const [form] = Form.useForm();
  const watchedModelType = Form.useWatch('modelType', form);
  const watchedGenerateMode = Form.useWatch('generateMode', form);
  const [selectedModels, setSelectedModels] = useState<PoolModel[]>([]);
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  const [modelBindings, setModelBindings] = useState<BusinessModelBinding[]>([]);
  const [activeCapabilityModel, setActiveCapabilityModel] = useState<number>();
  const [editingId, setEditingId] = useState<any>(null);
  const [editingData, setEditingData] = useState<any>(null);

  const loadPool = async () => {
    try {
      // 全量加载（含停用模型）：停用模型需在列表/已选中标识展示而非变成“未知”，
      // 且编辑保存时不能丢失停用模型的原有配置，模型恢复后配置原样生效
      const res: any = await listModelForFuncconfig({ pageNum: 1, pageSize: 999 });
      setModelPool(res.rows || []);
      return res.rows || [];
    } catch { setModelPool([]); return []; }
  };

  /** 服务商 id→名称映射（模型池标签展示用，加载失败不阻断） */
  const loadProviders = async () => {
    try {
      const res: any = await listProvider({ pageNum: 1, pageSize: 999 });
      const map: Record<number, string> = {};
      (res.rows || []).forEach((p: any) => { if (p.id != null) map[p.id] = p.providerName || p.providerCode || `#${p.id}`; });
      setProviderNameMap(map);
    } catch { /* 忽略：仅影响标签展示 */ }
  };

  const loadList = async () => {
    setLoading(true);
    try {
      if (modelPool.length === 0) await loadPool();
      const res: any = await listFuncconfig(query);
      const rows = (res.rows || []).map((r: any) => {
        let ids: number[] = [];
        try { ids = r.modelIds ? JSON.parse(r.modelIds) : []; } catch {}
        return { ...r, _parsedIds: ids };
      });
      setList(rows);
      setTotal(res.total || 0);
    } finally { setLoading(false); }
  };
  useEffect(() => { loadList(); }, [query]);
  useEffect(() => { loadProviders(); }, []);

  const resolveModelName = (id: number) => {
    const m = modelPool.find((x) => x.id === id);
    return m ? (m.modelName || m.modelCode || `#${id}`) : `#${id}`;
  };

  const openAdd = async () => {
    await loadPool();
    form.resetFields();
    setEditingId(null);
    setEditingData(null);
    form.setFieldsValue({ status: '0' });
    setSelectedModels([]);
    setModelBindings([]);
    setActiveCapabilityModel(undefined);
    setDlgTitle('新增功能配置');
    setDlgOpen(true);
  };

  const openEdit = async (row: any) => {
    const [pool, detail]: any[] = await Promise.all([loadPool(), getFuncconfig(row.id)]);
    const data = detail.data || detail;
    form.resetFields();
    form.setFieldsValue(data);
    setEditingId(data.id);
    setEditingData(data);
    let ids: number[] = [];
    try { ids = data.modelIds ? JSON.parse(data.modelIds) : []; } catch {}
    const byId = new Map((pool || []).map((m: any) => [m.id, m]));
    const seen = new Set<number>();
    // 已删除模型不再作为可编辑关系展示；服务端与升级 SQL 会同步清理悬空引用。
    const hydrated = ids
      .filter((id) => typeof id === 'number' && id > 0 && !seen.has(id) && seen.add(id))
      .map((id) => byId.get(id) as PoolModel | undefined)
      .filter((model): model is PoolModel => Boolean(model));
    setSelectedModels(hydrated);
    setModelBindings(normalizeFunctionCapabilityBindings(hydrated, data.modelBindings || []));
    setActiveCapabilityModel(undefined);
    setDlgTitle('修改功能配置');
    setDlgOpen(true);
  };

  const handleDelete = async (row: any) => {
    await delFuncconfig(row.id);
    message.success('删除成功');
    loadList();
  };

  const handleExport = () => {
    download('/aid/funcconfig/export', query, `funcconfig_${Date.now()}.xlsx`);
  };

  const handleSave = async () => {
    if (savingRef.current) return;
    savingRef.current = true;
    try {
    const values = await form.validateFields();
    if (selectedModels.length === 0) { message.error('请至少选择一个可用模型'); return; }
    const incompatible = selectedModels.filter((model) => !modelMatchesBusinessContext(model, values.modelType, values.generateMode));
    if (incompatible.length > 0) {
      message.error(`模型与当前业务大类或生成模式不兼容：${incompatible.map((model) => model.modelName || model.modelCode).join('、')}`);
      return;
    }
    const modelIds = JSON.stringify(selectedModels.map((m) => m.id));
    for (const model of selectedModels.filter(usesStructuredCapabilities)) {
      if (modelBindings.filter((row) => row.modelId === model.id && row.defaultCapability).length !== 1) {
        setActiveCapabilityModel(model.id);
        requestAnimationFrame(() => document.getElementById(`model-capability-${model.id}`)?.scrollIntoView({ block: 'center' }));
        message.error(`请选择 ${model.modelName} 的业务能力及默认能力`); return;
      }
    }
    const bindings = modelBindings.filter((row) => selectedModels.some((model) => model.id === row.modelId)).map((row) => ({ ...row, funcCode: values.funcCode }));
    setSaving(true);
    try {
      if (editingId) {
        const oldIds: number[] = JSON.parse(editingData?.modelIds || '[]');
        const removed = oldIds.filter((id) => !selectedModels.some((model) => model.id === id));
        const replacements = await confirmModelPoolRemoval(removed, [Number(editingId)], selectedModels.map((model) => model.modelCode));
        if (!replacements) return;
        await updateFuncconfig({ ...(editingData || {}), ...values, id: editingId, modelIds, modelBindings: bindings, removedModelReplacementCode: replacements[editingId] || null });
        message.success('修改成功');
      } else {
        await addFuncconfig({ ...values, modelIds, modelBindings: bindings });
        message.success('新增成功');
      }
      setDlgOpen(false);
      loadList();
    } finally { setSaving(false); }
    } finally { savingRef.current = false; }
  };

  const columns: any[] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '功能名称', dataIndex: 'funcName', width: 160, render: (v: string) => <span style={{ fontWeight: 500 }}>{v}</span> },
    { title: '功能编码', dataIndex: 'funcCode', width: 200, ellipsis: true,
      render: (v: string) => <code className="code-text">{v}</code> },
    { title: '模型大类', dataIndex: 'modelType', width: 110,
      render: (v: string) => v ? <Tag color="purple" style={{ borderRadius: 6 }}>{getLabelByValue(MODEL_TYPE_OPTIONS, v, '--')}</Tag> : <span style={{ color: '#94a3b8' }}>--</span> },
    { title: '生成模式', dataIndex: 'generateMode', width: 130,
      render: (v: string) => v ? <Tag color="cyan" style={{ borderRadius: 6 }}>{getLabelByValue(GENERATE_MODE_OPTIONS, v, '--')}</Tag> : <span style={{ color: '#94a3b8' }}>--</span> },
    { title: '可选模型', key: 'models', render: (_: any, r: any) => {
      const existingIds = new Set(modelPool.map((model) => model.id));
      const ids: number[] = (r._parsedIds || []).filter((id: number) => existingIds.has(id));
      if (!ids.length) return <span style={{ color: '#94a3b8' }}>--</span>;
      const defaultId = ids.find((id) => {
        const model = modelPool.find((candidate) => candidate.id === id);
        if (!model || model.status === '1') return false;
        if (!usesStructuredCapabilities(model)) return true;
        return (r.modelBindings || []).filter((binding: BusinessModelBinding) => binding.modelId === id && binding.defaultCapability).length === 1;
      });
      return <Space wrap size={[4, 6]}>{ids.map((id, idx) => {
        const m = modelPool.find((x) => x.id === id);
        const req = m?.inputRequirement;
        // 已删除模型由服务端和升级 SQL 清理；已停用模型继续置灰保留关系。
        const disabled = m?.status === '1';
        const color = disabled ? 'default' : 'geekblue';
        return (
          <Tag key={idx} color={color} style={{ borderRadius: 6, margin: 0 }}>
            {resolveModelName(id)}
            {id === defaultId && <span style={{ marginLeft: 4, fontSize: 11 }}>[默认]</span>}
            {disabled && <span style={{ marginLeft: 4, opacity: 0.75, fontSize: 11 }}>[已停用]</span>}
            {req && req !== 'text_only' && (
              <span style={{ marginLeft: 4, opacity: 0.75, fontSize: 11 }}>
                [{getLabelByValue(INPUT_REQUIREMENT_OPTIONS, req)}]
              </span>
            )}
          </Tag>
        );
      })}</Space>;
    } },
    { title: '状态', dataIndex: 'status', width: 100, render: (v: string) => {
      const hit = STATUS_OPTIONS.find((d) => d.value === v);
      const label = hit?.label || v;
      const color = v === '0' ? 'success' : 'default';
      return <Tag color={color} style={{ borderRadius: 6 }}>{label}</Tag>;
    } },
    { title: '备注', dataIndex: 'remark', ellipsis: true, width: 180 },
    { title: '操作', key: 'ops', width: 150, fixed: 'right' as const, render: (_: any, r: any) => (
      <Space size={0}>
        <Auth permission="aid:funcconfig:edit"><Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>修改</Button></Auth>
        <Auth permission="aid:funcconfig:remove"><Popconfirm title="确认删除？" onConfirm={() => handleDelete(r)}><Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button></Popconfirm></Auth>
      </Space>
    ) }
  ];

  return (
    <div className="crud-page">
      <Card className="page-card" bordered={false}>
        <Form form={searchForm} layout="inline" onFinish={(v) => setQuery({ ...query, ...v, pageNum: 1 })} style={{ rowGap: 8 }}>
          <Form.Item name="funcName" label="功能名称"><Input allowClear style={{ width: 160 }} placeholder="请输入" /></Form.Item>
          <Form.Item name="funcCode" label="功能编码"><Input allowClear style={{ width: 180 }} placeholder="请输入" /></Form.Item>
          <Form.Item name="modelType" label="模型大类"><Select allowClear style={{ width: 140 }} options={MODEL_TYPE_OPTIONS.map((o) => ({ label: o.label, value: o.value }))} placeholder="请选择" /></Form.Item>
          <Form.Item name="generateMode" label="生成模式"><Select allowClear style={{ width: 160 }} options={GENERATE_MODE_OPTIONS.map((o) => ({ label: o.label, value: o.value }))} placeholder="请选择" /></Form.Item>
          <Form.Item name="status" label="状态"><Select allowClear style={{ width: 120 }} options={STATUS_OPTIONS} placeholder="请选择" /></Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" icon={<SearchOutlined />} htmlType="submit">搜索</Button>
              <Button icon={<RedoOutlined />} onClick={() => { searchForm.resetFields(); setQuery({ pageNum: 1, pageSize: 10 }); }}>重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      <Card className="page-card" bordered={false}>
        <div className="crud-page__toolbar">
          <Space>
            <Auth permission="aid:funcconfig:add"><Button type="primary" icon={<PlusOutlined />} onClick={openAdd}>新增</Button></Auth>
            <Auth permission="aid:funcconfig:export"><Button icon={<DownloadOutlined />} onClick={handleExport}>导出</Button></Auth>
          </Space>
          <div className="crud-page__stats">
            <span>共 {total} 条</span>
          </div>
        </div>
        <Table rowKey="id" size="middle" loading={loading} dataSource={list} columns={columns} scroll={{ x: 1200 }}
          pagination={{ current: query.pageNum, pageSize: query.pageSize, total, showSizeChanger: true, showTotal: (t) => `共 ${t} 条`, onChange: (p, s) => setQuery({ ...query, pageNum: p, pageSize: s }) }}
        />
      </Card>

      <Modal open={dlgOpen} title={<Space><AppstoreOutlined style={{ color: '#2563eb' }} /><span>{dlgTitle}</span></Space>} onCancel={() => { if (!savingRef.current) setDlgOpen(false); }} onOk={handleSave} confirmLoading={saving} width={1180} styles={{ body: { maxHeight: '70vh', overflowY: 'auto', paddingRight: 8 } }} destroyOnClose maskClosable={false}>
        <Form form={form} layout="vertical" style={{ marginTop: 8 }}>
          <Row gutter={16}>
            <Col span={12}><Form.Item name="funcName" label="功能名称" rules={[{ required: true, message: '功能名称不能为空' }]}><Input placeholder="如：图片编辑、图片高清" /></Form.Item></Col>
            <Col span={12}><Form.Item name="funcCode" label="功能编码" rules={[{ required: true, message: '功能编码不能为空' }]}><Input placeholder="如：image_edit / image_upscale" /></Form.Item></Col>
            <Col span={12}><Form.Item name="modelType" label="模型大类"><Select allowClear options={MODEL_TYPE_OPTIONS.map((o) => ({ label: o.label, value: o.value }))} placeholder="请选择模型大类" /></Form.Item></Col>
            <Col span={12}><Form.Item name="generateMode" label="生成模式"><Select allowClear options={GENERATE_MODE_OPTIONS.map((o) => ({ label: o.label, value: o.value }))} placeholder="请选择生成模式" /></Form.Item></Col>
          </Row>

          <SectionTitle title="可选模型" desc="必选；可按「输入要求」区分图片必传 / 图片可选等类型" />

          <ModelPoolSelector
            pool={modelPool}
            selected={selectedModels}
            defaultModelId={selectedModels.find((model) => model.status !== '1' && (!usesStructuredCapabilities(model)
              || modelBindings.filter((binding) => binding.modelId === model.id && binding.defaultCapability).length === 1))?.id}
            onChange={(models) => {
              setSelectedModels(models);
              setModelBindings((current) => normalizeFunctionCapabilityBindings(models, current));
            }}
            providerNameMap={providerNameMap}
            requiredModelType={watchedModelType}
            requiredGenerateMode={watchedGenerateMode}
          />
          <FunctionCapabilityEditor models={selectedModels} value={modelBindings} onChange={setModelBindings} activeModel={activeCapabilityModel} onActiveModelChange={setActiveCapabilityModel} />

          <Row gutter={16} style={{ marginTop: 16 }}>
            <Col span={12}><Form.Item name="status" label="状态" rules={[{ required: true }]}><Select options={STATUS_OPTIONS} placeholder="请选择状态" /></Form.Item></Col>
            <Col span={24}><Form.Item name="remark" label="备注"><Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} placeholder="请输入备注说明" /></Form.Item></Col>
          </Row>
        </Form>
      </Modal>
    </div>
  );
}
