import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Empty, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import type {
  ModelPoolBindingPool,
  ModelPoolBindingSnapshot,
  ModelPoolCapability,
  ModelPoolCapabilitySelection
} from '@/api/aid/aimanage';
import { GENERATE_MODE_OPTIONS, MODEL_TYPE_OPTIONS, getLabelByValue } from '@/utils/enums';
import type { Model } from './types';

export type ModelPoolBindingMode = 'bind' | 'unbind';

interface Props {
  open: boolean;
  mode: ModelPoolBindingMode;
  models: Model[];
  snapshot: ModelPoolBindingSnapshot;
  submitting: boolean;
  onCancel: () => void;
  onSubmit: (poolIds: number[], capabilitySelections: ModelPoolCapabilitySelection[]) => void;
}

interface PoolRow extends ModelPoolBindingPool {
  selectedBindingCount: number;
  unavailableReason?: string;
}

interface CapabilityRow {
  key: string;
  modelId: number;
  modelName: string;
  poolId: number;
  poolName: string;
  poolFuncCode: string;
  capabilities: ModelPoolCapability[];
}

interface CapabilityChoice {
  capabilityCodes: string[];
  defaultCapabilityCode?: string;
}

const relationKey = (modelId: number, poolId: number) => `${modelId}:${poolId}`;

