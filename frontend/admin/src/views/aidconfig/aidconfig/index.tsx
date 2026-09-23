import React, { useEffect, useMemo, useState } from 'react';
import { Button, Empty, Form, Input, InputNumber, Modal, Spin, Tag, message } from 'antd';
import {
  CheckOutlined,
  ExperimentOutlined,
  FolderOpenOutlined,
  KeyOutlined,
  LinkOutlined,
  ReloadOutlined,
  SaveOutlined,
  SettingOutlined,
  SyncOutlined,
  UndoOutlined,
  UsergroupAddOutlined,
  WarningOutlined
} from '@ant-design/icons';

import {
  listAidconfig,
  updateAidconfig,
  addAidconfig,
  refreshConfig,
  getCurrentConfig,
  saveStorageConfig,
  testMqSend,
  testSmsSend
} from '@/api/aidconfig/aidconfig';
import { listModel as listAidmodel } from '@/api/aid/aimanage';
import TestConnectionButton from '@/components/TestConnectionButton';
import type { ConfigTestKey } from '@/api/system/configTest';

/**
 * 各配置分类 → 后端连通性测试 testKey 映射。
 * 仅这些分类在头部显示「测试连接」按钮。
 *
 * 注：image_moderation 走专用 `ImageModerationSection`（multipart 上传 + URL 双形态），
 * 不进通用映射，避免与区块内的「测试审查」按钮重复。
 */
const CONFIG_TEST_KEY: Record<string, ConfigTestKey> = {
  alipay: 'alipay',
  mail: 'smtp',
  oss: 'oss',
  sms: 'sms',
  wxpay: 'wxpay'
};

/**
 * 各配置分类的字段名重映射（页面 configName → 后端 Tester 读取的 payload key）。
 * 未列出的字段按原名透传。
 */
const CONFIG_TEST_FIELD_REMAP: Record<string, Record<string, string>> = {
  // 邮箱：页面 user/pass → 后端 username/password
  mail: { user: 'username', pass: 'password' },
  // 短信：页面 providerType → 后端 provider
  sms: { providerType: 'provider' }
};

import ValueField from './ValueField';
import ImageModerationSection from './ImageModerationSection';
import MediaProcessSection from './MediaProcessSection';
import TencentAsrSection from './TencentAsrSection';
import AdminEntrySection from './AdminEntrySection';
import DefaultAvatarSection from './DefaultAvatarSection';
import AdminBrandSection from './AdminBrandSection';
import BasicConfigSection from './BasicConfigSection';
import WechatNotifySection from './WechatNotifySection';
import {
  CATEGORY_NAMES,
  CONFIG_SECTIONS,
  PROVIDER_SPECIFIC_FIELDS,
  PROVIDER_FILTER_DRIVER,
  SYNCABLE_CATEGORIES,
  formatConfigLabel,
  formatCurrentValue,
  isRestartRequired
} from './maps';
import './style.less';

/**
 * 图片审查分类（image_moderation）的渲染走专用 `ImageModerationSection`，
 * 它使用专用的 /aidconfig/imgmoderation/* 接口（含密钥脱敏回写保护和 multipart 测试），
 * 比 aid_config 通用 CRUD 更安全易用。
 */
const IMAGE_MODERATION_CATEGORY = 'image_moderation';

/** 媒体处理分类（mps）：走专用 MediaProcessSection（地域/分辨率/编码下拉 + 单价表单，不写 JSON） */
const MEDIA_PROCESS_CATEGORY = 'mps';

/** 腾讯云语音识别分类：走专用中文表单，不展示英文配置键。 */
const TENCENT_ASR_CATEGORY = 'tencent_asr';

/** 后台登录入口安全分类：走专用 AdminEntrySection（开关 + 生成访问码 + 登录链接） */
const ADMIN_ENTRY_CATEGORY = 'admin_entry';

/** 默认头像分类：走专用 DefaultAvatarSection（管理员上传最多5张，注册随机选取） */
const DEFAULT_AVATAR_CATEGORY = 'default_avatar';

/** 后台品牌图片：走专用 AdminBrandSection（平台 LOGO + 页签图标上传） */
const ADMIN_BRAND_CATEGORY = 'admin_brand';

/** 微信公众号推送分类：走专用 WechatNotifySection（模板 JSON 表单化维护） */
const WECHAT_NOTIFY_CATEGORY = 'wx_notify';

/** 基础配置分类：走分组化信息架构，但沿用页面统一保存流程。 */
const BASIC_CATEGORY = 'basic';

/** 数据库缺失时注入的必备基础配置项，保存后自动创建真实记录。 */
const REQUIRED_BASIC_FIELDS = [
  { id: -401, configName: 'site_name', configDict: '网站名称', orderNum: 1 },
  { id: -404, configName: 'membership_agreement', configDict: '会员协议', orderNum: 4 }
];

