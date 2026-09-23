import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Checkbox, Collapse, Descriptions, Drawer, Empty, Form, Input, InputNumber, Modal, Row, Col, Space, Spin, Switch, Table, Tag, Typography, message } from 'antd';
import { CheckCircleOutlined, ClockCircleOutlined, ExclamationCircleOutlined, FileTextOutlined, ReloadOutlined, SendOutlined, SettingOutlined } from '@ant-design/icons';
import { diagnostics, type DiagnosticApplication, type DiagnosticEvent, type DiagnosticSettings, type Verification } from '@/api/aid/diagnostics';
import { useAuth } from '@/hooks/useAuth';
import './style.less';

const { Text, Paragraph, Title } = Typography;
const stateLabels: Record<string, string> = { unknown: '正在核验', unavailable: '状态暂不可用', none: '尚未申请', pending: '等待官方审核', approved: '已获授权', rejected: '申请已驳回', revoked: '授权已撤销', expired: '授权已失效' };
const verificationLabels: Record<string, string> = { queued: '等待验证', pending: '等待验证', processing: '正在验证', running: '正在验证', verified: '验证成功', failed: '验证失败', timeout: '结果暂未确认', unknown: '结果暂未确认', expired: '验证已过期', revoked: '授权已撤销' };
const deliveryLabels: Record<string, string> = { queued: '等待发送', sending: '正在发送', received: '已接收', checking: '正在确认送达', delivered: '已送达', failed: '发送失败', unknown: '送达待确认' };
const processingStatuses = ['queued', 'pending', 'running', 'processing'];
const verificationWaitMs = 60_000;

function verificationReason(value?: Verification) {
  if (value?.status === 'timeout') return '等待超过 60 秒，尚未取得最终结果。请刷新当前验证结果。';
  if (value?.status === 'unknown') return value.message || '暂时无法确认当前验证结果，请刷新当前验证结果。';
  const reason = value?.errorCode || value?.message || '';
  if (reason.includes('authentication_failed')) return 'SSH 账号或密码认证失败，请修正后重新验证。';
  if (reason.includes('connection_refused')) return '服务器拒绝连接，请检查公网地址、SSH 端口和白名单。';
  if (reason.includes('connection_timeout') || reason.includes('timed_out')) return '连接超时，请检查服务器网络和白名单后重新验证。';
  return reason && !/^[a-z_]+$/.test(reason) ? reason : '请检查服务器地址、端口、白名单和 SSH 认证后重新验证。';
}

function requestTimestamp(value?: string) {
  if (!value) return 0;
  // Java 时间可能带纳秒。截到毫秒以兼容只接受三位小数的浏览器。
  const normalized = value.replace(/(\.\d{3})\d+(?=Z|[+-]\d{2}:?\d{2}$)/, '$1');
  const timestamp = new Date(normalized).getTime();
  return Number.isNaN(timestamp) ? 0 : timestamp;
}

function localTime(value?: string) {
  if (!value) return '—';
  const timestamp = requestTimestamp(value);
  if (!timestamp) return '—';
  const date = new Date(timestamp);
  const parts = new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).formatToParts(date);
  const get = (type: string) => parts.find((part) => part.type === type)?.value || '';
  return `${get('year')}-${get('month')}-${get('day')} ${get('hour')}:${get('minute')}:${get('second')}`;
}

function retentionLabel(days: number) {
  return days === 1 ? '24 小时' : `${days} 天`;
}

function deliveryTag(row: DiagnosticEvent) {
  if (!row.reportId) return <Tag>未发送</Tag>;
  const status = row.deliveryStatus || 'unknown';
  const color = ['delivered', 'received'].includes(status) ? 'success' : status === 'failed' ? 'error' : ['unknown', 'queued'].includes(status) ? 'warning' : 'processing';
  const icon = ['delivered', 'received'].includes(status) ? <CheckCircleOutlined /> : status === 'failed' ? <ExclamationCircleOutlined /> : <ClockCircleOutlined />;
  return <Tag color={color} icon={icon} title={row.deliveryMessage}>{deliveryLabels[status] || '已提交'}</Tag>;
}