/** 批量维护模型池关系；新增关系在同一弹窗中明确选择业务能力及默认能力。 */
export default function ModelPoolBindingModal({ open, mode, models, snapshot, submitting, onCancel, onSubmit }: Props) {
  const [selectedPoolIds, setSelectedPoolIds] = useState<React.Key[]>([]);
  const [capabilityChoices, setCapabilityChoices] = useState<Record<string, CapabilityChoice>>({});
  const modelIds = useMemo(() => models.map((model) => model.id).filter((id): id is number => id != null), [models]);
  const modelIdsKey = useMemo(() => modelIds.join(','), [modelIds]);
  const modelIdSet = useMemo(() => new Set(modelIds), [modelIds]);
  const selectedTypes = useMemo(() => new Set(models.map((model) => model.modelType)), [models]);
  const disabledModelCount = useMemo(() => models.filter((model) => model.status !== '0').length, [models]);
  const snapshotModelMap = useMemo(() => new Map(snapshot.models.map((model) => [model.id, model])), [snapshot.models]);
  const selectedPoolIdSet = useMemo(() => new Set(selectedPoolIds.map(Number)), [selectedPoolIds]);

  useEffect(() => {
    if (open) {
      setSelectedPoolIds([]);
      setCapabilityChoices({});
    }
  }, [open, mode, modelIdsKey]);

  const rows = useMemo<PoolRow[]>(() => snapshot.pools
    .map((pool) => {
      const selectedBindingCount = pool.modelIds.filter((id) => modelIdSet.has(id)).length;
      let unavailableReason: string | undefined;
      if (!pool.configurationValid) unavailableReason = '模型池配置异常，请先在模型池页面修复';
      else if (mode === 'bind' && (selectedTypes.size !== 1 || !selectedTypes.has(pool.modelType))) unavailableReason = '模型大类不一致';
      else if (mode === 'unbind' && selectedBindingCount === 0) unavailableReason = '所选模型均未绑定';
      else if (mode === 'unbind' && pool.status === '0' && pool.modelIds.every((id) => modelIdSet.has(id))) unavailableReason = '启用模型池不能移除全部模型';
      return { ...pool, selectedBindingCount, unavailableReason };
    })
    .filter((pool) => mode === 'bind' || pool.selectedBindingCount > 0),
  [snapshot.pools, modelIdSet, mode, selectedTypes]);

  const capabilityRows = useMemo<CapabilityRow[]>(() => {
    if (mode !== 'bind') return [];
    const result: CapabilityRow[] = [];
    rows.filter((pool) => selectedPoolIdSet.has(pool.id)).forEach((pool) => {
      models.forEach((model) => {
        if (model.id == null || pool.modelIds.includes(model.id)) return;
        const definitions = snapshotModelMap.get(model.id)?.capabilities || [];
        if (definitions.length === 0) return;
        const isMultiStoryboardPool = pool.funcCode === 'main_storyboard_video'
          || pool.funcCode === 'main_storyboard_video_multi_pro';
        const capabilities = definitions.filter((capability) => capability.enabled
          && !(isMultiStoryboardPool && capability.generateMode === 'start_end_to_video'));
        result.push({
          key: relationKey(model.id, pool.id),
          modelId: model.id,
          modelName: model.modelName || model.modelCode,
          poolId: pool.id,
          poolName: pool.funcName,
          poolFuncCode: pool.funcCode,
          capabilities
        });
      });
    });
    return result;
  }, [mode, rows, selectedPoolIdSet, models, snapshotModelMap]);

  useEffect(() => {
    setCapabilityChoices((current) => {
      const next: Record<string, CapabilityChoice> = {};
      capabilityRows.forEach((row) => {
        const retained = current[row.key];
        if (retained && retained.capabilityCodes.every((code) => row.capabilities.some((item) => item.code === code))) {
          next[row.key] = retained;
          return;
        }
        const defaults = row.capabilities.filter((capability) => capability.defaultCapability);
        const requiresExplicitChoice = row.poolFuncCode === 'main_storyboard_video'
          || row.poolFuncCode === 'main_storyboard_video_multi_pro';
        const initial = row.capabilities.length === 1 ? row.capabilities[0]
          : !requiresExplicitChoice && defaults.length === 1 ? defaults[0] : undefined;
        next[row.key] = {
          capabilityCodes: initial ? [initial.code] : [],
          defaultCapabilityCode: initial?.code
        };
      });
      return next;
    });
  }, [capabilityRows]);

  const validationErrors = useMemo(() => capabilityRows.flatMap((row) => {
    const choice = capabilityChoices[row.key];
    if (row.capabilities.length === 0) return [`模型“${row.modelName}”与模型池“${row.poolName}”没有兼容能力`];
    if (!choice?.capabilityCodes.length || !choice.defaultCapabilityCode
      || !choice.capabilityCodes.includes(choice.defaultCapabilityCode)) {
      return [`请选择模型“${row.modelName}”在模型池“${row.poolName}”中的能力和默认能力`];
    }
    return [];
  }), [capabilityRows, capabilityChoices]);

  const poolColumns = [
    {
      title: '模型池', key: 'pool', render: (_: unknown, row: PoolRow) => (
        <div>
          <Space size={6} wrap>
            <Typography.Text strong>{row.funcName}</Typography.Text>
            <Tag>{row.funcCode}</Tag>
            {row.status === '1' && <Tag color="default">已停用</Tag>}
            {row.staleModelCount > 0 && <Tag color="warning">已忽略 {row.staleModelCount} 个失效引用</Tag>}
          </Space>
          <div style={{ marginTop: 4, color: '#64748b', fontSize: 12 }}>
            {getLabelByValue(MODEL_TYPE_OPTIONS, row.modelType)}
            {row.generateMode ? ` · ${getLabelByValue(GENERATE_MODE_OPTIONS, row.generateMode)}` : ''}
          </div>
        </div>
      )
    },
    {
      title: '当前关系', key: 'binding', width: 140, render: (_: unknown, row: PoolRow) => {
        if (row.selectedBindingCount === modelIds.length && modelIds.length > 0) return <Tag color="success">全部已绑定</Tag>;
        if (row.selectedBindingCount > 0) return <Tag color="gold">已绑定 {row.selectedBindingCount}/{modelIds.length}</Tag>;
        return <Tag>尚未绑定</Tag>;
      }
    },
    { title: '池内模型', dataIndex: 'modelIds', width: 90, render: (ids: number[]) => `${ids.length} 个` },
    {
      title: '可操作性', key: 'availability', width: 220,
      render: (_: unknown, row: PoolRow) => row.unavailableReason
        ? <Typography.Text type="danger">{row.unavailableReason}</Typography.Text>
        : <Typography.Text type="success">可以操作</Typography.Text>
    }
  ];

  const capabilityColumns = [
    {
      title: '模型 / 模型池', key: 'relation', width: 280,
      render: (_: unknown, row: CapabilityRow) => (
        <Space direction="vertical" size={2}>
          <Typography.Text strong>{row.modelName}</Typography.Text>
          <Typography.Text type="secondary">{row.poolName}</Typography.Text>
        </Space>
      )
    },
    {
      title: '使用能力', key: 'capabilities',
      render: (_: unknown, row: CapabilityRow) => (
        <Select
          aria-label={`${row.modelName}在${row.poolName}中使用的能力`}
          mode="multiple"
          style={{ width: '100%' }}
          status={row.capabilities.length === 0 || !capabilityChoices[row.key]?.capabilityCodes.length ? 'error' : undefined}
          placeholder={row.capabilities.length === 0 ? '没有兼容能力' : '请选择能力'}
          disabled={row.capabilities.length === 0}
          value={capabilityChoices[row.key]?.capabilityCodes || []}
          options={row.capabilities.map((capability) => ({
            value: capability.code,
            label: capability.defaultCapability ? `${capability.label}（模型默认）` : capability.label
          }))}
          onChange={(capabilityCodes: string[]) => setCapabilityChoices((current) => {
            const previousDefault = current[row.key]?.defaultCapabilityCode;
            const declaredDefault = row.capabilities.find((item) => item.defaultCapability && capabilityCodes.includes(item.code))?.code;
            const defaultCapabilityCode = previousDefault && capabilityCodes.includes(previousDefault)
              ? previousDefault : declaredDefault || (capabilityCodes.length === 1 ? capabilityCodes[0] : undefined);
            return { ...current, [row.key]: { capabilityCodes, defaultCapabilityCode } };
          })}
        />
      )
    },
    {
      title: '默认能力', key: 'default', width: 220,
      render: (_: unknown, row: CapabilityRow) => {
        const choice = capabilityChoices[row.key];
        return (
          <Select
            aria-label={`${row.modelName}在${row.poolName}中的默认能力`}
            style={{ width: '100%' }}
            status={!choice?.defaultCapabilityCode ? 'error' : undefined}
            placeholder="请选择默认能力"
            disabled={!choice?.capabilityCodes.length}
            value={choice?.defaultCapabilityCode}
            options={(choice?.capabilityCodes || []).map((code) => ({
              value: code,
              label: row.capabilities.find((capability) => capability.code === code)?.label || code
            }))}
            onChange={(defaultCapabilityCode) => setCapabilityChoices((current) => ({
              ...current,
              [row.key]: { ...(current[row.key] || { capabilityCodes: [] }), defaultCapabilityCode }
            }))}
          />
        );
      }
    }
  ];

  const submit = () => {
    const selections = capabilityRows.map((row) => ({
      modelId: row.modelId,
      poolId: row.poolId,
      capabilityCodes: capabilityChoices[row.key]?.capabilityCodes || [],
      defaultCapabilityCode: capabilityChoices[row.key]?.defaultCapabilityCode || ''
    }));
    onSubmit(selectedPoolIds.map(Number), selections);
  };

  return (
    <Modal
      open={open}
      title={mode === 'bind' ? `绑定模型池（${models.length} 个模型）` : `移出模型池（${models.length} 个模型）`}
      okText={mode === 'bind' ? '确认绑定' : '确认移出'}
      cancelText="取消"
      width={1040}
      confirmLoading={submitting}
      okButtonProps={{ disabled: selectedPoolIds.length === 0 || validationErrors.length > 0 }}
      maskClosable={false}
      destroyOnClose
      onCancel={onCancel}
      onOk={submit}
    >
      <Alert
        type={mode === 'bind' ? 'info' : 'warning'}
        showIcon
        style={{ marginBottom: 14 }}
        message={mode === 'bind'
          ? '选择模型池后配置本次新增关系的能力。多参分镜池有多种可用能力时须手动选择；已有业务绑定保持不变。'
          : '移出不删除模型。有业务引用时，下一步预览影响并选择池内替代文本模型；不会自动更换模型，也不能清空启用模型池。'}
      />
      {mode === 'bind' && disabledModelCount > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 14 }}
          message={`所选模型中有 ${disabledModelCount} 个已停用；可以预先绑定，但恢复启用前不会参与调度。`}
        />
      )}
      <Table<PoolRow>
        rowKey="id"
        size="small"
        pagination={false}
        scroll={{ y: capabilityRows.length > 0 ? 260 : 420 }}
        dataSource={rows}
        columns={poolColumns}
        locale={{ emptyText: <Empty description={mode === 'bind' ? '没有可用的同类模型池' : '所选模型尚未绑定模型池'} /> }}
        rowSelection={{
          selectedRowKeys: selectedPoolIds,
          onChange: setSelectedPoolIds,
          getCheckboxProps: (row) => ({ disabled: Boolean(row.unavailableReason), title: row.unavailableReason })
        }}
      />
      {mode === 'bind' && capabilityRows.length > 0 && (
        <section aria-label="新增关系能力配置" style={{ marginTop: 18 }}>
          <Space direction="vertical" size={4} style={{ marginBottom: 10 }}>
            <Typography.Text strong>新增关系能力</Typography.Text>
            <Typography.Text type="secondary">仅影响本次新增的模型池关系；请核对所选能力及默认能力后再绑定。</Typography.Text>
          </Space>
          {validationErrors.length > 0 && (
            <Alert type="error" showIcon style={{ marginBottom: 10 }} message={validationErrors[0]} />
          )}
          <Table<CapabilityRow>
            rowKey="key"
            size="small"
            pagination={false}
            scroll={{ y: 260 }}
            dataSource={capabilityRows}
            columns={capabilityColumns}
          />
        </section>
      )}
    </Modal>
  );
}
