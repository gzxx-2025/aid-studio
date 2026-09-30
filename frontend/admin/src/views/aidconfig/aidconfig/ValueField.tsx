import React, { useState } from 'react';
import { Input, InputNumber, Select, Switch } from 'antd';
import { EyeInvisibleOutlined, EyeOutlined } from '@ant-design/icons';

import AgentModelField from './AgentModelField';
import ShotDensityField from './ShotDensityField';
import ProjectGenConfigField from './ProjectGenConfigField';
import WxpayCertField from './WxpayCertField';
import UploadTypeLimitsField from './UploadTypeLimitsField';
import JsonArrayField from './JsonArrayField';
import ImageUpload from '@/components/ImageUpload';
import { BUILTIN_CAPTCHA_BACKGROUNDS } from '@/utils/builtinImages';
import JsonObjectEditor from '@/views/aid/aimanage/JsonObjectEditor';
import {
  CATEGORY_SELECT_OPTIONS,
  SELECT_FIELD_OPTIONS,
  isAgentModelJson,
  isBooleanLike,
  isJsonLike,
  isLongText,
  isNumericField,
  isSensitive
} from './maps';

interface Props {
  name: string;
  value: string;
  onChange: (v: string) => void;
  models?: any[];
  category?: string;
}

/**
 * 根据配置项名称 / 值智能选择合适的编辑控件
 */
