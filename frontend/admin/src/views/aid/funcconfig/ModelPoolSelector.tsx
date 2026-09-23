import React, { useMemo, useState } from 'react';
import { Button, Empty, Input, Select, Space, Tag, Tooltip } from 'antd';
import {
  ArrowDownOutlined, ArrowUpOutlined, ClearOutlined, DeleteOutlined,
  PlusOutlined, SearchOutlined, VerticalAlignTopOutlined
} from '@ant-design/icons';
import {
  MODEL_TYPE_OPTIONS, GENERATE_MODE_OPTIONS, INPUT_REQUIREMENT_OPTIONS,
  getLabelByValue, getAntdTagColor
} from '@/utils/enums';

/** 模型池行数据（来自 /aid/aidmodel/list，含后端推导的 inputRequirement） */
export interface PoolModel {
  id: number;
  modelCode: string;
  modelName: string;
  modelType: string;
  generateMode?: string;
  /** 输入要求：text_only / image_optional / image_required / video_required */
  inputRequirement?: string;
  providerId?: number;
  status?: string;
  [key: string]: any;
}

interface Props {
  /** 全量候选模型池 */
  pool: PoolModel[];
  /** 已选模型（有序） */
  selected: PoolModel[];
  onChange: (next: PoolModel[]) => void;
  /** 服务端选择规则下的唯一默认模型。 */
  defaultModelId?: number;
  /** 服务商 id → 名称映射（可空，缺失时不展示服务商标签） */
  providerNameMap?: Record<number, string>;
  /** 当前业务声明的模型大类；候选池不会展示其他媒体类型。 */
  requiredModelType?: string;
  /** 当前业务声明的生成模式；候选池不会展示不支持该模式的模型。 */
  requiredGenerateMode?: string;
}

/** 筛选条件 */
interface PoolFilter {
  modelType: string | null;
  generateMode: string | null;
  inputRequirement: string | null;
  providerId: number | null;
  keyword: string;
}

const EMPTY_FILTER: PoolFilter = {
  modelType: null, generateMode: null, inputRequirement: null, providerId: null, keyword: ''
};

export function modelMatchesBusinessContext(
  model: PoolModel,
  requiredModelType?: string,
  requiredGenerateMode?: string
) {
  if (requiredModelType && model.modelType !== requiredModelType) return false;
  if (!requiredGenerateMode) return true;
  const capabilities = Array.isArray(model.capabilities) ? model.capabilities : [];
  if (capabilities.length > 0) {
    return capabilities.some((capability: { code?: string; generateMode?: string; enabled?: boolean }) =>
      capability.enabled !== false
      && (capability.generateMode === requiredGenerateMode || capability.code === requiredGenerateMode));
  }
  return model.generateMode === requiredGenerateMode;
}

/** 模型标签行：状态 / 大类 / 输入要求 / 生成模式 / 服务商 */
function ModelTags({ m, providerName }: { m: PoolModel; providerName?: string }) {
  return (
    <Space size={4} wrap>
      {m.status === '1' && <Tag style={{ borderRadius: 6, margin: 0 }} color="default">已停用</Tag>}
      {m.modelType && (
        <Tag style={{ borderRadius: 6, margin: 0 }} color={getAntdTagColor(MODEL_TYPE_OPTIONS, m.modelType)}>
          {getLabelByValue(MODEL_TYPE_OPTIONS, m.modelType)}
        </Tag>
      )}
      {m.inputRequirement && (
        <Tag style={{ borderRadius: 6, margin: 0 }} color={getAntdTagColor(INPUT_REQUIREMENT_OPTIONS, m.inputRequirement)}>
          {getLabelByValue(INPUT_REQUIREMENT_OPTIONS, m.inputRequirement)}
        </Tag>
      )}
      {!m.capabilities?.length && m.generateMode && (
        <Tag style={{ borderRadius: 6, margin: 0 }} color="blue">
          {getLabelByValue(GENERATE_MODE_OPTIONS, m.generateMode)}
        </Tag>
      )}
      {m.capabilities?.map((capability: { code: string; label: string; enabled: boolean }) => capability.enabled && <Tag key={capability.code}>{capability.label}</Tag>)}
      {providerName && (
        <Tag style={{ borderRadius: 6, margin: 0, color: '#64748b', background: '#f8fafc', borderColor: '#e2e8f0' }}>
          {providerName}
        </Tag>
      )}
    </Space>
  );
}