function handlingTag(row: DiagnosticEvent) {
  if (!row.reportId) return <Tag>未提交</Tag>;
  const status = row.handlingStatus;
  if (!status) return <Tag>等待官方回执</Tag>;
  const states: Record<string, { label: string; color: string }> = {
    loading: { label: '正在同步', color: 'processing' },
    pending: { label: '待处理', color: 'warning' },
    processing: { label: '处理中', color: 'processing' },
    done: { label: '处理完毕', color: 'success' },
    refused: { label: '拒绝处理', color: 'error' },
    unable: { label: '无法处理', color: 'error' },
    unknown: { label: '进度待确认', color: 'warning' },
  };
  const state = states[status] || states.unknown;
  const hint = [row.handlingNote, row.handlingUpdatedAt && `更新于 ${localTime(row.handlingUpdatedAt)}`].filter(Boolean).join(' · ');
  return <Tag color={state.color} title={hint || undefined}>{state.label}</Tag>;
}

export default function ErrorHandlingPage() {
  const { hasPermi } = useAuth();
  const manage = hasPermi('aid:diagnostics:manage');
  const canSend = hasPermi('aid:diagnostics:send');
  const [settings, setSettings] = useState<DiagnosticSettings>();
  const [access, setAccess] = useState<DiagnosticApplication>({ status: 'unknown', canOperate: false, canApply: false });
  const [accessLoading, setAccessLoading] = useState(true);
  const authorizedRef = useRef(false);
  const requestEpoch = useRef(0);
  const accessEpoch = useRef(0);
  const diagnosticRequests = useRef(new Set<AbortController>());
  const [busy, setBusy] = useState('');
  const activeAction = useRef(false);
  const [page, setPage] = useState(1);
  const [rows, setRows] = useState<DiagnosticEvent[]>([]);
  const [total, setTotal] = useState(0);
  const [pageVisible, setPageVisible] = useState(() => document.visibilityState === 'visible');
  const pollRunRef = useRef(0);
  const [detail, setDetail] = useState<Record<string, unknown>>();
  const [detailRow, setDetailRow] = useState<DiagnosticEvent>();
  const [detailOpen, setDetailOpen] = useState(false);
  const [sendRow, setSendRow] = useState<DiagnosticEvent>();
  const [accessExpanded, setAccessExpanded] = useState(true);
  const [verification, setVerification] = useState<Verification>();
  const verificationStartedAt = useRef(0);
  const [consent, setConsent] = useState(false);
  const [applicationForm] = Form.useForm();
  const [supportForm] = Form.useForm();
  const enabled = settings?.enabled === true;
  const approved = access.status === 'approved' && access.authorized === true && access.canOperate === true && !!access.observedIp && !accessLoading;
  const verifying = busy === 'verify' || processingStatuses.includes(verification?.status || '');
  const includeServer = consent && verification?.status === 'verified';
  const officialIps = settings?.metadataError ? [] : (settings?.metadata?.supportEgressIps || []).filter(Boolean);
  const supportIpNotice = <Alert className="diagnostics-page__ip-notice" type={officialIps.length ? 'error' : 'warning'} showIcon
    message={officialIps.length ? '远程只读排查需先配置服务器白名单' : '官方配置暂不可用'}
    description={officialIps.length ? <Space wrap>请将官方跳板出口 IP {officialIps.map((ip) => <Text code copyable key={ip}>{ip}</Text>)} 加入目标服务器白名单，否则官方无法连接只读排查。</Space> : '暂无法获取已验证的官方出口 IP，请稍后刷新配置。'} />;

  useEffect(() => { setAccessExpanded(!approved); }, [approved]);

  const clearDiagnostics = useCallback(() => {
    requestEpoch.current += 1;
    pollRunRef.current = 0;
    diagnosticRequests.current.forEach((controller) => controller.abort());
    diagnosticRequests.current.clear();
    setRows([]); setTotal(0); setDetail(undefined); setDetailRow(undefined); setDetailOpen(false); setSendRow(undefined);
    supportForm.resetFields(); setVerification(undefined); verificationStartedAt.current = 0; setConsent(false);
  }, [supportForm]);

  const loadRows = useCallback(async () => {
    if (!authorizedRef.current) return;
    const epoch = requestEpoch.current;
    const controller = new AbortController(); diagnosticRequests.current.add(controller);
    try {
      const result = await diagnostics.events(page, controller.signal);
      if (authorizedRef.current && epoch === requestEpoch.current) {
        setRows([...result.rows].sort((left, right) => requestTimestamp(right.requestTime) - requestTimestamp(left.requestTime)));
        setTotal(result.total);
      }
    } catch (error) {
      if (!controller.signal.aborted && authorizedRef.current && epoch === requestEpoch.current) {
        authorizedRef.current = false;
        clearDiagnostics();
        setAccess((old) => ({ ...old, status: 'unavailable', authorized: false, canOperate: false, canApply: false, reason: '诊断状态暂不可用，请刷新授权状态。' }));
      }
      throw error;
    } finally { diagnosticRequests.current.delete(controller); }
  }, [page, clearDiagnostics]);

  const refreshAccess = useCallback(async () => {
    const epoch = ++accessEpoch.current;
    authorizedRef.current = false;
    clearDiagnostics(); setAccessLoading(true);
    setAccess((old) => ({ ...old, authorized: false, canOperate: false }));
    try {
      const result = await diagnostics.access(true);
      if (epoch !== accessEpoch.current) return;
      // 强制核验会刷新签名配置；随后重读 settings，避免展示旧缓存中的出口 IP。
      const refreshedSettings = await diagnostics.settings();
      if (epoch !== accessEpoch.current) return;
      setSettings(refreshedSettings);
      const allowed = result.status === 'approved' && result.authorized === true && result.canOperate === true && !!result.observedIp;
      authorizedRef.current = allowed;
      setAccess({ ...result, authorized: allowed, canOperate: allowed });
    } catch {
      if (epoch === accessEpoch.current) setAccess((old) => ({ ...old, status: 'unavailable', authorized: false, canOperate: false, canApply: false, reason: '授权状态查询失败，请稍后刷新状态。' }));
    } finally { if (epoch === accessEpoch.current) setAccessLoading(false); }
  }, [clearDiagnostics]);

  useEffect(() => {
    void refreshAccess();
    return () => { accessEpoch.current += 1; authorizedRef.current = false; clearDiagnostics(); };
  }, [clearDiagnostics, refreshAccess]);

  useEffect(() => {
    if (enabled && approved) void loadRows().catch(() => {});
    else if (!enabled) clearDiagnostics();
  }, [enabled, approved, loadRows, clearDiagnostics]);

  useEffect(() => {
    const onVisibility = () => setPageVisible(document.visibilityState === 'visible');
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, []);

  useEffect(() => {
    if (!approved || !enabled || !pageVisible || !rows.some((row) => row.reportId)) return;
    const timer = window.setTimeout(() => {
      pollRunRef.current += 1;
      void loadRows().catch(() => {});
    }, pollRunRef.current === 0 ? 2000 : 30000);
    return () => window.clearTimeout(timer);
  }, [approved, enabled, pageVisible, rows, loadRows]);

  useEffect(() => {
    if (!approved || !enabled || !sendRow || !verification || !processingStatuses.includes(verification.status)) return;
    let alive = true;
    let controller: AbortController | undefined;
    const epoch = requestEpoch.current;
    const timer = window.setTimeout(() => {
      if (!authorizedRef.current) return;
      if (Date.now() - verificationStartedAt.current >= verificationWaitMs) {
        setVerification({ status: 'timeout' });
        return;
      }
      controller = new AbortController(); diagnosticRequests.current.add(controller);
      void diagnostics.verification(controller.signal).then((value) => {
        if (alive && authorizedRef.current && epoch === requestEpoch.current) setVerification(value);
      }).catch(() => {
        if (alive && authorizedRef.current && epoch === requestEpoch.current) setVerification({ status: 'unknown', message: '验证状态查询失败，请检查网络后刷新当前验证结果。' });
      }).finally(() => { if (controller) diagnosticRequests.current.delete(controller); });
    }, 2000);
    return () => { alive = false; window.clearTimeout(timer); controller?.abort(); };
  }, [verification, approved, enabled, sendRow]);

  const action = async (name: string, run: () => Promise<void>) => {
    if (activeAction.current) return;
    activeAction.current = true;
    setBusy(name);
    try { await run(); } catch {
      if (['events', 'detail'].includes(name)) await refreshAccess();
      if (['verify', 'send', 'retry'].includes(name)) {
        // 普通发送失败保留表单；确实失去授权时才清除已输入的诊断资料。
        try {
          const current = await diagnostics.access(true);
          if (current.status !== 'approved' || current.authorized !== true || current.canOperate !== true || !current.observedIp) await refreshAccess();
        } catch { /* 网络故障时保留本次草稿，等待用户重试 */ }
      }
    } finally { activeAction.current = false; setBusy(''); }
  };

  const closeSend = () => {
    if (busy) return;
    setSendRow(undefined); supportForm.resetFields(); setVerification(undefined); verificationStartedAt.current = 0; setConsent(false);
  };

  const submit = () => action('send', async () => {
    if (!authorizedRef.current || !sendRow) return;
    if (sendRow.reportId) {
      await diagnostics.retry(sendRow.reportId);
    } else {
      const values = supportForm.getFieldsValue(true);
      const serverDetailsEntered = [values.host, values.username, values.password].some((value) => typeof value === 'string' && value.length > 0)
        || (values.port !== undefined && Number(values.port) !== 22);
      if ((consent || serverDetailsEntered) && !includeServer) {
        message.error(verification?.status === 'failed'
          ? '服务器连接验证失败，请修正 SSH 认证后重新验证；如只发日志，请清空服务器资料并取消同意。'
          : '请先完成服务器连接验证；如只发日志，请清空服务器资料并取消同意。');
        return;
      }
      const optionalSupport = {
        testAccount: { username: values.testAccount, password: values.testPassword },
        adminAccount: { username: values.adminAccount, password: values.adminPassword },
        notes: values.supportDescription,
      };
      await diagnostics.send({ eventIds: [sendRow.eventId], requestId: sendRow.requestId, optionalSupport, includeServer });
    }
    if (authorizedRef.current) {
      message.success(sendRow.reportId ? '已重新确认原报告' : '报告已提交，发送状态会在列表中更新');
      setSendRow(undefined); supportForm.resetFields(); setVerification(undefined); verificationStartedAt.current = 0; setConsent(false);
      await loadRows();
      void diagnostics.settings().then((value) => { if (authorizedRef.current) setSettings(value); }).catch(() => {});
    }
  });

  if (!settings) return <Card>{accessLoading ? <Spin tip="正在读取错误处理配置" /> : <Alert type="error" showIcon message={access.reason || '读取配置失败'} action={<Button onClick={() => window.location.reload()}>重试</Button>} />}</Card>;

  return <div className="diagnostics-page">
    <div className="diagnostics-page__header">
      <div><Title level={3}>错误处理</Title><Text type="secondary">自动采集异常，逐条提交给官方排查。</Text></div>
      <Space><Text>错误采集</Text><Switch aria-label="开启错误采集" checked={enabled} disabled={!manage || !!busy} loading={busy === 'settings'}
        onChange={(value) => void action('settings', async () => { const result = await diagnostics.save(value); setSettings((old) => ({ ...old!, ...result })); if (value) await refreshAccess(); })} /></Space>
    </div>

    <Row gutter={[16, 16]} className="diagnostics-page__overview">
      <Col xs={24} sm={12} lg={8}><Card size="small"><Text type="secondary">采集状态</Text><div className="diagnostics-page__metric"><Tag color={enabled ? 'success' : 'default'}>{enabled ? '已开启' : '已关闭'}</Tag></div><Text type="secondary">异常只保留在本地，逐条提交后才发送</Text></Card></Col>
      <Col xs={24} sm={12} lg={8}><Card size="small"><Text type="secondary">官方授权</Text><div className="diagnostics-page__metric"><Tag color={approved ? 'success' : access.status === 'pending' ? 'processing' : 'warning'}>{stateLabels[access.status] || access.status}</Tag></div><Text type="secondary">按当前服务器出口 IP 核验</Text></Card></Col>
      <Col xs={24} sm={12} lg={8}><Card size="small"><Text type="secondary">本地错误保留时间</Text><div className="diagnostics-page__metric">{retentionLabel(settings.retentionDays)}</div><Text type="secondary">本地错误与已提交资料到期自动清理</Text></Card></Col>
    </Row>
    {approved && !!settings.captureFailures && <Alert type="warning" showIcon message={`有 ${settings.captureFailures} 次诊断未能完整保存，请检查存储容量与数据库状态。`} />}

    <Card className="diagnostics-page__access" size="small">
      <Collapse ghost activeKey={accessExpanded ? ['authorization'] : []} onChange={(keys) => setAccessExpanded(keys.includes('authorization'))} items={[{
        key: 'authorization',
        label: <Space wrap><strong>官方白名单申请</strong><Tag color={approved ? 'success' : 'warning'}>{stateLabels[access.status] || access.status}</Tag>{approved && <Text type="secondary">已通过，无需再次申请</Text>}</Space>,
        extra: <Button size="small" icon={<ReloadOutlined />} loading={accessLoading} disabled={accessLoading || !!busy} onClick={(event) => { event.stopPropagation(); void refreshAccess(); }}>刷新状态</Button>,
        children: <div className="diagnostics-page__access-body">
          <Space wrap><Text>服务器实际出口 IP：{access.observedIp || '暂未识别'}</Text>{access.applicationId && <Text>申请号：<Text copyable>{access.applicationId}</Text></Text>}{access.queryCredentialSaved && <Text type="secondary">查询凭据已安全保存</Text>}</Space>
          {access.reason && <Alert type="warning" showIcon message={access.reason} />}
          {settings.metadataError && <Alert type="warning" showIcon message={settings.metadataError} />}
          {!approved && <><Paragraph type="secondary">官方按服务器实际出口 IP 审核。授权通过后即可查看错误、验证连接并发送报告。</Paragraph>
            {access.canApply && <Form form={applicationForm} layout="vertical" disabled={!manage || accessLoading || !!busy} onFinish={(values) => void action('apply', async () => { const result = await diagnostics.apply(values); setAccess(result); message.success('申请已提交，请等待官方审核'); await refreshAccess(); })}>
              <Row gutter={16}><Col xs={24} md={12}><Form.Item name="platformName" label="平台名称" rules={[{ required: true, whitespace: true, message: '请填写平台名称' }]}><Input maxLength={100} /></Form.Item></Col>
                <Col xs={24} md={12}><Form.Item name="contact" label="联系方式" rules={[{ required: true, whitespace: true, message: '请填写联系方式' }]}><Input maxLength={250} /></Form.Item></Col></Row>
              <Form.Item name="description" label="申请说明" rules={[{ required: true, whitespace: true, message: '请简要说明申请用途' }]}><Input.TextArea rows={2} maxLength={2000} showCount /></Form.Item>
              <Button htmlType="submit" type="primary" loading={busy === 'apply'} disabled={!manage || !!busy}>{['rejected', 'revoked', 'expired'].includes(access.status) ? '重新申请授权' : '提交白名单申请'}</Button>
            </Form>}</>}
        </div>,
      }]} />
    </Card>

    {approved && enabled && supportIpNotice}

    {!approved ? <Card><Alert type="warning" showIcon message="诊断功能等待授权" description={accessLoading ? '正在核验当前服务器的授权状态。' : '当前服务器出口 IP 获得官方授权后，可查看错误记录和发送资料。'} /></Card>
      : !enabled ? <Card><Alert type="info" showIcon message="错误采集已关闭" description="开启上方采集开关后，可查看错误记录和发送报告。" /></Card>
      : <Card className="diagnostics-page__events" title={<Space><FileTextOutlined /><span>最近 {retentionLabel(settings.retentionDays)}错误</span><Text type="secondary">共 {total} 条</Text></Space>}
          extra={<Button icon={<ReloadOutlined />} disabled={!approved || !!busy} onClick={() => void action('events', loadRows)}>刷新</Button>}>
          <Table<DiagnosticEvent> rowKey="eventId" size="middle" dataSource={rows} scroll={{ x: 880 }}
            pagination={{ current: page, total, pageSize: 20, showSizeChanger: false, onChange: (next) => {
              requestEpoch.current += 1;
              diagnosticRequests.current.forEach((controller) => controller.abort());
              diagnosticRequests.current.clear();
              pollRunRef.current = 0;
              setRows([]); setPage(next);
            } }}
            locale={{ emptyText: <Empty description={`最近 ${retentionLabel(settings.retentionDays)}暂无错误记录`} /> }} columns={[
              { title: '请求时间', dataIndex: 'requestTime', width: 190, render: (value: string) => <time dateTime={value}>{localTime(value)}</time> },
              { title: '请求编码', dataIndex: 'requestId', ellipsis: true, render: (value: string) => <Text copyable ellipsis={{ tooltip: value }}>{value || '—'}</Text> },
              { title: '发送状态', dataIndex: 'reportId', width: 150, render: (_: unknown, row: DiagnosticEvent) => deliveryTag(row) },
              { title: '官方处理进度', dataIndex: 'handlingStatus', width: 160, render: (_: unknown, row: DiagnosticEvent) => handlingTag(row) },
              { title: '操作', key: 'actions', width: 190, render: (_: unknown, row: DiagnosticEvent) => <Space><Button type="link" disabled={!!busy} onClick={() => void action('detail', async () => { if (!authorizedRef.current) return; const epoch = requestEpoch.current; const controller = new AbortController(); diagnosticRequests.current.add(controller); try { const result = await diagnostics.detail(row.eventId, controller.signal); if (authorizedRef.current && epoch === requestEpoch.current) { setDetail(result); setDetailRow(row); setDetailOpen(true); } } finally { diagnosticRequests.current.delete(controller); } })}>查看详情</Button>
                {(!row.reportId || ['failed', 'unknown'].includes(row.deliveryStatus || '')) && <Button type="link" disabled={!canSend || !!busy || !!sendRow} onClick={() => { supportForm.resetFields(); setVerification(undefined); verificationStartedAt.current = 0; setConsent(false); setSendRow(row); }}>{row.reportId ? '重新提交' : '提交'}</Button>}</Space> },
            ]} />
          <Text type="secondary">每条错误单独提交；可选资料仅随该次报告发送。报告状态和官方处理进度会在列表中更新。</Text>
        </Card>}

    <Drawer title="错误详情" width="min(840px, 96vw)" open={detailOpen} onClose={() => setDetailOpen(false)} destroyOnClose>
      {detailRow && <Descriptions size="small" column={1} bordered items={[
        { key: 'time', label: '请求时间', children: localTime(detailRow.requestTime) },
        { key: 'id', label: '请求编码', children: <Text copyable>{detailRow.requestId}</Text> },
        { key: 'status', label: '发送状态', children: <Space>{deliveryTag(detailRow)}{detailRow.deliveryMessage && <Text type="secondary">{detailRow.deliveryMessage}</Text>}</Space> },
        { key: 'handling', label: '官方处理进度', children: <Space wrap>{handlingTag(detailRow)}{detailRow.handlingUpdatedAt && <Text type="secondary">更新于 {localTime(detailRow.handlingUpdatedAt)}</Text>}</Space> },
        ...(detailRow.handlingNote ? [{ key: 'handlingNote', label: '官方处理说明', children: detailRow.handlingNote }] : []),
      ]} />}
      <Title level={5} style={{ marginTop: 24 }}>诊断内容</Title>
      <pre className="diagnostics-page__json">{detail ? JSON.stringify(detail, null, 2) : ''}</pre>
    </Drawer>

    <Modal className="diagnostics-page__send" title={sendRow?.reportId ? '重新提交原报告' : '提交错误报告'} width="min(720px, 96vw)"
      open={!!sendRow && approved && enabled} closable={false} maskClosable={false} keyboard={false} onCancel={() => {}} destroyOnClose
      styles={{ body: { maxHeight: 'calc(100vh - 230px)', overflowY: 'auto' } }}
      footer={<Space wrap><Button disabled={!!busy} onClick={closeSend}>取消</Button><Button type="primary" icon={<SendOutlined />} loading={busy === 'send'} disabled={!sendRow || !canSend || !!busy} onClick={() => void submit()}>{sendRow?.reportId ? '确认重新提交' : '确认提交'}</Button></Space>}>
      <Descriptions size="small" column={1} items={[{ key: 'requestId', label: '本次请求', children: <Text copyable>{sendRow?.requestId || '—'}</Text> }]} />
      {sendRow?.reportId ? <Alert type="warning" showIcon message="重试原报告" description="官方可能已经收到这份报告。重新提交会沿用首次提交的诊断内容和资料，仅重新确认发送结果；此处不能修改资料。" /> : <>
      <Alert type="info" showIcon message="可选资料只随这条错误提交" description={`所有补充资料都可留空直接发送。本地错误与已提交的诊断资料分别保留 ${retentionLabel(settings.retentionDays)}，到期自动清理。发送时系统自动准备加密密钥。`} />
      <Form form={supportForm} layout="vertical" className="diagnostics-page__support-form" disabled={!enabled || verifying}
        onValuesChange={(changed) => { if (['host', 'port', 'username', 'password'].some((field) => field in changed)) setVerification(undefined); }}>
        <Collapse defaultActiveKey={['notes']} items={[
          { key: 'notes', label: <Space><FileTextOutlined />问题说明</Space>, children: <Form.Item name="supportDescription" label="问题补充说明（选填）" extra="描述复现步骤、影响范围或期望结果。"><Input.TextArea rows={4} maxLength={4000} showCount /></Form.Item> },
          { key: 'accounts', label: <Space><SettingOutlined />测试与后台账号 <Text type="secondary">选填</Text></Space>, children: <>
            <Text strong>测试账号</Text><Row gutter={16}><Col xs={24} sm={12}><Form.Item name="testAccount" label="账号"><Input autoComplete="off" /></Form.Item></Col><Col xs={24} sm={12}><Form.Item name="testPassword" label="密码"><Input.Password autoComplete="new-password" /></Form.Item></Col></Row>
            <Text strong>后台账号</Text><Row gutter={16}><Col xs={24} sm={12}><Form.Item name="adminAccount" label="账号"><Input autoComplete="off" /></Form.Item></Col><Col xs={24} sm={12}><Form.Item name="adminPassword" label="密码"><Input.Password autoComplete="new-password" /></Form.Item></Col></Row>
            <Text type="secondary">填写后，账号资料会出现在官方报告详情中。</Text>
          </> },
          { key: 'server', label: <Space><SettingOutlined />服务器只读排查 <Text type="secondary">选填，需单独授权</Text></Space>, children: <>
            {supportIpNotice}
            <Paragraph type="secondary" style={{ marginTop: 12 }}>仅在本次授权范围内读取日志和诊断信息；不主动修改业务数据、安装程序、重启服务或执行付费模型调用。连接和认证可能产生系统审计记录。</Paragraph>
            <Row gutter={16}><Col xs={24} sm={12}><Form.Item name="host" label="服务器公网地址"><Input autoComplete="off" /></Form.Item></Col><Col xs={24} sm={12}><Form.Item name="port" label="SSH 端口" initialValue={22}><InputNumber min={1} max={65535} style={{ width: '100%' }} /></Form.Item></Col>
              <Col xs={24} sm={12}><Form.Item name="username" label="服务器账号"><Input autoComplete="off" /></Form.Item></Col><Col xs={24} sm={12}><Form.Item name="password" label="服务器密码"><Input.Password autoComplete="new-password" /></Form.Item></Col></Row>
            <div className="diagnostics-page__verify"><Text type="secondary">填写服务器资料并同意后，还需点击下方按钮完成验证；验证成功会随这条报告自动附带。连接验证结果仅在 15 分钟内可用于本次提交；资料保存时间不等于验证有效期。若只发日志，请清空服务器资料并取消同意。</Text>
              <Checkbox checked={consent} disabled={verifying} onChange={(event) => { setConsent(event.target.checked); if (!event.target.checked) setVerification(undefined); }}>同意本次连接验证与只读排查</Checkbox>
              <Space wrap><Button disabled={!approved || !consent || !manage || !!busy || verifying || ['verified', 'timeout', 'unknown'].includes(verification?.status || '')} loading={busy === 'verify'} onClick={() => void action('verify', async () => { if (!authorizedRef.current) return; const values = supportForm.getFieldsValue(true); if (!values.host || !values.username || !values.password) { message.warning('请填写服务器地址、账号和密码后验证'); return; } verificationStartedAt.current = Date.now(); setVerification(undefined); try { const result = await diagnostics.verify({ server: { host: values.host, port: values.port, username: values.username, password: values.password }, consent }); if (authorizedRef.current) setVerification(result); } catch { if (authorizedRef.current) setVerification({ status: 'unknown', message: '验证请求结果暂无法确认，请刷新当前验证结果。' }); throw new Error('verification request result unknown'); } })}>验证服务器连接</Button>
                {verification && ['timeout', 'unknown'].includes(verification.status) && <Button loading={busy === 'verification-status'} disabled={!!busy} onClick={() => void action('verification-status', async () => { try { const result = await diagnostics.verification(); if (!authorizedRef.current) return; if (processingStatuses.includes(result.status)) verificationStartedAt.current = Date.now(); setVerification(result); } catch { if (authorizedRef.current) setVerification({ status: 'unknown', message: '暂时无法查询当前验证结果，请稍后刷新。' }); } })}>刷新验证结果</Button>}
                {verification && <Tag color={verification.status === 'verified' ? 'success' : ['failed', 'expired', 'revoked'].includes(verification.status) ? 'error' : ['timeout', 'unknown'].includes(verification.status) ? 'warning' : 'processing'}>{verificationLabels[verification.status] || '结果待确认'}</Tag>}</Space>
              {busy === 'verify' && <Alert type="info" showIcon message="正在发起连接验证" description="请求已提交，正在等待官方确认是否受理。" />}
              {verification && processingStatuses.includes(verification.status) && <Alert type="info" showIcon message={verificationLabels[verification.status]} description="正在等待官方连接和认证结果，最长等待 60 秒。关闭弹窗将停止查询。" />}
              {verification && ['failed', 'expired', 'revoked', 'timeout', 'unknown'].includes(verification.status) && <Alert type={['timeout', 'unknown'].includes(verification.status) ? 'warning' : 'error'} showIcon message={verificationLabels[verification.status]} description={<>{verificationReason(verification)} 如只发日志，请清空服务器资料并取消同意。</>} />}
              {includeServer && <Alert type="success" showIcon message="验证成功，提交时将自动附带服务器资料（含连接密码）" description="如只提交日志，请清空服务器资料并取消同意。" />}
              <Button size="small" disabled={verifying} onClick={() => { supportForm.setFieldsValue({ host: undefined, port: 22, username: undefined, password: undefined }); setConsent(false); setVerification(undefined); verificationStartedAt.current = 0; }}>清空服务器资料，仅提交日志</Button>
            </div>
          </> },
        ]} />
      </Form>
      <Paragraph type="secondary" style={{ marginTop: 16 }}>诊断日志和您选择填写的资料会随本次报告提交。提交后由后台异步发送，无需重复点击。</Paragraph>
      </>}
    </Modal>
  </div>;
}