/** 数据库缺失时注入的短信宝配置项。 */
const REQUIRED_SMS_BAO_FIELDS = [
  { id: -411, configName: 'smsBaoUsername', configDict: '短信宝用户名', configValue: '', orderNum: 20 },
  { id: -412, configName: 'smsBaoApiKey', configDict: '短信宝API Key', configValue: '', orderNum: 21 },
  { id: -413, configName: 'smsBaoProductId', configDict: '短信宝产品ID', configValue: '', orderNum: 22 },
  {
    id: -414,
    configName: 'smsBaoContentTemplate',
    configDict: '短信内容模板（含签名）',
    configValue: '【视觉AID】您的验证码是{code}',
    orderNum: 23
  }
];

/** 数据库缺失时注入的文件存储必备配置项。 */
const REQUIRED_STORAGE_FIELDS = [
  { id: -421, configName: 'qiniuAccessKey', configDict: '七牛云AccessKey', configValue: '', orderNum: 20 },
  { id: -422, configName: 'qiniuSecretKey', configDict: '七牛云SecretKey', configValue: '', orderNum: 21 },
  { id: -423, configName: 'qiniuBucketName', configDict: '七牛云Bucket名称', configValue: '', orderNum: 22 },
  { id: -424, configName: 'qiniuPrefix', configDict: '七牛云路径前缀', configValue: 'upload', orderNum: 23 },
  { id: -425, configName: 'resourceAccessDomain', configDict: '资源访问地址', configValue: '', orderNum: 3 },
  { id: -426, configName: 'modelSignedUrlExpireHours', configDict: '模型临时链接有效期(小时)', configValue: '72', orderNum: 4 }
];

/** 数据库缺失时注入的账号注销后再次注册限制配置。 */
const REQUIRED_ACCOUNT_SECURITY_FIELDS = [
  {
    id: -431,
    configName: 'cancel_re_registration_enabled',
    configDict: '注销后再次注册限制开关',
    configValue: 'true',
    orderNum: 1
  },
  {
    id: -432,
    configName: 'cancel_re_registration_days',
    configDict: '注销后再次注册限制天数',
    configValue: '15',
    orderNum: 2
  }
];

/** 专属页面维护或程序内部使用的配置不进入通用编辑入口，数据库值仍保留。 */
const HIDDEN_CATEGORIES = new Set<string>([
  'realAuth', 'system_upgrade', 'official_gateway', 'seo',
  'provider_balance', 'media_eta', 'error_diagnostics'
]);

/** SEO 统一由专用页面维护；历史默认值保留在数据库供兼容读取，不在这里编辑。 */
const HIDDEN_BASIC_FIELDS = new Set<string>(['version_number', 'site_description', 'site_keywords']);

interface ConfigItem {
  id: number;
  configKey?: string;
  configName: string;
  configValue: string;
  configDict?: string;
  category?: string;
  remark?: string;
  orderNum?: number;
  _original: string;
  _modified: boolean;
}

interface Group {
  category: string;
  items: ConfigItem[];
}

