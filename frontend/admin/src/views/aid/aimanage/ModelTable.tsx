import { providerCategoryLabel } from './providerCategory';
import React from 'react';
import ModelMigrationModal from './ModelMigrationModal';
import ModelMigrationHistory from './ModelMigrationHistory';
import { Avatar, Badge, Button, Input, Select, Space, Switch, Table, Tag, Tooltip, message } from 'antd';
import { DeleteOutlined, DisconnectOutlined, EditOutlined, GlobalOutlined, LinkOutlined, PlusOutlined, SyncOutlined, ClearOutlined, ExperimentOutlined, SearchOutlined } from '@ant-design/icons';
import type { ModelPoolBindingSnapshot } from '@/api/aid/aimanage';
import type { Model, Provider } from './types';
import { MODEL_TYPE_OPTIONS, GENERATE_MODE_OPTIONS, INPUT_REQUIREMENT_OPTIONS, getLabelByValue, getAntdTagColor } from '@/utils/enums';
import { runConfigTest, type ConfigTestResult } from '@/api/system/configTest';
import TestResultModal from '@/components/TestResultModal';
import { useAuth } from '@/hooks/useAuth';
import { ModelBillingOverview } from './BillingOverview';
import { resolveProviderLogo } from '@/utils/builtinImages';

interface Props {
  provider: Provider | null;
  list: Model[];
  loading: boolean;
  query: { modelType: string | null; generateMode: string | null; inputRequirement: string | null; poolId: number | 'unbound' | null; keyword: string };
  onQueryChange: (q: any) => void;
  onAdd: () => void;
  onEdit: (row: Model) => void;
  onDelete: (row: Model) => void;
  /** 行内开关：直接启用或停用模型，无需进入编辑弹窗 */
  onToggleStatus: (row: Model, enabled: boolean) => Promise<void>;
  onSyncVoice?: () => void;
  onCleanExpired?: () => void;
  /** 打开真实模型总览抽屉 */
  onOpenOverview?: () => void;
  poolSnapshot: ModelPoolBindingSnapshot;
  poolSnapshotReady: boolean;
  selectedModelIds: number[];
  onSelectionChange: (ids: number[]) => void;
  onOpenPoolBinding: (mode: 'bind' | 'unbind') => void;
}

