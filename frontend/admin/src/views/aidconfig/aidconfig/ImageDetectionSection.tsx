import { useEffect, useRef, useState } from 'react';
import { Button, Form, Input, InputNumber, Radio, Select, Space, Spin, Switch, Tag, message } from 'antd';
import { ExperimentOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import PageCard from '@/components/PageCard';
import {
  getImageDetectionConfig,
  saveImageDetectionConfig,
  testImageDetection,
  type ImageDetectionConfig,
  type ImageDetectionTestResult
} from '@/api/aidconfig/imageDetection';

function normalize(raw: Record<string, string>): ImageDetectionConfig {
  return {
    enabled: raw.enabled === 'true',
    provider: 'tencent_ci',
    credentialSource: raw.credentialSource === 'DEDICATED' ? 'DEDICATED' : 'COS_STORAGE',
    region: raw.region || '',
    bucketName: raw.bucketName || '',
    secretId: raw.secretId || '',
    secretKey: raw.secretKey || '',
    cosImageAccessMode: raw.cosImageAccessMode === 'PUBLIC_URL' ? 'PUBLIC_URL' : 'COS_OBJECT',
    connectTimeoutMs: Number(raw.connectTimeoutMs || 3000),
    readTimeoutMs: Number(raw.readTimeoutMs || 15000),
    maxCallsPerUserMinute: Number(raw.maxCallsPerUserMinute || 10),
    uploadMode: raw.uploadMode || 'local'
  };
}

export default function ImageDetectionSection() {
  const [form] = Form.useForm<ImageDetectionConfig>();
  const [testForm] = Form.useForm<{ objectKey?: string; imageUrl?: string }>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const testInFlight = useRef(false);
  const [uploadMode, setUploadMode] = useState('local');
  const [result, setResult] = useState<ImageDetectionTestResult | null>(null);
  const credentialSource = Form.useWatch('credentialSource', form);
  const cosImageAccessMode = Form.useWatch('cosImageAccessMode', form);
  const useCosObject = uploadMode === 'cos' && cosImageAccessMode !== 'PUBLIC_URL';

  const load = async () => {
    setLoading(true);
    try {
      const response: any = await getImageDetectionConfig();
      const config = normalize(response.data || {});
      form.setFieldsValue(config);
      setUploadMode(config.uploadMode);
    } catch {
      message.error('图像识别配置读取失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const save = async () => {
    try {
      const values = await form.validateFields();
      setSaving(true);
      const data = { ...values };
      if (credentialSource === 'COS_STORAGE') {
        delete (data as Partial<ImageDetectionConfig>).region;
        delete (data as Partial<ImageDetectionConfig>).bucketName;
        delete (data as Partial<ImageDetectionConfig>).secretId;
        delete (data as Partial<ImageDetectionConfig>).secretKey;
      }
      if (uploadMode !== 'cos') delete (data as Partial<ImageDetectionConfig>).cosImageAccessMode;
      await saveImageDetectionConfig(data);
      setResult(null);
      message.success('保存成功，请测试实际识别');
      await load();
    } catch (error: any) {
      if (!error?.errorFields) message.error('保存失败');
    } finally {
      setSaving(false);
    }
  };

  const runTest = async () => {
    if (testInFlight.current) return;
    testInFlight.current = true;
    setTesting(true);
    setResult(null);
    try {
      const values = await testForm.validateFields();
      const response: any = await testImageDetection(useCosObject
        ? { sourceType: 'COS_OBJECT', objectKey: values.objectKey?.trim() }
        : { sourceType: 'VERIFIED_URL', imageUrl: values.imageUrl?.trim() });
      setResult(response.data as ImageDetectionTestResult);
    } catch (error: any) {
      if (!error?.errorFields) message.error('测试请求失败，请查看网络状态');
    } finally {
      testInFlight.current = false;
      setTesting(false);
    }
  };

  if (loading) return <Spin />;

  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <PageCard title="图像识别 / 视觉服务">
      <Form form={form} layout="vertical" initialValues={{ enabled: false, credentialSource: 'COS_STORAGE', cosImageAccessMode: 'COS_OBJECT' }}>
        <Form.Item name="enabled" label="启用图像主体检测" valuePropName="checked"><Switch /></Form.Item>
        <Form.Item label="供应商"><Input value="腾讯云数据万象 AIObjectDetect" disabled /></Form.Item>
        <Form.Item name="credentialSource" label="凭证来源" rules={[{ required: true }]}>
          <Select options={[{ value: 'COS_STORAGE', label: '复用文件存储中的腾讯云 COS 配置' }, { value: 'DEDICATED', label: '独立数据万象凭证' }]} />
        </Form.Item>
        {credentialSource === 'COS_STORAGE' ? <>
          <Form.Item name="region" label="COS 地域"><Input disabled /></Form.Item>
          <Form.Item name="bucketName" label="用于 CI 调用的 Bucket"><Input disabled /></Form.Item>
          <div>此处始终读取文件存储中保存的 COS 配置，与当前上传模式无关。</div>
        </> : <>
          <Form.Item name="region" label="地域" rules={[{ required: true, message: '请输入地域' }]}><Input placeholder="ap-guangzhou" /></Form.Item>
          <Form.Item name="bucketName" label="用于 CI 调用的 Bucket" rules={[{ required: true, message: '请输入 Bucket' }]}><Input /></Form.Item>
          <Form.Item name="secretId" label="SecretId"><Input.Password placeholder="留空或保留掩码则不修改" /></Form.Item>
          <Form.Item name="secretKey" label="SecretKey"><Input.Password placeholder="留空或保留掩码则不修改" /></Form.Item>
        </>}
        {uploadMode === 'cos' && <Form.Item name="cosImageAccessMode" label="COS 图片取图方式">
          <Radio.Group options={[{ value: 'COS_OBJECT', label: 'COS 桶直连' }, { value: 'PUBLIC_URL', label: '公网访问' }]} />
        </Form.Item>}
        <Form.Item name="connectTimeoutMs" label="连接超时（毫秒）" rules={[{ required: true }]}><InputNumber min={500} max={30000} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="readTimeoutMs" label="读取超时（毫秒）" rules={[{ required: true }]}><InputNumber min={1000} max={120000} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="maxCallsPerUserMinute" label="单用户每分钟调用上限" rules={[{ required: true }]}><InputNumber min={1} max={120} style={{ width: '100%' }} /></Form.Item>
        <p>平台承担供应商费用，用户不扣积分。保存后需用真实图片测试，确认数据万象已绑定调用 Bucket。</p>
        <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={save}>保存配置</Button>
      </Form>
    </PageCard>
    <PageCard title="测试识别">
      <p>每点击测试一次，最多发起 1 次腾讯云识别请求，可能产生 1 次供应商计费；用户积分不扣除。超时或断线时是否计费以腾讯云账单为准。</p>
      <Form form={testForm} layout="vertical">
        {useCosObject ? <Form.Item name="objectKey" label="已登记 COS 图片的对象键" rules={[{ required: true, message: '请输入对象键' }]}>
          <Input placeholder="folder/image.jpg" />
        </Form.Item> : <Form.Item name="imageUrl" label="已登记图片的受控 HTTPS 地址" rules={[{ required: true, message: '请输入图片地址' }, { type: 'url', message: '请输入有效地址' }]}>
          <Input placeholder="https://cdn.example.com/image.jpg" />
        </Form.Item>}
        <Space><Button icon={<ExperimentOutlined />} type="primary" loading={testing} disabled={saving} onClick={runTest}>测试识别</Button>
          <Button icon={<ReloadOutlined />} onClick={load} disabled={testing}>刷新配置</Button></Space>
      </Form>
      {result && <div style={{ marginTop: 16 }}>
        <Tag color={result.status === 'SUCCESS' ? 'green' : 'red'}>{result.status}</Tag>
        <div>实际识别请求：{result.providerCalls} 次；主体：{result.objectCount} 个；耗时：{result.elapsedMs} 毫秒</div>
        {result.providerRequestId && <div>供应商请求 ID：{result.providerRequestId}</div>}
        {result.error && <div>原因：{result.error}</div>}
      </div>}
    </PageCard>
  </Space>;
}
