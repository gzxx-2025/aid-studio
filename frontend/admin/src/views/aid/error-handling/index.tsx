import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Checkbox, Col, Drawer, Empty, Form, Input, InputNumber, Row, Space, Spin, Switch, Table, Tag, Typography, message } from 'antd';
import { ReloadOutlined, SendOutlined, KeyOutlined } from '@ant-design/icons';
import { diagnostics, type DiagnosticApplication, type DiagnosticEvent, type DiagnosticSettings, type Verification } from '@/api/aid/diagnostics';
import { useAuth } from '@/hooks/useAuth';

const { Text, Paragraph, Title } = Typography;
const stateLabels: Record<string, string> = { unknown: '正在核验授权', unavailable: '暂时无法核验授权', none: '尚未申请', pending: '等待官方审核', approved: '已获授权', rejected: '申请已驳回', revoked: '授权已撤销', expired: '授权已失效' };
const verificationLabels: Record<string, string> = { queued: '等待验证', pending: '正在验证', processing: '正在验证', running: '正在验证', verified: '验证成功', failed: '验证失败', expired: '验证已过期', revoked: '授权已撤销' };
const deliveryLabels: Record<string,string> = { queued: '等待发送', sending: '正在发送', received: '官方处理中', checking: '正在确认', delivered: '官方已收到', failed: '处理失败', unknown: '结果未确认' };

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
  const [key, setKey] = useState('');
  const [page, setPage] = useState(1);
  const [rows, setRows] = useState<DiagnosticEvent[]>([]);
  const [total, setTotal] = useState(0);
  const [selected, setSelected] = useState<React.Key[]>([]);
  const [detail, setDetail] = useState<Record<string, unknown>>();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [verification, setVerification] = useState<Verification>();
  const [includeServer, setIncludeServer] = useState(false);
  const [consent, setConsent] = useState(false);
  const [applicationForm] = Form.useForm();
  const [supportForm] = Form.useForm();
  const enabled = settings?.enabled === true;
  const approved = access.status === 'approved' && access.authorized === true && access.canOperate === true && !!access.observedIp && !accessLoading;
  const verifying = busy === 'verify' || ['queued', 'pending', 'running', 'processing'].includes(verification?.status || '');

  const clearDiagnostics = useCallback(() => {
    requestEpoch.current += 1;
    diagnosticRequests.current.forEach((controller) => controller.abort());
    diagnosticRequests.current.clear();
    setRows([]); setTotal(0); setSelected([]); setDetail(undefined); setDrawerOpen(false);
    setKey(''); supportForm.resetFields(); setVerification(undefined); setIncludeServer(false); setConsent(false);
  }, [supportForm]);
  const loadRows = useCallback(async () => {
    if (!authorizedRef.current) return;
    const epoch = requestEpoch.current;
    const controller = new AbortController(); diagnosticRequests.current.add(controller);
    try {
      const result = await diagnostics.events(page, controller.signal);
      if (authorizedRef.current && epoch === requestEpoch.current) { setRows(result.rows); setTotal(result.total); }
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
      const allowed = result.status === 'approved' && result.authorized === true && result.canOperate === true && !!result.observedIp;
      authorizedRef.current = allowed;
      setAccess({ ...result, authorized: allowed, canOperate: allowed });
    } catch {
      if (epoch === accessEpoch.current) setAccess((old) => ({ ...old, status: 'unavailable', authorized: false, canOperate: false, canApply: false, reason: '授权状态查询失败，请稍后刷新状态。' }));
    } finally { if (epoch === accessEpoch.current) setAccessLoading(false); }
  }, [clearDiagnostics]);
  useEffect(() => {
    let alive = true;
    void diagnostics.settings().then((value) => {
      if (!alive) return;
      setSettings(value);
      void refreshAccess();
    }).catch(() => { if (alive) { setAccessLoading(false); setAccess({ status: 'unavailable', canOperate: false, canApply: false, reason: '无法读取错误处理配置，请刷新页面。' }); } });
    return () => { alive = false; accessEpoch.current += 1; authorizedRef.current = false; clearDiagnostics(); };
  }, [clearDiagnostics, refreshAccess]);
  useEffect(() => {
    if (enabled && approved) void loadRows().catch(() => {});
    else if (!enabled) clearDiagnostics();
  }, [enabled, approved, loadRows, clearDiagnostics]);
  useEffect(() => {
    if (!approved || !enabled || !verification || !['queued', 'pending', 'running', 'processing'].includes(verification.status)) return;
    let alive = true;
    const epoch = requestEpoch.current;
    const timer = window.setTimeout(() => {
      if (!authorizedRef.current) return;
      const controller = new AbortController(); diagnosticRequests.current.add(controller);
      void diagnostics.verification(controller.signal).then((value) => { if (alive && authorizedRef.current && epoch === requestEpoch.current) setVerification(value); })
        .catch(() => { if (alive && authorizedRef.current && epoch === requestEpoch.current) setVerification({ status: 'failed' }); })
        .finally(() => diagnosticRequests.current.delete(controller));
    }, 2000);
    return () => { alive = false; window.clearTimeout(timer); };
  }, [verification, approved, enabled]);

  const action = async (name: string, run: () => Promise<void>) => {
    if (activeAction.current) return;
    activeAction.current = true;
    setBusy(name);
    try { await run(); } catch {
      if (['key', 'save-key', 'events', 'detail', 'verify', 'send', 'retry'].includes(name)) await refreshAccess();
    } finally { activeAction.current = false; setBusy(''); }
  };
  const submit = () => action('send', async () => {
    if (!authorizedRef.current) return;
    const values = supportForm.getFieldsValue();
    const optionalSupport = {
      testAccount: { username: values.testAccount, password: values.testPassword },
      adminAccount: { username: values.adminAccount, password: values.adminPassword },
      notes: values.description,
    };
    await diagnostics.send({ eventIds: selected, optionalSupport, includeServer });
    if (authorizedRef.current) { message.success('已提交发送，无需重复操作'); setSelected([]); await loadRows(); }
  });

  if (!settings) return <Card>{accessLoading ? <Spin tip="正在读取错误处理配置" /> : <Alert type="error" showIcon message={access.reason || '读取配置失败'} action={<Button onClick={() => window.location.reload()}>重试</Button>} />}</Card>;
  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Card>
      <Row justify="space-between" align="middle" gutter={[16, 12]}>
        <Col><Title level={4} style={{ margin: 0 }}>错误处理</Title><Text type="secondary">完整记录错误原文，由管理员选择并加密发送。</Text></Col>
        <Col><Space><Text>错误采集</Text><Switch aria-label="开启错误采集" checked={enabled} disabled={!manage || !!busy} loading={busy === 'settings'}
          onChange={(value) => void action('settings', async () => { const result = await diagnostics.save(value); setSettings((old) => ({ ...old!, ...result })); if (value) await refreshAccess(); })} /></Space></Col>
      </Row>
      <Paragraph type="secondary" style={{ marginBottom: 0, marginTop: 12 }}>本地错误保留 7 天。开启采集不会自动发送；智能体提示词和秘密字段会过滤，媒体文件以引用说明保留。</Paragraph>
      {approved && !!settings.captureFailures && <Alert style={{ marginTop: 12 }} type="warning" showIcon message={`有 ${settings.captureFailures} 次诊断未能完整保存，请检查存储容量与数据库状态。`} />}
    </Card>

    <Card title="官方白名单申请" extra={<Button icon={<ReloadOutlined />} loading={accessLoading} onClick={() => void refreshAccess()} disabled={accessLoading || !!busy}>刷新状态</Button>}>
      <Space wrap><Tag color={approved ? 'success' : 'warning'}>{stateLabels[access.status] || access.status}</Tag>
        <Text>服务器实际出口 IP：{access.observedIp || '暂未识别'}</Text>
        {access.applicationId && <Text>申请号：<Text copyable>{access.applicationId}</Text></Text>}
        {access.queryCredentialSaved && <Text type="secondary">查询凭据已安全保存</Text>}</Space>
      {access.reason && <Alert style={{ marginTop: 12 }} type="warning" message={access.reason} />}
      <Paragraph type="secondary" style={{ marginTop: 12 }}>官方按服务器实际出口 IP 人工审核。授权未通过时可开关本地采集、申请或刷新授权状态；诊断功能将在授权通过后开放。</Paragraph>
      {settings.metadataError && <Alert type="warning" showIcon message={settings.metadataError} style={{ marginBottom: 16 }} />}
      <Form form={applicationForm} layout="vertical" disabled={!manage || !access.canApply || accessLoading || !!busy} onFinish={(values) => void action('apply', async () => { const result = await diagnostics.apply(values); setAccess(result); message.success('申请已提交，请等待官方审核'); await refreshAccess(); })}>
        <Row gutter={16}><Col xs={24} md={12}><Form.Item name="platformName" label="平台名称" rules={[{ required: true, whitespace: true, message: '请填写平台名称' }]}><Input maxLength={100} /></Form.Item></Col>
          <Col xs={24} md={12}><Form.Item name="contact" label="联系方式" rules={[{ required: true, whitespace: true, message: '请填写联系方式' }]}><Input maxLength={250} /></Form.Item></Col></Row>
        <Form.Item name="description" label="申请说明" rules={[{ required: true, whitespace: true, message: '请简要说明申请用途' }]}><Input.TextArea rows={2} maxLength={2000} showCount /></Form.Item>
        <Button htmlType="submit" type="primary" loading={busy === 'apply'} disabled={!manage || !access.canApply || accessLoading || !!busy}>{['rejected', 'revoked', 'expired'].includes(access.status) ? '重新申请授权' : '提交白名单申请'}</Button>
      </Form>
    </Card>

    {!approved ? <Card><Alert type="warning" showIcon message="诊断功能已锁定" description={accessLoading ? '正在核验当前服务器的授权状态。' : '只有当前服务器出口 IP 经官方确认授权后，才能配置密钥、查看错误、验证连接或发送资料。'} /></Card>
      : !enabled ? <Card><Alert type="info" showIcon message="错误采集已关闭" description="开启上方采集开关后，可配置密钥、查看错误和发送报告。" /></Card>
      : <div>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Card title="加密密钥">
          <Space.Compact style={{ width: '100%', maxWidth: 760 }}><Input.Password aria-label="AES 密钥" autoComplete="new-password" disabled={!enabled || !manage} value={key} onChange={(event) => setKey(event.target.value)} placeholder={settings.keyConfigured ? '已配置；留空保留现有密钥' : '填写 Base64 编码的 AES-256 密钥'} />
            <Button disabled={!approved || !manage || !!busy} icon={<KeyOutlined />} loading={busy === 'key'} onClick={() => void action('key', async () => { if (!authorizedRef.current) return; const result = await diagnostics.key(); if (!authorizedRef.current) return; setKey(result.aesKey); setSettings((old) => ({ ...old!, keyConfigured: true })); message.success('密钥已生成并安全保存'); })}>生成</Button>
            <Button disabled={!approved || !manage || !key || !!busy} loading={busy === 'save-key'} onClick={() => void action('save-key', async () => { if (!authorizedRef.current) return; await diagnostics.save(true, key); if (!authorizedRef.current) return; setKey(''); setSettings((old) => ({ ...old!, keyConfigured: true })); message.success('密钥已保存'); })}>保存</Button></Space.Compact>
          <Paragraph type="secondary" style={{ marginTop: 10, marginBottom: 0 }}>密钥保存在本平台，发送时由官方公钥加密。每次报告使用不同的随机加密参数。</Paragraph>
        </Card>
        <Card title="错误记录" extra={<Button icon={<ReloadOutlined />} disabled={!approved || !!busy} onClick={() => void action('events', loadRows)}>刷新列表</Button>}>
          <Table<DiagnosticEvent> rowKey="eventId" size="middle" dataSource={rows} scroll={{ x: 760 }}
            rowSelection={{ selectedRowKeys: selected, onChange: setSelected, getCheckboxProps: (row) => ({ disabled: !enabled || !!row.reportId }) }}
            pagination={{ current: page, total, pageSize: 20, showSizeChanger: false, onChange: setPage }}
            locale={{ emptyText: <Empty description="暂无错误记录" /> }} columns={[
              { title: '请求编码', dataIndex: 'requestId', width: 310, render: (value) => <Text copyable>{value}</Text> },
              { title: '请求时间', dataIndex: 'requestTime', width: 220 },
              { title: '发送状态', dataIndex: 'reportId', render: (value, row) => value ? <Tag title={row.deliveryMessage}>{deliveryLabels[row.deliveryStatus || ''] || '已提交发送'}</Tag> : <Tag color="blue">待选择</Tag> },
              { title: '操作', key: 'actions', render: (_, row) => <Space wrap><Button type="link" disabled={!approved} onClick={() => void action('detail', async () => { if (!authorizedRef.current) return; const epoch = requestEpoch.current; const controller = new AbortController(); diagnosticRequests.current.add(controller); try { const result = await diagnostics.detail(row.eventId, controller.signal); if (authorizedRef.current && epoch === requestEpoch.current) { setDetail(result); setDrawerOpen(true); } } finally { diagnosticRequests.current.delete(controller); } })}>查看完整 JSON</Button>
                {row.reportId && ['failed','unknown'].includes(row.deliveryStatus || '') && <Button type="link" disabled={!approved || !canSend || !!busy} onClick={() => void action('retry', async () => {if (!authorizedRef.current) return; await diagnostics.retry(row.reportId!);if (!authorizedRef.current) return;message.success('已提交重新确认');await loadRows();})}>重新确认或发送</Button>}</Space> },
            ]} />
        </Card>
        <Card title="可选诊断资料">
          <Alert type="info" showIcon message={`如允许官方远程只读排查，请将官方出口 IP 加入目标服务器白名单：${settings.metadata?.supportEgressIps?.join('、') || '官方配置暂不可用'}`} />
          <Paragraph type="secondary" style={{ marginTop: 12 }}>仅在本次授权范围内读取日志和诊断信息，不主动新增、修改、删除业务文件或业务数据，不安装程序、不重启服务、不执行付费模型调用。连接和认证可能产生系统审计记录。</Paragraph>
          <Form form={supportForm} layout="vertical" disabled={!enabled || verifying} onValuesChange={(changed) => { if (['host', 'port', 'username', 'password'].some((field) => field in changed)) {setVerification(undefined);setIncludeServer(false);} }}>
            <Row gutter={16}><Col xs={24} md={12}><Form.Item name="host" label="服务器公网地址（选填）"><Input autoComplete="off" /></Form.Item></Col><Col xs={24} md={12}><Form.Item name="port" label="SSH 端口" initialValue={22}><InputNumber min={1} max={65535} style={{ width: '100%' }} /></Form.Item></Col>
              <Col xs={24} md={12}><Form.Item name="username" label="服务器账号"><Input autoComplete="off" /></Form.Item></Col><Col xs={24} md={12}><Form.Item name="password" label="服务器密码"><Input.Password autoComplete="new-password" /></Form.Item></Col></Row>
            <Space wrap style={{ marginBottom: 20 }}><Checkbox checked={consent} disabled={!enabled || verifying} onChange={(event) => {setConsent(event.target.checked);if(!event.target.checked)setIncludeServer(false);}}>同意本次连接验证与只读排查</Checkbox>
              <Button disabled={!approved || !consent || !manage || !!busy || ['queued', 'pending', 'running', 'processing'].includes(verification?.status || '')} loading={busy === 'verify'} onClick={() => void action('verify', async () => { if (!authorizedRef.current) return; const values = supportForm.getFieldsValue(); const result = await diagnostics.verify({ server: { host: values.host, port: values.port, username: values.username, password: values.password }, consent }); if (authorizedRef.current) setVerification(result); })}>验证服务器连接</Button>
              {verification && <Tag color={verification.status === 'verified' ? 'success' : 'processing'}>{verificationLabels[verification.status] || verification.status}</Tag>}
              <Checkbox disabled={!enabled || !consent || verification?.status !== 'verified'} checked={includeServer} onChange={(event) => setIncludeServer(event.target.checked)}>附带已验证服务器资料</Checkbox></Space>
            <Row gutter={16}><Col xs={24} md={12}><Form.Item name="testAccount" label="测试账号（选填）"><Input autoComplete="off" /></Form.Item></Col><Col xs={24} md={12}><Form.Item name="testPassword" label="测试密码（选填）"><Input.Password autoComplete="new-password" /></Form.Item></Col>
              <Col xs={24} md={12}><Form.Item name="adminAccount" label="后台账号（选填）"><Input autoComplete="off" /></Form.Item></Col><Col xs={24} md={12}><Form.Item name="adminPassword" label="后台密码（选填）"><Input.Password autoComplete="new-password" /></Form.Item></Col></Row>
            <Form.Item name="description" label="问题补充说明（选填）"><Input.TextArea rows={3} maxLength={4000} /></Form.Item>
          </Form>
          <Space wrap><Button type="primary" icon={<SendOutlined />} loading={busy === 'send'} disabled={!approved || !canSend || !selected.length || !settings.keyConfigured || !!busy} onClick={() => void submit()}>发送所选错误（{selected.length}）</Button><Text type="secondary">提交后后台异步发送，无需重复点击。</Text></Space>
        </Card>
      </Space>
    </div>}
    <Drawer title="完整诊断 JSON" width="min(960px, 95vw)" open={drawerOpen} onClose={() => setDrawerOpen(false)} destroyOnClose>
      <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontSize: 13 }}>{detail ? JSON.stringify(detail, null, 2) : ''}</pre>
    </Drawer>
  </Space>;
}