export default function ValueField({ name, value, onChange, models, category }: Props) {
  const [showSecret, setShowSecret] = useState(false);

  // 0. 分类内专属控件（优先级最高）
  if (category === 'account_security' && name === 'cancel_re_registration_enabled') {
    const checked = value === 'true';
    return (
      <div style={{ width: '100%' }}>
        <div className="switch-wrap">
          <Switch checked={checked} onChange={(enabled) => onChange(enabled ? 'true' : 'false')} />
          <span className="switch-text">{checked ? '已开启' : '已关闭'}</span>
        </div>
        <div style={{ marginTop: 6, color: '#64748b', fontSize: 12, lineHeight: '20px' }}>
          开启后，原手机号、邮箱或微信在限制期内不能再次注册；关闭后注销账号可以立即重新注册。
        </div>
      </div>
    );
  }
  if (category === 'account_security' && name === 'cancel_re_registration_days') {
    return (
      <div style={{ width: '100%' }}>
        <InputNumber
          value={value === '' || value === null || value === undefined ? undefined : Number(value)}
          onChange={(days) => onChange(days == null ? '' : String(days))}
          min={1}
          max={3650}
          precision={0}
          addonAfter="天"
          style={{ width: '100%' }}
        />
        <div style={{ marginTop: 6, color: '#64748b', fontSize: 12, lineHeight: '20px' }}>
          允许设置 1—3650 天，仅在上方限制开关开启时生效，默认 15 天。
        </div>
      </div>
    );
  }
  // 0.0 微信支付证书字段：privateKey（应用私钥/apiclient_key.pem）、serialNo（证书序列号/apiclient_cert.pem）
  //     支持上传证书文件并解析内容/序列号，仅解析存库、不落盘。
  if (category === 'wxpay' && (name === 'privateKey' || name === 'serialNo' || name === 'publicKey')) {
    return <WxpayCertField name={name} value={value} onChange={onChange} />;
  }
  // 0.0.0 分镜镜头数下限锚点：storyboard / shot_density_floor —— 用「每镜字数+上浮比例」表单替代裸 JSON，带三档预览
  if (category === 'storyboard' && name === 'shot_density_floor') {
    return <ShotDensityField value={value} onChange={onChange} />;
  }
  // 0.0.1 项目生成配置兜底：project_gen_config / sceneCode —— 双模式（经济/性能）表单
  if (category === 'project_gen_config') {
    return (
      <ProjectGenConfigField
        name={name}
        value={value}
        onChange={onChange}
      />
    );
  }
  // 基础配置中的交流二维码统一走图片上传，上传成功后保存对象存储地址。
  if (category === 'basic' && name === 'exchange_image_url') {
    return (
      <ImageUpload
        value={value}
        onChange={(v) => onChange(v)}
        maxCount={1}
        maxSize={5}
        accept="image/*"
      />
    );
  }
  // SEO 关键词以逗号持久化，后台用标签逐个维护，支持回车、中文逗号和英文逗号分隔。
  if (category === 'basic' && name === 'site_keywords') {
    const keywords = value
      ? value
          .split(/[,，]/)
          .map((item) => item.trim())
          .filter(Boolean)
      : [];
    return (
      <Select
        mode="tags"
        value={keywords}
        tokenSeparators={[',', '，']}
        placeholder="输入关键词后按回车，例如 AI漫画"
        maxTagCount="responsive"
        style={{ width: '100%' }}
        onChange={(items) =>
          onChange(
            Array.from(new Set((items as string[]).map((item) => item.trim()).filter(Boolean))).join(',')
          )
        }
      />
    );
  }
  if (category === 'basic' && name === 'site_description') {
    return (
      <Input.TextArea
        value={value}
        onChange={(e) => onChange(e.target.value)}
        rows={4}
        maxLength={300}
        showCount
        placeholder="概括平台定位与核心能力，建议 80—160 个字"
      />
    );
  }
  // 短信宝由本平台维护完整短信内容，{code} 会在发送前替换为实际验证码。
  if (category === 'sms' && name === 'smsBaoContentTemplate') {
    return (
      <div style={{ width: '100%' }}>
        <Input.TextArea
          value={value}
          onChange={(e) => onChange(e.target.value)}
          rows={3}
          maxLength={300}
          showCount
          placeholder="例如：【视觉AID】您的验证码是{code}"
        />
        <div style={{ marginTop: 6, color: '#64748b', fontSize: 12 }}>
          内容需包含 <code>{'{code}'}</code>，签名请直接写在模板开头。
        </div>
      </div>
    );
  }
  // 0.1 captcha 背景图：走后台管理上传接口（按 aid_config oss.uploadMode 动态分发 local/oss），
  //     自动回填地址（本地模式回填 /profile/... ，OSS 模式回填远程 URL），逗号分隔多图
  if (category === 'captcha' && name === 'background_urls') {
    return (
      <div>
        <ImageUpload
          value={value === BUILTIN_CAPTCHA_BACKGROUNDS.join(',') ? '' : value}
          onChange={(v) => onChange(v)}
          maxCount={10}
          maxSize={5}
          accept="image/*"
        />
        <div style={{ marginTop: 8, color: '#64748b', fontSize: 12 }}>留空时使用以下内置背景；上传后优先使用自定义背景。</div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 8 }}>
          {BUILTIN_CAPTCHA_BACKGROUNDS.map((src, index) => <img key={src} src={src} alt={`内置验证码背景 ${index + 1}`} width={84} height={52} style={{ objectFit: 'cover', borderRadius: 4 }} />)}
        </div>
      </div>
    );
  }
  // 0.2 分类型上传限制（oss.uploadTypeLimits）：JSON 数组，用表单（类型名+扩展名标签+大小MB）维护，运营无需手写 JSON
  if (category === 'oss' && name === 'uploadTypeLimits') {
    return <UploadTypeLimitsField value={value} onChange={onChange} />;
  }
  // 0.2.1 微信公众号关注后自动回复：多行文本，留空则关注后不回复
  if (category === 'wxLogin' && name === 'wxLoginSubscribeReply') {
    return (
      <div style={{ width: '100%' }}>
        <Input.TextArea
          value={value}
          onChange={(e) => onChange(e.target.value)}
          rows={4}
          maxLength={600}
          showCount
          placeholder="用户关注公众号后自动回复的文本内容，留空则不回复"
        />
        <div style={{ marginTop: 6, color: '#64748b', fontSize: 12, lineHeight: '20px' }}>
          用户关注公众号（subscribe 事件）后由回调接口被动回复；支持换行，保存后需点击「同步配置」生效。
        </div>
      </div>
    );
  }
  // 0.3 图片URL域名白名单（oss.imageUrlWhitelist）：逗号分隔，用标签式输入更直观
  if (category === 'oss' && name === 'imageUrlWhitelist') {
    const arr = value ? value.split(',').map((s) => s.trim()).filter(Boolean) : [];
    return (
      <Select
        mode="tags"
        value={arr}
        tokenSeparators={[',', '，']}
        notFoundContent={null}
        placeholder="输入可信图片域名前缀，回车或逗号分隔，如 https://cdn.xxx.com"
        style={{ width: '100%' }}
        onChange={(vals) => onChange((vals as string[]).map((s) => s.trim()).filter(Boolean).join(','))}
      />
    );
  }

  if (category === 'oss' && name === 'resourceAccessDomain') {
    return (
      <div style={{ width: '100%' }}>
        <Input
          value={value}
          onChange={(e) => onChange(e.target.value.trim())}
          placeholder="https://cdn.example.com"
        />
        <div style={{ marginTop: 6, color: '#64748b', fontSize: 12, lineHeight: '20px' }}>
          必填。填写浏览器和服务端都能访问的完整地址；使用非默认端口时须写明端口（如 https://media.example.com:8443）。不要填写 /profile、对象路径或查询参数。保存前会验证上传文件能否通过此地址读取。
        </div>
      </div>
    );
  }

  if (category === 'oss' && name === 'modelSignedUrlExpireHours') {
    return (
      <div style={{ width: '100%' }}>
        <InputNumber
          value={value ? Number(value) : 72}
          min={1}
          max={168}
          precision={0}
          addonAfter="小时"
          style={{ width: '100%' }}
          onChange={(v) => onChange(v == null ? '' : String(v))}
        />
        <div style={{ marginTop: 6, color: '#64748b', fontSize: 12, lineHeight: '20px' }}>
          仅在任务取得并发名额、即将提交外部大模型时读取。COS、OSS、七牛云会生成临时签名链接；OSS 配置内网 Endpoint 时自动使用同地域公网 Endpoint 签名。推荐 72 小时，最长 168 小时。
        </div>
      </div>
    );
  }

  // 模型官方原价换算为积分的系统基础倍率。字段说明固定在页面，避免依赖数据库备注展示。
  if (category === 'media' && name === 'ai_billing_global_multiplier') {
    return (
      <div style={{ width: '100%' }}>
        <InputNumber
          value={value === '' || value === null || value === undefined ? undefined : Number(value)}
          onChange={(v) => onChange(v == null ? '' : String(v))}
          min={0.01}
          max={100000}
          precision={4}
          controls={false}
          addonAfter="积分/元"
          style={{ width: '100%' }}
        />
        <div style={{ marginTop: 6, color: '#64748b', fontSize: 12, lineHeight: '20px' }}>
          最终积分 = SKU 官方原价（元）× 模型基础倍率 × 单模型倍率；公众号金额 = 积分 ÷ 本倍率。
        </div>
      </div>
    );
  }

  // 0.3 分类内下拉（如 captcha.type）
  if (category && CATEGORY_SELECT_OPTIONS[category]?.[name]) {
    return (
      <Select
        value={value}
        options={CATEGORY_SELECT_OPTIONS[category][name]}
        onChange={(v) => onChange(v)}
        placeholder="请选择"
        style={{ width: '100%' }}
      />
    );
  }

  // 1. 下拉选择
  if (SELECT_FIELD_OPTIONS[name]) {
    return (
      <Select
        value={value}
        options={SELECT_FIELD_OPTIONS[name]}
        onChange={(v) => onChange(v)}
        placeholder="请选择"
        style={{ width: '100%' }}
      />
    );
  }

  // 2. 布尔开关
  if (isBooleanLike(value)) {
    const checked = value === 'true';
    return (
      <div className="switch-wrap">
        <Switch checked={checked} onChange={(c) => onChange(c ? 'true' : 'false')} />
        <span className="switch-text">{checked ? '已开启' : '已关闭'}</span>
      </div>
    );
  }

  // 3. agent_model 字段：专用下拉 + 可选默认参数
  if (isAgentModelJson(name)) {
    return <AgentModelField name={name} value={value} onChange={onChange} models={models || []} />;
  }

  // 4. 其它 JSON 结构：普通对象用可视化键值编辑，数组用可视化列表编辑，均无需手写 JSON
  if (isJsonLike(value)) {
    if (isPlainObjectValue(value)) {
      return (
        <JsonObjectEditor
          value={value}
          onChange={(jsonStr) => onChange(jsonStr ?? '')}
          emptyText="暂无参数，点击添加"
        />
      );
    }
    return <JsonArrayField value={value} onChange={onChange} />;
  }

  // 4. 敏感字段（密码框）
  if (isSensitive(name)) {
    return (
      <Input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        type={showSecret ? 'text' : 'password'}
        placeholder="请输入配置值"
        suffix={
          <span
            style={{ cursor: 'pointer', color: '#94a3b8' }}
            onClick={() => setShowSecret((s) => !s)}
          >
            {showSecret ? <EyeInvisibleOutlined /> : <EyeOutlined />}
          </span>
        }
      />
    );
  }

  // 5. 数字
  if (isNumericField(category, name)) {
    return (
      <InputNumber
        value={value === '' || value === null || value === undefined ? undefined : Number(value)}
        onChange={(v) => onChange(v == null ? '' : String(v))}
        placeholder="请输入数值"
        controls={false}
        style={{ width: '100%' }}
      />
    );
  }

  // 6. 长文本
  if (isLongText(value)) {
    return (
      <Input.TextArea
        value={value}
        onChange={(e) => onChange(e.target.value)}
        rows={4}
        placeholder="请输入配置值"
      />
    );
  }

  // 7. 普通文本
  return (
    <Input
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder="请输入配置值"
      allowClear
    />
  );
}

/** 判断值是否为可用键值编辑器可视化编辑的普通 JSON 对象（空串视为空对象） */
function isPlainObjectValue(v: string): boolean {
  const s = (v ?? '').trim();
  if (!s) return true;
  try {
    const parsed = JSON.parse(s);
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed);
  } catch {
    return false;
  }
}