/**
 * 模型池选择器：左侧候选（支持关键词/大类/生成模式/输入要求/服务商组合筛选，整行点击添加、
 * 一键添加筛选结果），右侧已选（保存顺序 = 展示顺序，支持置顶/上移/下移/移除/清空）。
 */
export default function ModelPoolSelector({
  pool, selected, onChange, defaultModelId, providerNameMap = {},
  requiredModelType, requiredGenerateMode
}: Props) {
  const [filter, setFilter] = useState<PoolFilter>({ ...EMPTY_FILTER });

  const selectedIds = useMemo(() => new Set(selected.map((m) => m.id)), [selected]);

  /** 池内实际存在的服务商下拉选项 */
  const providerOptions = useMemo(() => {
    const ids = Array.from(new Set(pool.map((m) => m.providerId).filter((v): v is number => v != null)));
    return ids
      .map((id) => ({ value: id, label: providerNameMap[id] || `服务商#${id}` }))
      .sort((a, b) => a.label.localeCompare(b.label, 'zh-CN'));
  }, [pool, providerNameMap]);

  const filtered = useMemo(() => {
    const kw = filter.keyword.trim().toLowerCase();
    return pool
      .filter((m) => !selectedIds.has(m.id))
      .filter((m) => m.status !== '1')
      .filter((m) => modelMatchesBusinessContext(m, requiredModelType, requiredGenerateMode))
      .filter((m) => !filter.modelType || m.modelType === filter.modelType)
      .filter((m) => !filter.generateMode || (m.capabilities?.length ? m.capabilities.some((capability: { generateMode: string }) => capability.generateMode === filter.generateMode) : m.generateMode === filter.generateMode))
      .filter((m) => !filter.inputRequirement || m.inputRequirement === filter.inputRequirement)
      .filter((m) => filter.providerId == null || m.providerId === filter.providerId)
      .filter((m) => {
        if (!kw) return true;
        const providerName = (m.providerId != null && providerNameMap[m.providerId]) || '';
        return (m.modelCode || '').toLowerCase().includes(kw)
          || (m.modelName || '').toLowerCase().includes(kw)
          || providerName.toLowerCase().includes(kw);
      });
  }, [pool, selectedIds, filter, providerNameMap, requiredModelType, requiredGenerateMode]);

  const hasFilter = filter.keyword.trim() !== '' || filter.modelType != null
    || filter.generateMode != null || filter.inputRequirement != null || filter.providerId != null;

  const addOne = (m: PoolModel) => onChange([...selected, m]);
  const addAllFiltered = () => onChange([...selected, ...filtered]);
  const removeAt = (idx: number) => onChange(selected.filter((_, i) => i !== idx));
  const clearAll = () => onChange([]);
  const moveTo = (idx: number, target: number) => {
    if (target < 0 || target >= selected.length || target === idx) return;
    const arr = [...selected];
    const [item] = arr.splice(idx, 1);
    arr.splice(target, 0, item);
    onChange(arr);
  };

  const listBoxStyle: React.CSSProperties = {
    height: 380, overflow: 'auto', background: '#fff',
    borderRadius: 8, border: '1px solid rgba(15, 23, 42, 0.06)'
  };
  const rowStyle: React.CSSProperties = {
    padding: '9px 12px', borderBottom: '1px solid rgba(15, 23, 42, 0.04)',
    display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8
  };

  return (
    <div style={{
      display: 'flex', gap: 14, padding: 14, borderRadius: 12,
      background: 'linear-gradient(180deg, #fafbff 0%, #f8fafc 100%)',
      border: '1px solid rgba(15, 23, 42, 0.06)'
    }}>
      {/* 左：候选区 */}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
          <span style={{ fontWeight: 600, color: '#475569', fontSize: 13 }}>候选模型</span>
          <Space size={8}>
            {(requiredModelType || requiredGenerateMode) && (
              <Tag color="blue" style={{ borderRadius: 6, margin: 0 }}>
                业务约束：{getLabelByValue(MODEL_TYPE_OPTIONS, requiredModelType || '', requiredModelType || '全部大类')}
                {requiredGenerateMode ? ` / ${getLabelByValue(GENERATE_MODE_OPTIONS, requiredGenerateMode, requiredGenerateMode)}` : ''}
              </Tag>
            )}
            <span style={{ color: '#94a3b8', fontSize: 12 }}>{filtered.length} 个可添加</span>
            {filtered.length > 0 && hasFilter && (
              <Button size="small" type="link" style={{ padding: 0, height: 'auto' }} onClick={addAllFiltered}>
                全部添加
              </Button>
            )}
          </Space>
        </div>
        <Space size={6} wrap style={{ marginBottom: 10 }}>
          <Input
            size="small" style={{ width: 190 }} allowClear autoFocus
            placeholder="搜索名称 / 代码 / 服务商"
            prefix={<SearchOutlined style={{ color: '#94a3b8' }} />}
            value={filter.keyword}
            onChange={(e) => setFilter({ ...filter, keyword: e.target.value })}
          />
          <Select
            size="small" style={{ width: 96 }} allowClear placeholder="大类"
            value={filter.modelType}
            onChange={(v) => setFilter({ ...filter, modelType: v ?? null, generateMode: null })}
            options={MODEL_TYPE_OPTIONS.map((o) => ({ label: o.label, value: o.value }))}
          />
          <Select
            size="small" style={{ width: 128 }} allowClear placeholder="输入要求"
            value={filter.inputRequirement}
            onChange={(v) => setFilter({ ...filter, inputRequirement: v ?? null })}
            options={INPUT_REQUIREMENT_OPTIONS.map((o) => ({ label: o.label, value: o.value }))}
          />
          <Select
            size="small" style={{ width: 128 }} allowClear placeholder="生成模式"
            value={filter.generateMode}
            onChange={(v) => setFilter({ ...filter, generateMode: v ?? null })}
            options={GENERATE_MODE_OPTIONS
              .filter((o) => {
                // 生成模式跟随大类联动，未选大类时全量展示
                if (!filter.modelType) return true;
                if (filter.modelType === 'text') return o.value === 'text';
                if (filter.modelType === 'audio') return o.value === 'audio';
                if (filter.modelType === 'image') return String(o.value).includes('image');
                if (filter.modelType === 'video') return String(o.value).includes('video') || o.value === 'multi_frame';
                return true;
              })
              .map((o) => ({ label: o.label, value: o.value }))}
          />
          <Select
            size="small" style={{ width: 128 }} allowClear placeholder="服务商" showSearch optionFilterProp="label"
            value={filter.providerId}
            onChange={(v) => setFilter({ ...filter, providerId: v ?? null })}
            options={providerOptions}
          />
          {hasFilter && (
            <Tooltip title="清空筛选">
              <Button size="small" icon={<ClearOutlined />} onClick={() => setFilter({ ...EMPTY_FILTER })} />
            </Tooltip>
          )}
        </Space>
        <div style={listBoxStyle}>
          {filtered.map((m) => {
            const providerName = m.providerId != null ? providerNameMap[m.providerId] : undefined;
            return (
              <div
                key={m.id} style={{ ...rowStyle, cursor: 'pointer' }}
                onClick={() => addOne(m)}
                onMouseEnter={(e) => (e.currentTarget.style.background = 'rgba(37, 99, 235, 0.04)')}
                onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
              >
                <div style={{ minWidth: 0, flex: 1 }}>
                  <div style={{ fontSize: 13, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    <b>{m.modelName}</b>
                    <span style={{ color: '#94a3b8', fontSize: 12, marginLeft: 6 }}>({m.modelCode})</span>
                  </div>
                  <div style={{ marginTop: 4 }}>
                    <ModelTags m={m} providerName={providerName} />
                  </div>
                </div>
                <PlusOutlined style={{ color: '#2563eb' }} />
              </div>
            );
          })}
          {filtered.length === 0 && (
            <div style={{ padding: 40, textAlign: 'center' }}>
              <Empty description={hasFilter ? '无匹配模型，试试调整筛选条件' : '模型池为空'} image={Empty.PRESENTED_IMAGE_SIMPLE} />
            </div>
          )}
        </div>
      </div>

      {/* 右：已选区 */}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
          <span style={{ fontWeight: 600, color: '#475569', fontSize: 13 }}>
            已选模型 <span style={{ fontWeight: 400, color: '#64748b', fontSize: 12 }}>（首个可用且已配默认能力的模型为默认）</span>
          </span>
          <Space size={8}>
            <Tag color="blue" style={{ borderRadius: 6, fontWeight: 500, margin: 0 }}>{selected.length} 项</Tag>
            {selected.length > 0 && (
              <Button size="small" type="link" danger style={{ padding: 0, height: 'auto' }} onClick={clearAll}>
                清空
              </Button>
            )}
          </Space>
        </div>
        <div style={{ ...listBoxStyle, height: 422 }}>
          {selected.map((m, idx) => {
            const providerName = m.providerId != null ? providerNameMap[m.providerId] : undefined;
            return (
              <div key={m.id} style={rowStyle}>
                <div style={{ minWidth: 0, flex: 1 }}>
                  <div style={{ fontSize: 13, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    <span style={{
                      display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                      minWidth: 22, height: 20, padding: '0 6px', borderRadius: 10,
                      background: 'linear-gradient(135deg, #2563eb, #7c3aed)',
                      color: '#fff', fontSize: 11, fontWeight: 600, marginRight: 8
                    }}>{idx + 1}</span>
                    <b>{m.modelName}</b>
                    {m.id === defaultModelId && <Tag color="green" style={{ borderRadius: 6, marginLeft: 6, marginRight: 0 }}>默认模型</Tag>}
                    <span style={{ color: '#94a3b8', fontSize: 12, marginLeft: 6 }}>({m.modelCode})</span>
                  </div>
                  <div style={{ marginTop: 4, paddingLeft: 30 }}>
                    <ModelTags m={m} providerName={providerName} />
                  </div>
                </div>
                <Space size={4}>
                  <Tooltip title="置顶">
                    <Button size="small" aria-label={`将 ${m.modelName} 置顶`} icon={<VerticalAlignTopOutlined />} disabled={idx === 0} onClick={() => moveTo(idx, 0)} />
                  </Tooltip>
                  <Tooltip title="上移"><Button size="small" aria-label={`将 ${m.modelName} 上移`} icon={<ArrowUpOutlined />} disabled={idx === 0} onClick={() => moveTo(idx, idx - 1)} /></Tooltip>
                  <Tooltip title="下移"><Button size="small" aria-label={`将 ${m.modelName} 下移`} icon={<ArrowDownOutlined />} disabled={idx === selected.length - 1} onClick={() => moveTo(idx, idx + 1)} /></Tooltip>
                  <Tooltip title="移除"><Button size="small" danger aria-label={`移除 ${m.modelName}`} icon={<DeleteOutlined />} onClick={() => removeAt(idx)} /></Tooltip>
                </Space>
              </div>
            );
          })}
          {selected.length === 0 && (
            <div style={{ padding: 40, textAlign: 'center' }}>
              <Empty description="点击左侧候选模型即可添加" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