export default function AidconfigPage() {
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [groups, setGroups] = useState<Group[]>([]);
  const [activeCategory, setActiveCategory] = useState<string>('');
  const [currentActiveConfig, setCurrentActiveConfig] = useState<Record<string, any>>({});
  const [models, setModels] = useState<any[]>([]);
  const [testing, setTesting] = useState(false);
  const [smsTestOpen, setSmsTestOpen] = useState(false);
  const [smsTestForm] = Form.useForm();
  // taskq：按用户设并发上限
  const [userLimitOpen, setUserLimitOpen] = useState(false);
  const [userLimitForm] = Form.useForm();
  const [userLimitSaving, setUserLimitSaving] = useState(false);

  const getCategoryName = (cat: string) => CATEGORY_NAMES[cat] || cat;

  /**
   * 左侧「分区」：按 CONFIG_SECTIONS 把分类归组，仅保留数据库里真实存在的分类；
   * 未被任何分区收纳的分类单独成区兜底，保证配置不丢失。
   */
  const sections = useMemo(() => {
    const existing = new Set(groups.map((g) => g.category));
    const result = CONFIG_SECTIONS.map((s) => ({
      ...s,
      categories: s.categories.filter((c) => existing.has(c))
    })).filter((s) => s.categories.length > 0);

    const claimed = new Set(result.flatMap((s) => s.categories));
    groups
      .map((g) => g.category)
      .filter((c) => !claimed.has(c))
      .forEach((c) => result.push({ key: c, name: getCategoryName(c), categories: [c] }));
    return result;
  }, [groups]);

  const activeSection = useMemo(
    () => sections.find((s) => s.categories.includes(activeCategory)) || sections[0],
    [sections, activeCategory]
  );

  // 分区数据就绪后，若当前激活分类不在任何分区中，回退到首个分区的首个分类
  useEffect(() => {
    if (sections.length === 0) return;
    const ok = sections.some((s) => s.categories.includes(activeCategory));
    if (!ok) setActiveCategory(sections[0].categories[0]);
  }, [sections, activeCategory]);

  const loadList = async () => {
    setLoading(true);
    try {
      const res: any = await listAidconfig({ pageNum: 1, pageSize: 1000 });
      const rows: any[] = res.rows || [];
      const items: ConfigItem[] = rows.map((r) => ({
        ...r,
        _original: r.configValue,
        _modified: false
      }));

      // 前端硬过滤：彻底隐藏 agent_model 分类下"视频模型 · 经济/性能"两条（已废弃），
      // 即便数据库里还残留也不再展示，避免误编辑。
      const HIDDEN_AGENT_MODEL_FIELDS = new Set(['video_economy_model_code', 'video_performance_model_code']);
      const visibleItems = items.filter(
        (it) =>
          !HIDDEN_CATEGORIES.has(it.category || '') &&
          !(it.category === 'basic' && HIDDEN_BASIC_FIELDS.has(it.configName)) &&
          !(it.category === 'agent_model' && HIDDEN_AGENT_MODEL_FIELDS.has(it.configName))
      );

      const grouped: Record<string, Group> = {};
      visibleItems.forEach((it) => {
        const cat = it.category || '默认分组';
        if (!grouped[cat]) grouped[cat] = { category: cat, items: [] };
        grouped[cat].items.push(it);
      });

      const list = Object.values(grouped).sort((a, b) => a.category.localeCompare(b.category, 'zh-CN'));

      /** 确保指定分类具备后台必须维护的字段，已有数据库未执行脚本时也可直接编辑。 */
      const ensureFields = (
        category: string,
        definitions: Array<{
          id: number;
          configName: string;
          configDict: string;
          configValue?: string;
          orderNum: number;
        }>
      ) => {
        let group = list.find((item) => item.category === category);
        if (!group) {
          group = { category, items: [] };
          list.push(group);
        }
        definitions.forEach((definition) => {
          if (group!.items.some((item) => item.configName === definition.configName)) return;
          group!.items.push({
            ...definition,
            category,
            configValue: definition.configValue || '',
            _original: definition.configValue || '',
            _modified: false
          } as ConfigItem);
        });
      };

      ensureFields(BASIC_CATEGORY, REQUIRED_BASIC_FIELDS);
      ensureFields('sms', REQUIRED_SMS_BAO_FIELDS);
      ensureFields('oss', REQUIRED_STORAGE_FIELDS);
      ensureFields('account_security', REQUIRED_ACCOUNT_SECURITY_FIELDS);
      const storageGroup = list.find((g) => g.category === 'oss');
      const resourceDomain = storageGroup?.items.find((it) => it.configName === 'resourceAccessDomain');
      const uploadMode = storageGroup?.items.find((it) => it.configName === 'uploadMode')?.configValue || 'local';
      const legacyNames = uploadMode === 'cos'
        ? ['cosCdnDomain', 'cdnDomain']
        : uploadMode === 'local' ? ['localDomain', 'cdnDomain'] : ['cdnDomain'];
      const legacyDomain = legacyNames
        .map((name) => storageGroup?.items.find((it) => it.configName === name))
        .find((item) => Boolean(item?.configValue));
      if (resourceDomain && !resourceDomain.configValue && legacyDomain?.configValue) {
        resourceDomain.configValue = legacyDomain.configValue;
        resourceDomain._modified = true;
      }
      // 模型基础倍率是计费必填项。数据库尚未初始化时也提供可保存的页面占位项；说明固定写在页面，不落 configDict。
      let mediaGroup = list.find((g) => g.category === 'media');
      if (!mediaGroup) {
        mediaGroup = { category: 'media', items: [] };
        list.push(mediaGroup);
      }
      if (!mediaGroup.items.some((it) => it.configName === 'ai_billing_global_multiplier')) {
        mediaGroup.items.push({
          id: -301,
          category: 'media',
          configName: 'ai_billing_global_multiplier',
          configValue: '100',
          _original: '',
          _modified: true
        } as ConfigItem);
      }
      list.sort((a, b) => a.category.localeCompare(b.category, 'zh-CN'));
      // oss 分组确保存在「分类型上传限制」字段：数据库无此行时注入占位项（id=0），
      // 让后台始终能以表单维护，保存时自动新增该行（见 saveCurrentGroup）。
      const ossGroup = list.find((g) => g.category === 'oss');
      if (ossGroup && !ossGroup.items.some((it) => it.configName === 'uploadTypeLimits')) {
        ossGroup.items.push({
          id: 0,
          category: 'oss',
          configName: 'uploadTypeLimits',
          configValue: '',
          configDict: '分类型上传限制(JSON：name/maxSizeMb/extensions)',
          _original: '',
          _modified: false
        } as ConfigItem);
      }
      const wxpayGroup = list.find((g) => g.category === 'wxpay');
      if (wxpayGroup) {
        if (!wxpayGroup.items.some((it) => it.configName === 'publicKeyId')) {
          wxpayGroup.items.push({
            id: -101,
            category: 'wxpay',
            configName: 'publicKeyId',
            configValue: '',
            configDict: '微信支付公钥ID',
            _original: '',
            _modified: false
          } as ConfigItem);
        }
        if (!wxpayGroup.items.some((it) => it.configName === 'publicKey')) {
          wxpayGroup.items.push({
            id: -102,
            category: 'wxpay',
            configName: 'publicKey',
            configValue: '',
            configDict: '微信支付公钥内容',
            _original: '',
            _modified: false
          } as ConfigItem);
        }
      }
      // wxLogin：确保存在「关注后自动回复」字段，数据库无此行时注入占位项，保存时自动新增
      const wxLoginGroup = list.find((g) => g.category === 'wxLogin');
      if (wxLoginGroup && !wxLoginGroup.items.some((it) => it.configName === 'wxLoginSubscribeReply')) {
        wxLoginGroup.items.push({
          id: -103,
          category: 'wxLogin',
          configName: 'wxLoginSubscribeReply',
          configValue: '',
          configDict: '关注后自动回复内容',
          _original: '',
          _modified: false
        } as ConfigItem);
      }
      // admin_brand：数据库无行时也注入占位分组，保证配置中心始终可维护品牌图片
      if (!list.some((g) => g.category === ADMIN_BRAND_CATEGORY)) {
        list.push({
          category: ADMIN_BRAND_CATEGORY,
          items: [
            {
              id: -201,
              category: ADMIN_BRAND_CATEGORY,
              configName: 'platform_logo_url',
              configValue: '',
              configDict: '平台LOGO地址',
              _original: '',
              _modified: false
            } as ConfigItem
          ]
        });
        list.sort((a, b) => a.category.localeCompare(b.category, 'zh-CN'));
      }
      setGroups(list);
      setActiveCategory((prev) => prev || list[0]?.category || '');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadList();
  }, []);

  // 加载所有模型（给 agent_model 字段下拉用）
  useEffect(() => {
    listAidmodel({ pageNum: 1, pageSize: 1000 })
      .then((r: any) => setModels(r.rows || r.data || []))
      .catch(() => setModels([]));
  }, []);

  const loadCurrentActive = async (category: string) => {
    if (!SYNCABLE_CATEGORIES.includes(category)) {
      setCurrentActiveConfig({});
      return;
    }
    try {
      const res: any = await getCurrentConfig(category);
      setCurrentActiveConfig(res.data || {});
    } catch {
      setCurrentActiveConfig({});
    }
  };

  useEffect(() => {
    if (activeCategory) loadCurrentActive(activeCategory);
  }, [activeCategory]);

  const currentGroup = useMemo(() => groups.find((g) => g.category === activeCategory), [groups, activeCategory]);

  /**
   * 当前是否处于图片审查分类：是则用专用 `ImageModerationSection` 渲染右侧内容，
   * 同时隐藏对该分类无意义的「保存/同步/通用测试」头部按钮（区块内置自己的保存/测试按钮）。
   */
  const isImageModeration = activeCategory === IMAGE_MODERATION_CATEGORY;
  const isMediaProcess = activeCategory === MEDIA_PROCESS_CATEGORY;
  const isTencentAsr = activeCategory === TENCENT_ASR_CATEGORY;
  const isAdminEntry = activeCategory === ADMIN_ENTRY_CATEGORY;
  const isDefaultAvatar = activeCategory === DEFAULT_AVATAR_CATEGORY;
  const isAdminBrand = activeCategory === ADMIN_BRAND_CATEGORY;
  const isWechatNotify = activeCategory === WECHAT_NOTIFY_CATEGORY;
  const isBasic = activeCategory === BASIC_CATEGORY;
  /** 走专用区块（自带保存/操作）的分类：隐藏通用的保存/同步/测试/刷新头部按钮 */
  const isSpecialSection =
    isImageModeration ||
    isMediaProcess ||
    isTencentAsr ||
    isAdminEntry ||
    isDefaultAvatar ||
    isAdminBrand ||
    isWechatNotify;

  const filteredItems = useMemo(() => {
    if (!currentGroup) return [];
    const rule = PROVIDER_SPECIFIC_FIELDS[activeCategory];
    const driver = PROVIDER_FILTER_DRIVER[activeCategory];
    if (!rule || !driver) return currentGroup.items;
    // 驱动字段值（sms=providerType / oss=uploadMode），决定显示哪一套服务商/模式字段
    const driverItem = currentGroup.items.find((it) => it.configName === driver.field);
    const driverValue = driverItem?.configValue || driver.default;
    const allowed = [...rule.common, ...((rule as any)[driverValue] || [])];
    // 按 allowed 顺序输出：公共字段在上、模式字段在下
    const byName = new Map(currentGroup.items.map((it) => [it.configName, it]));
    return allowed.map((n) => byName.get(n)).filter(Boolean) as ConfigItem[];
  }, [currentGroup, activeCategory]);

  /** 当前短信厂商，用于展示厂商专属提示。 */
  const currentSmsProvider =
    activeCategory === 'sms'
      ? currentGroup?.items.find((item) => item.configName === 'providerType')?.configValue || 'aliyun'
      : '';

  /** 当前存储模式，用于展示不易误配的域名示例。 */
  const currentStorageMode =
    activeCategory === 'oss'
      ? currentGroup?.items.find((item) => item.configName === 'uploadMode')?.configValue || 'oss'
      : '';

  // 整组项（含被隐藏的镜像字段，如 cdnDomain/cosCdnDomain），用于"已修改"统计与保存
  const groupItems = currentGroup?.items || [];
  const modifiedCount = groupItems.filter((it) => it._modified).length;
  const hasModified = modifiedCount > 0;

  /** 当前分类是否支持连通性测试 */
  const connectivityTestKey = CONFIG_TEST_KEY[activeCategory];

  /** 用当前（未保存）的表单值构建测试 payload，并按需重映射字段名 */
  const buildTestPayload = (): Record<string, any> => {
    const remap = CONFIG_TEST_FIELD_REMAP[activeCategory] || {};
    const payload: Record<string, any> = {};
    filteredItems.forEach((it) => {
      const key = remap[it.configName] || it.configName;
      payload[key] = it.configValue;
    });
    return payload;
  };

  const handleChange = (id: number, value: string) => {
    setGroups((prev) =>
      prev.map((g) => ({
        ...g,
        items: g.items.map((it) =>
          it.id === id ? { ...it, configValue: value, _modified: value !== it._original } : it
        )
      }))
    );
  };

  const resetCurrentGroup = () => {
    setGroups((prev) =>
      prev.map((g) =>
        g.category !== activeCategory
          ? g
          : { ...g, items: g.items.map((it) => ({ ...it, configValue: it._original, _modified: false })) }
      )
    );
  };

  const saveCurrentGroup = async () => {
    // 从整组取已修改项保存（含被模式过滤隐藏但已改动的项，保持数据一致）
    const dirty = groupItems.filter((it) => it._modified);
    if (dirty.length === 0) return;
    setSaving(true);
    try {
      if (activeCategory === 'oss') {
        const payload = Object.fromEntries(groupItems.map((item) => [item.configName, item.configValue]));
        await saveStorageConfig(payload);
        message.success('保存成功，存储归属已校验');
        await loadList();
        return;
      }
      // id>0 走更新；id<=0（占位项，如未初始化的 uploadTypeLimits）走新增
      const results = await Promise.allSettled(
        dirty.map((it) =>
          it.id && it.id > 0
            ? updateAidconfig({ id: it.id, configValue: it.configValue })
            : addAidconfig({
                category: it.category,
                configName: it.configName,
                configValue: it.configValue,
                configDict: it.configDict,
                delFlag: '0',
                orderNum: it.orderNum || 7
              })
        )
      );
      const ok = results.filter((r) => r.status === 'fulfilled').length;
      // 有新增项成功时重新拉取列表，拿到真实主键 id，避免后续重复新增
      const hasCreated = dirty.some((it) => !(it.id && it.id > 0));
      if (hasCreated) {
        if (ok === dirty.length) {
          message.success(
            SYNCABLE_CATEGORIES.includes(activeCategory)
              ? '保存成功，请点击"同步配置"使配置生效'
              : '保存成功，配置将在短暂缓存后生效'
          );
        } else {
          message.warning(`成功保存 ${ok}/${dirty.length} 项`);
        }
        await loadList();
        return;
      }
      setGroups((prev) =>
        prev.map((g) => ({
          ...g,
          items: g.items.map((it) => {
            const idx = dirty.findIndex((d) => d.id === it.id);
            if (idx === -1) return it;
            if (results[idx].status === 'fulfilled') {
              return { ...it, _original: it.configValue, _modified: false };
            }
            return it;
          })
        }))
      );
      if (ok === dirty.length) {
        message.success(
          SYNCABLE_CATEGORIES.includes(activeCategory)
            ? '保存成功，请点击"同步配置"使配置生效'
            : '保存成功，配置将在短暂缓存后生效'
        );
      } else {
        message.warning(`成功保存 ${ok}/${dirty.length} 项`);
      }
    } finally {
      setSaving(false);
    }
  };

  const syncConfig = async () => {
    if (!SYNCABLE_CATEGORIES.includes(activeCategory)) {
      message.warning('该分类暂不支持同步配置');
      return;
    }
    setSyncing(true);
    try {
      await refreshConfig(activeCategory);
      message.success('配置已同步到内存');
      await loadCurrentActive(activeCategory);
    } catch {
      message.error('同步配置失败');
    } finally {
      setSyncing(false);
    }
  };

  /** 是否支持"测试"按钮：mq 直接测试、sms 需要输入手机号 */
  const canTest = activeCategory === 'mq' || activeCategory === 'sms';

  const handleTest = async () => {
    if (activeCategory === 'mq') {
      setTesting(true);
      try {
        const res: any = await testMqSend();
        message.success(res.msg || '消息队列连接正常');
      } catch (e: any) {
        message.error(e?.message || '消息队列测试失败');
      } finally {
        setTesting(false);
      }
    } else if (activeCategory === 'sms') {
      setSmsTestOpen(true);
      smsTestForm.resetFields();
    }
  };

  const submitSmsTest = async () => {
    const values = await smsTestForm.validateFields();
    setTesting(true);
    try {
      const res: any = await testSmsSend({
        phone: values.phone,
        code: values.code?.trim() || undefined
      });
      if (res.code === 200 || res.success) {
        message.info(res.msg || '短信请求已受理，请核对送达');
        setSmsTestOpen(false);
      } else {
        message.error(res.msg || '测试短信发送失败');
      }
    } finally {
      setTesting(false);
    }
  };

  /** taskq：为指定用户写入专属并发上限 taskq_concurrent_limit_user_{userId} */
  const submitUserLimit = async () => {
    const values = await userLimitForm.validateFields();
    const userId = String(values.userId).trim();
    const configName = `taskq_concurrent_limit_user_${userId}`;
    // 已存在则走更新，否则新增
    const taskqGroup = groups.find((g) => g.category === 'taskq');
    const existing = taskqGroup?.items.find((it) => it.configName === configName);
    setUserLimitSaving(true);
    try {
      if (existing) {
        await updateAidconfig({ id: existing.id, configValue: String(values.limit) });
        message.success(`已更新用户 ${userId} 的专属并发上限`);
      } else {
        await addAidconfig({
          category: 'taskq',
          configName,
          configValue: String(values.limit),
          configDict: `用户${userId}专属并发上限`,
          delFlag: '0',
          orderNum: 10
        });
        message.success(`已为用户 ${userId} 设置专属并发上限`);
      }
      setUserLimitOpen(false);
      await loadList();
    } finally {
      setUserLimitSaving(false);
    }
  };

  /** 一键生成 RSA 密钥对（前端 Web Crypto API，2048位） */
  const generateRsaKeyPair = async () => {
    try {
      const keyPair = await window.crypto.subtle.generateKey(
        { name: 'RSA-OAEP', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
        true,
        ['encrypt', 'decrypt']
      );
      // 导出公钥（SPKI / X.509 格式）
      const pubBuf = await window.crypto.subtle.exportKey('spki', keyPair.publicKey);
      const pubBase64 = btoa(String.fromCharCode(...new Uint8Array(pubBuf)));
      // 导出私钥（PKCS8 格式）
      const privBuf = await window.crypto.subtle.exportKey('pkcs8', keyPair.privateKey);
      const privBase64 = btoa(String.fromCharCode(...new Uint8Array(privBuf)));

      // 找到 public_key 和 private_key 配置项并填入
      setGroups((prev) =>
        prev.map((g) => {
          if (g.category !== 'api_crypto') return g;
          return {
            ...g,
            items: g.items.map((it) => {
              if (it.configName === 'public_key') {
                return { ...it, configValue: pubBase64, _modified: pubBase64 !== it._original };
              }
              if (it.configName === 'private_key') {
                return { ...it, configValue: privBase64, _modified: privBase64 !== it._original };
              }
              return it;
            })
          };
        })
      );
      message.success('密钥对已生成，请保存配置');
    } catch (e: any) {
      message.error('生成密钥对失败：' + (e?.message || '浏览器不支持'));
    }
  };

  return (
    <div className="config-page">
      {/* 左侧分类菜单 */}
      <div className="config-page__sidebar">
        <div className="config-page__sidebar-header">
          <div className="icon-box">
            <SettingOutlined />
          </div>
          <span className="title">系统配置</span>
        </div>
        <div className="config-page__sidebar-menu">
          {sections.map((s) => {
            const count = s.categories.reduce(
              (n, c) => n + (groups.find((g) => g.category === c)?.items.length || 0),
              0
            );
            return (
              <div
                key={s.key}
                className={`config-page__menu-item ${activeSection?.key === s.key ? 'active' : ''}`}
                onClick={() => setActiveCategory(s.categories[0])}
              >
                <FolderOpenOutlined />
                <span className="text">{s.name}</span>
                <span className="count">{count}</span>
              </div>
            );
          })}
        </div>
      </div>

      {/* 右侧内容 */}
      <div className="config-page__main">
        {/* 顶部操作栏：标题 + 保存/同步/刷新 */}
        <div className="config-page__header">
          <div className="config-page__header-left">
            <h2 className="title">{activeSection ? activeSection.name : '系统配置'}</h2>
            {currentGroup && !isSpecialSection && <span className="desc">共 {filteredItems.length} 项配置</span>}
          </div>
          <div className="config-page__header-right">
            {!isSpecialSection && hasModified && (
              <>
                <Button icon={<UndoOutlined />} onClick={resetCurrentGroup}>
                  取消修改
                </Button>
                <Button
                  type="primary"
                  icon={<SaveOutlined />}
                  loading={saving}
                  onClick={saveCurrentGroup}
                  className="save-btn"
                >
                  保存配置 ({modifiedCount})
                </Button>
              </>
            )}
            {!isSpecialSection && SYNCABLE_CATEGORIES.includes(activeCategory) && (
              <Button icon={<SyncOutlined />} loading={syncing} onClick={syncConfig}>
                同步配置
              </Button>
            )}
            {!isSpecialSection && canTest && (
              <Button
                type="primary"
                ghost
                icon={<ExperimentOutlined />}
                loading={testing}
                onClick={handleTest}
              >
                测试
              </Button>
            )}
            {!isSpecialSection && connectivityTestKey && (
              <TestConnectionButton testKey={connectivityTestKey} getPayload={buildTestPayload} />
            )}
            {activeCategory === 'api_crypto' && (
              <Button type="primary" ghost icon={<KeyOutlined />} onClick={generateRsaKeyPair}>
                一键生成密钥对
              </Button>
            )}
            {activeCategory === 'taskq' && (
              <Button
                type="primary"
                ghost
                icon={<UsergroupAddOutlined />}
                onClick={() => {
                  userLimitForm.resetFields();
                  setUserLimitOpen(true);
                }}
              >
                按用户设并发
              </Button>
            )}
            {!isSpecialSection && (
              <Button icon={<ReloadOutlined />} loading={loading} onClick={loadList}>
                刷新
              </Button>
            )}
          </div>
        </div>

        {/* 同一分区下多个配置分类：右侧顶部 Tab 切换（如 支付管理 → 支付宝 / 微信支付） */}
        {activeSection && activeSection.categories.length > 1 && (
          <div className="config-page__tabs">
            {activeSection.categories.map((c) => (
              <div
                key={c}
                className={`config-page__tab ${c === activeCategory ? 'active' : ''}`}
                onClick={() => setActiveCategory(c)}
              >
                {getCategoryName(c)}
              </div>
            ))}
          </div>
        )}

        {/* 修改提示条 */}
        {!isSpecialSection && hasModified && (
          <div className="config-page__notice">
            <WarningOutlined />
            <span>
              您有 <strong>{modifiedCount}</strong> 项配置已修改未保存
            </span>
          </div>
        )}

        <div className="config-page__content">
          {activeCategory === 'sms' && currentSmsProvider === 'smsbao' && (
            <div className="config-page__provider-note">
              <div>
                <strong>短信宝 · 轻量 HTTP 短信通道</strong>
                <span>
                  接口返回 0 仅表示短信宝已受理，并不代表手机已送达。首次使用请先在短信宝报备 VIP
                  模板；不要向同一手机号连续发送完全相同的测试内容。
                </span>
              </div>
              <a href="https://www.smsbao.com" target="_blank" rel="noreferrer">
                <LinkOutlined /> smsbao.com
              </a>
            </div>
          )}
          {activeCategory === 'oss' && currentStorageMode === 'local' && (
            <div className="config-page__provider-note">
              <div>
                <strong>本地存储配置示例</strong>
                <span>
                  资源访问地址必须填写协议和域名，例如 https://api.example.com，不要添加
                  /profile 或其他路径。该地址只用于系统正常展示资源。
                </span>
              </div>
            </div>
          )}
          {activeCategory === 'oss' && currentStorageMode && currentStorageMode !== 'local' && (
            <div className="config-page__provider-note">
              <div>
                <strong>
                  {currentStorageMode === 'qiniu'
                    ? '七牛云 Kodo'
                    : currentStorageMode === 'cos'
                      ? '腾讯云 COS'
                      : '阿里云 OSS'}{' '}
                  配置提示
                </strong>
                <span>
                  资源访问地址必须是 Bucket 绑定的下载域名或 CDN 域名，只填写协议和域名，
                  例如 https://cdn.example.com。页面展示继续使用该地址；提交外部大模型时，
                  系统才按“模型临时链接有效期”动态生成签名 URL。
                  {currentStorageMode === 'oss' && ' OSS Endpoint 可填写阿里云官方公网或内网地址；使用内网地址时，模型签名会自动改用同地域公网 Endpoint。'}
                  {' '}路径前缀可留空；存储方式、Endpoint、地域或访问凭证变更后请重启服务，使存储客户端使用新配置。
                </span>
              </div>
            </div>
          )}
          {isImageModeration ? (
            <ImageModerationSection />
          ) : isMediaProcess ? (
            <MediaProcessSection />
          ) : isTencentAsr ? (
            <TencentAsrSection />
          ) : isAdminEntry ? (
            <AdminEntrySection />
          ) : isDefaultAvatar ? (
            <DefaultAvatarSection />
          ) : isAdminBrand ? (
            <AdminBrandSection />
          ) : isWechatNotify ? (
            <WechatNotifySection
              onJumpToWxLogin={() => {
                if (groups.some((g) => g.category === 'wxLogin')) {
                  setActiveCategory('wxLogin');
                  return;
                }
                message.warning('当前配置列表中没有微信公众号配置');
              }}
            />
          ) : loading ? (
            <div className="config-page__center">
              <Spin size="large" />
            </div>
          ) : !currentGroup ? (
            <div className="config-page__empty">
              <FolderOpenOutlined />
              <div>请选择左侧配置分类</div>
            </div>
          ) : filteredItems.length === 0 ? (
            <div className="config-page__center">
              <Empty description="当前分类无可配置项" />
            </div>
          ) : isBasic ? (
            <BasicConfigSection items={filteredItems} onChange={handleChange} />
          ) : (
            <div className="config-page__form">
              {filteredItems.map((item) => {
                const current = currentActiveConfig[item.configName];
                // 分类型上传限制：表单内已展示生效值，无需再显示裸 JSON 的「生效」指示
                const isUploadTypeLimits = item.category === 'oss' && item.configName === 'uploadTypeLimits';
                // 分镜镜头数下限锚点：卡片式编辑器（说明+参数+预览），走整行块级布局
                const isShotDensityFloor = item.category === 'storyboard' && item.configName === 'shot_density_floor';
                const isBlockRow = item.category === 'project_gen_config' || isUploadTypeLimits || isShotDensityFloor;
                return (
                  <div
                    key={item.id}
                    className={`config-page__row ${item._modified ? 'modified' : ''} ${
                      isBlockRow ? 'config-page__row--block' : ''
                    }`}
                  >
                    <div className="config-page__label">{formatConfigLabel(item)}</div>
                    <div className="config-page__value">
                      <ValueField
                        name={item.configName}
                        value={item.configValue ?? ''}
                        onChange={(v) => handleChange(item.id, v)}
                        models={models}
                        category={item.category}
                      />
                    </div>
                    {current !== undefined && !isUploadTypeLimits && (
                      <div className="config-page__current" title={String(current)}>
                        <CheckOutlined />
                        <span className="label">生效:</span>
                        <span className="value">{formatCurrentValue(item.configName, current)}</span>
                      </div>
                    )}
                    {item._modified && (
                      <Tag color="orange" bordered={false} style={{ marginLeft: 8 }}>
                        已修改
                      </Tag>
                    )}
                    {isRestartRequired(item.category, item.configName) && (
                      <Tag color="blue" bordered={false} style={{ marginLeft: 8 }}>
                        修改后需重启生效
                      </Tag>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>

      {/* 短信测试弹窗 */}
      <Modal
        open={smsTestOpen}
        title="测试发送短信"
        onCancel={() => setSmsTestOpen(false)}
        onOk={submitSmsTest}
        confirmLoading={testing}
        okText="发送"
        destroyOnClose
      >
        <div className="help-text" style={{ marginBottom: 12 }}>
          将使用当前已同步的短信渠道向目标手机号发送测试验证码；短信宝返回“已受理”不等于运营商已送达。
        </div>
        <Form form={smsTestForm} layout="vertical">
          <Form.Item
            name="phone"
            label="手机号"
            rules={[
              { required: true, message: '请输入手机号' },
              { pattern: /^1\d{10}$/, message: '格式不正确' }
            ]}
          >
            <Input placeholder="接收测试短信的手机号" />
          </Form.Item>
          <Form.Item name="code" label="验证码" extra="可选，留空自动生成随机 6 位数字，避免固定测试内容被拦截">
            <Input placeholder="4-6 位数字" maxLength={6} />
          </Form.Item>
        </Form>
      </Modal>

      {/* taskq：按用户设并发上限 */}
      <Modal
        open={userLimitOpen}
        title="按用户设置专属并发上限"
        onCancel={() => setUserLimitOpen(false)}
        onOk={submitUserLimit}
        confirmLoading={userLimitSaving}
        okText="保存"
        destroyOnClose
      >
        <div className="help-text" style={{ marginBottom: 12 }}>
          为指定用户单独设置并发上限（VIP 等场景），写入配置项 <code>taskq_concurrent_limit_user_{'{userId}'}</code>
          。未单独设置的用户回退到"单用户默认并发上限"。
        </div>
        <Form form={userLimitForm} layout="vertical">
          <Form.Item
            name="userId"
            label="用户ID"
            rules={[
              { required: true, message: '请输入用户ID' },
              { pattern: /^\d+$/, message: '用户ID必须为数字' }
            ]}
          >
            <Input placeholder="目标用户的ID" />
          </Form.Item>
          <Form.Item name="limit" label="并发上限" rules={[{ required: true, message: '请输入并发上限' }]}>
            <InputNumber min={1} max={1000} style={{ width: '100%' }} placeholder="正整数，如 3" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