export default function ModelTable({ provider, list, loading, query, onQueryChange, onAdd, onEdit, onDelete, onToggleStatus, onSyncVoice, onCleanExpired, onOpenOverview, poolSnapshot, poolSnapshotReady, selectedModelIds, onSelectionChange, onOpenPoolBinding }: Props) {
  const [migrationOpen, setMigrationOpen] = React.useState(false);
  const [migrationHistoryOpen, setMigrationHistoryOpen] = React.useState(false);
  const [testingId, setTestingId] = React.useState<number | null>(null);
  const [togglingId, setTogglingId] = React.useState<number | null>(null);
  const [testOpen, setTestOpen] = React.useState(false);
  const [testResult, setTestResult] = React.useState<ConfigTestResult | null>(null);
  const testing = React.useRef(false);
  const toggling = React.useRef(false);
  const { hasPermi } = useAuth();
  const canEditPools = hasPermi('aid:funcconfig:edit');
  const poolActionDisabledReason = !poolSnapshotReady ? '模型池关系加载失败，请刷新后重试'
    : loading ? '模型列表刷新中，请稍候' : undefined;
  const poolMap = React.useMemo(() => new Map(poolSnapshot.pools.map((pool) => [pool.id, pool])), [poolSnapshot.pools]);
  const modelPoolMap = React.useMemo(() => new Map(poolSnapshot.models.map((model) => [model.id, model.poolIds])), [poolSnapshot.models]);

  const handleTestModel = async (row: Model) => {
    if (testing.current) return;
    testing.current = true;
    setTestingId(row.id ?? null);
    try {
      const res = await runConfigTest('ai-model', { modelId: row.id });
      setTestResult(res.data);
      setTestOpen(true);
    } catch (e: any) {
      message.error(e?.message || '测试请求失败');
    } finally {
      testing.current = false;
      setTestingId(null);
    }
  };

  const handleToggleStatus = async (row: Model, enabled: boolean) => {
    if (toggling.current || row.id == null) return;
    toggling.current = true;
    setTogglingId(row.id);
    try {
      await onToggleStatus(row, enabled);
    } catch {
      // 请求失败由统一拦截器提示，列表保留原状态
    } finally {
      toggling.current = false;
      setTogglingId(null);
    }
  };

  if (!provider) {
    return <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#94a3b8' }}>请选择左侧的服务商查看模型配置</div>;
  }

  const columns: any[] = [
    {
      title: '模型名称', dataIndex: 'modelName', width: 210,
      render: (name: string, row: Model) => <div className="model-table__model">
        <Avatar shape="square" size={34} src={row.logoUrl || resolveProviderLogo(provider.providerCode, provider.logoUrl)}>{(name || 'M').slice(0, 1)}</Avatar>
        <Tooltip title={name}><span>{name || '未命名模型'}</span></Tooltip>
      </div>
    },
    {
      title: '计费配置', key: 'billingOverview', width: 330,
      render: (_: any, row: Model) => <ModelBillingOverview model={row} />
    },
    { title: '模型代码', dataIndex: 'modelCode', width: 155, ellipsis: true },
    { title: '真实模型名', dataIndex: 'realModelCode', width: 150, ellipsis: true, render: (v: string) => v || '--' },
    { title: '分类', dataIndex: 'modelType', width: 80, render: (v: string) => <Tag color={getAntdTagColor(MODEL_TYPE_OPTIONS, v)}>{getLabelByValue(MODEL_TYPE_OPTIONS, v)}</Tag> },
    { title: '模型能力', dataIndex: 'capabilities', width: 180, render: (_: unknown, row: Model) => row.capabilities?.length ? <Space size={[0, 4]} wrap>{row.capabilities.map((capability) => <Tag key={capability.code} color={capability.enabled ? 'blue' : undefined}>{capability.label}</Tag>)}</Space> : row.generateMode ? <Tag>{getLabelByValue(GENERATE_MODE_OPTIONS, row.generateMode)}</Tag> : '--' },
    { title: '输入要求', dataIndex: 'inputRequirement', width: 95, render: (v: string) => v ? <Tag color={getAntdTagColor(INPUT_REQUIREMENT_OPTIONS, v)}>{getLabelByValue(INPUT_REQUIREMENT_OPTIONS, v)}</Tag> : '--' },
    {
      title: '模型池', key: 'pools', width: 190, render: (_: any, r: Model) => {
        if (!poolSnapshotReady) return <span style={{ color: '#94a3b8' }}>加载失败</span>;
        const pools = (modelPoolMap.get(r.id!) || []).map((id) => poolMap.get(id)).filter(Boolean);
        if (pools.length === 0) return <Tag color="default">未绑定</Tag>;
        const visible = pools.slice(0, 2);
        return <Space size={4} wrap>
          {visible.map((pool) => <Tag key={pool!.id} color={pool!.status === '0' ? 'blue' : 'default'}>{pool!.funcName}</Tag>)}
          {pools.length > visible.length && <Tooltip title={pools.slice(2).map((pool) => pool!.funcName).join('、')}><Tag>+{pools.length - visible.length}</Tag></Tooltip>}
        </Space>;
      }
    },
    {
      title: '操作',
      key: 'ops',
      width: 250,
      fixed: 'right',
      render: (_: any, r: Model) => {
        const enabled = r.status === '0';
        return (
          <Space size={0}>
            <Tooltip title={enabled ? '点击停用' : '点击启用'}>
              <Switch
                size="small"
                checked={enabled}
                checkedChildren="启用"
                unCheckedChildren="停用"
                loading={togglingId === r.id}
                onChange={(checked) => handleToggleStatus(r, checked)}
              />
            </Tooltip>
            <Button type="link" size="small" icon={<EditOutlined />} onClick={() => onEdit(r)}>修改</Button>
            <Tooltip title={provider?.integrationType === 'NEW_API' && r.protocol === 'openai-compatible-text' ? '向上游实际生成一条短文本，会消耗少量额度' : undefined}>
              <Button type="link" size="small" icon={<ExperimentOutlined />} loading={testingId === r.id} onClick={() => handleTestModel(r)}>
                {provider?.integrationType === 'NEW_API' && ['openai-compatible-text', 'newapi-image'].includes(r.protocol || '') ? '实际测试' : '测试'}
              </Button>
            </Tooltip>
            <Button type="link" size="small" danger icon={<DeleteOutlined />} onClick={() => onDelete(r)}>下线</Button>
          </Space>
        );
      }
    }
  ];

  return (
    <div className="model-table">
      <div className="model-table__header">
        <div className="model-table__identity">
          <div className="model-table__title">
          <h3 style={{ margin: 0 }}>{provider.providerName}</h3><Tag>{providerCategoryLabel(provider)}</Tag>
          <Tag>{provider.providerCode}</Tag>
          {provider.status === '1' && <Tag color="red">已停用</Tag>}
          </div>
          <span className="model-table__subtitle">模型配置 <span>·</span> {loading ? '加载中' : `当前 ${list.length} 个模型`}</span>
        </div>
        <Space size={8} wrap>
          {hasPermi('aid:aidmodel:edit') && canEditPools && <Button size="small" onClick={() => setMigrationOpen(true)}>模型统一迁移</Button>}
          {hasPermi('aid:aidmodel:edit') && canEditPools && <Button size="small" onClick={() => setMigrationHistoryOpen(true)}>迁移记录</Button>}
          {onOpenOverview && <Button icon={<GlobalOutlined />} onClick={onOpenOverview}>真实模型总览</Button>}
          <Button type="primary" icon={<PlusOutlined />} onClick={onAdd}>新增模型</Button>
        </Space>
      </div>
      <div className="model-table__filters" role="group" aria-label="筛选模型">
          <Select aria-label="模型分类" style={{ width: 120 }} placeholder="模型分类" allowClear value={query.modelType} onChange={(v) => onQueryChange({ ...query, modelType: v })} options={MODEL_TYPE_OPTIONS.map((o) => ({ label: o.label, value: o.value }))} />
          <Select aria-label="生成模式" style={{ width: 140 }} placeholder="生成模式" allowClear value={query.generateMode} onChange={(v) => onQueryChange({ ...query, generateMode: v })} options={GENERATE_MODE_OPTIONS.map((o) => ({ label: o.label, value: o.value }))} />
          <Select aria-label="输入要求" style={{ width: 120 }} placeholder="输入要求" allowClear value={query.inputRequirement} onChange={(v) => onQueryChange({ ...query, inputRequirement: v })} options={INPUT_REQUIREMENT_OPTIONS.map((o) => ({ label: o.label, value: o.value }))} />
          <Select
            aria-label="所属模型池"
            showSearch
            optionFilterProp="label"
            style={{ width: 150 }}
            placeholder="所属模型池"
            allowClear
            disabled={!poolSnapshotReady}
            value={query.poolId}
            onChange={(v) => onQueryChange({ ...query, poolId: v ?? null })}
            options={[
              { label: '未绑定模型池', value: 'unbound' },
              ...poolSnapshot.pools.map((pool) => ({ label: pool.funcName, value: pool.id }))
            ]}
          />
          <Input className="model-table__search" prefix={<SearchOutlined />} aria-label="搜索模型" placeholder="搜索模型名称或代码" allowClear value={query.keyword} onChange={(e) => onQueryChange({ ...query, keyword: e.target.value })} />
      </div>
      <div className="model-table__toolbar">
        <span className="model-table__selection">{selectedModelIds.length > 0 ? `已选择 ${selectedModelIds.length} 个模型` : '模型列表'}
          {selectedModelIds.length > 0 && <Button type="link" size="small" onClick={() => onSelectionChange([])}>清空选择</Button>}
        </span>
        <Space size={8} wrap>
          {canEditPools && (
            <>
              <Tooltip title={poolActionDisabledReason}>
              <Button size="small" icon={<LinkOutlined />} disabled={Boolean(poolActionDisabledReason) || selectedModelIds.length === 0} onClick={() => onOpenPoolBinding('bind')}>
                绑定模型池
              </Button>
              </Tooltip>
              <Tooltip title={poolActionDisabledReason}>
              <Button size="small" icon={<DisconnectOutlined />} disabled={Boolean(poolActionDisabledReason) || selectedModelIds.length === 0} onClick={() => onOpenPoolBinding('unbind')}>
                移出模型池
              </Button>
              </Tooltip>
            </>
          )}
          {provider.providerCode?.trim().toLowerCase() === 'minimax' && onCleanExpired && (
            <Button size="small" icon={<ClearOutlined />} danger onClick={onCleanExpired}>
              清除过期
            </Button>
          )}
          {provider.providerCode?.trim().toLowerCase() === 'minimax' && onSyncVoice && (
            <Badge count="推荐" size="small">
              <Button size="small" icon={<SyncOutlined />} onClick={onSyncVoice}>
                同步音色
              </Button>
            </Badge>
          )}
        </Space>
      </div>
      <div className="model-table__scroll" role="region" aria-label="供应商模型列表" tabIndex={0}>
      <Table
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={list}
        columns={columns}
        scroll={{ x: 1660 }}
        sticky={{ offsetScroll: 0 }}
        pagination={false}
        rowSelection={canEditPools && poolSnapshotReady ? {
          selectedRowKeys: selectedModelIds,
          preserveSelectedRowKeys: false,
          onChange: (keys) => onSelectionChange(keys.map(Number)),
          getCheckboxProps: () => ({ disabled: loading })
        } : undefined}
      />
      </div>
      <ModelMigrationModal open={migrationOpen} onClose={() => setMigrationOpen(false)} onComplete={() => onQueryChange({ ...query })} />
      <ModelMigrationHistory open={migrationHistoryOpen} onClose={() => setMigrationHistoryOpen(false)} onComplete={() => onQueryChange({ ...query })} />
      <TestResultModal open={testOpen} result={testResult} onClose={() => setTestOpen(false)} />
    </div>
  );
}
