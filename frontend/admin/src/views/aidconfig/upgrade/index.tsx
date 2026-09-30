import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Col,
  Collapse,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Progress,
  Radio,
  Row,
  Select,
  Space,
  Spin,
  Switch,
  Tabs,
  Tag,
  Typography,
  Upload,
  message
} from 'antd';
import {
  CheckCircleOutlined,
  CloseOutlined,
  CloudDownloadOutlined,
  CloudServerOutlined,
  CodeOutlined,
  ControlOutlined,
  DatabaseOutlined,
  DownOutlined,
  DownloadOutlined,
  ExclamationCircleOutlined,
  FileTextOutlined,
  HistoryOutlined,
  InboxOutlined,
  ReloadOutlined,
  RocketOutlined,
  SafetyCertificateOutlined,
  SaveOutlined,
  SettingOutlined,
  SyncOutlined,
  StopOutlined,
  UpOutlined,
  UploadOutlined
} from '@ant-design/icons';

import {
  applyDeploymentConfig,
  cancelUpgrade,
  DeploymentConfig,
  DeploymentCheck,
  DeploymentConfigSaveParams,
  getDeploymentConfig,
  getOfficialAssetsStatus,
  getUpdaterLogs,
  getUpgradeSource,
  rollbackDeploymentConfig,
  rollbackSystem,
  saveUpgradeSource,
  startUpdaterUpgrade,
  startUpgrade,
  installOfficialAssets,
  installHttpsCertificate,
  testDeploymentConfig,
  validateDeploymentConfig,
  UpdaterLog,
  OfficialAssetsStatus,
  UpgradeSourceSetting
} from '@/api/aidconfig/upgrade';
import { useUpgradeStore } from '@/store/useUpgradeStore';
import PageHeader from '@/components/PageHeader';
import StatCard from '@/components/StatCard';
import './style.less';
import NginxPanel from './NginxPanel';

const { Paragraph, Text } = Typography;

const formatBytes = (value?: number): string => {
  const bytes = Number(value || 0);
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MiB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GiB`;
};

const UPDATER_TAG: Record<string, { color: string; text: string }> = {
  AVAILABLE: { color: 'green', text: '运行正常' },
  NOT_INSTALLED: { color: 'red', text: '未安装' },
  STOPPED: { color: 'orange', text: '已停止' },
  INCOMPATIBLE: { color: 'volcano', text: '版本不兼容' },
  UNKNOWN: { color: 'default', text: '状态异常' }
};

const TASK_ACTION_TEXT: Record<string, string> = {
  UPGRADE: '系统升级',
  UPDATER_UPGRADE: '升级器升级',
  ROLLBACK: '版本回退',
  CONFIG_VALIDATE: '配置校验',
  CONFIG_TEST: '配置诊断',
  CONFIG_APPLY: '配置应用',
  CONFIG_ROLLBACK: '配置恢复',
  CERT_INSTALL: '证书安装'
};

const VERSION_TASK_ACTIONS = new Set(['UPGRADE', 'UPDATER_UPGRADE', 'ROLLBACK']);
const CONFIG_RELOAD_ACTIONS = new Set(['CERT_INSTALL', 'CONFIG_APPLY', 'CONFIG_ROLLBACK', 'NGINX_APPLY', 'NGINX_ROLLBACK']);
const HOSTNAME_PATTERN = /^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)*[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$/;
const portRules = (required: boolean) => [
  { required, message: '请输入端口' },
  {
    validator: (_: unknown, value?: string) => {
      if (value === undefined || value === null || String(value).trim() === '') return Promise.resolve();
      const port = Number(value);
      return Number.isInteger(port) && port >= 1 && port <= 65535
        ? Promise.resolve()
        : Promise.reject(new Error('端口范围必须为 1-65535'));
    }
  }
];

const deploymentToForm = (config: DeploymentConfig): DeploymentConfigSaveParams => {
  const value = config.values || {};
  return {
    configPath: config.configPath,
    httpPort: value.HTTP_PORT,
    adminPort: value.ADMIN_PORT,
    backendPort: value.BACKEND_PORT,
    dataRoot: value.DATA_ROOT || config.allowedConfigRoot.replace(/[\\/]config[\\/]?$/, ''),
    mysqlPort: value.MYSQL_PORT,
    dbHost: value.DB_HOST,
    dbPort: value.DB_PORT,
    dbName: value.DB_NAME,
    dbUsername: value.DB_USERNAME,
    redisHost: value.REDIS_HOST,
    redisPort: value.REDIS_PORT,
    redisUsername: value.REDIS_USERNAME,
    redisDatabase: value.REDIS_DATABASE,
    redisPassword: undefined,
    clearRedisPassword: false,
    javaOpts: value.JAVA_OPTS,
    dependencyInstallMode: value.DEPENDENCY_INSTALL_MODE || 'auto',
    dependencyRegion: value.DEPENDENCY_REGION || 'auto',
    dockerMirrors: config.mode === 'docker' ? value.DOCKER_MIRRORS || 'docker.m.daocloud.io,dockerproxy.net' : undefined,
    composeProfiles: value.COMPOSE_PROFILES,
    rocketmqEnabled: value.ROCKETMQ_ENABLED,
    rocketmqNameserver: value.ROCKETMQ_NAMESERVER,
    rocketmqFlushDiskType: value.ROCKETMQ_FLUSH_DISK_TYPE || 'ASYNC_FLUSH',
    rocketmqAccessKey: undefined,
    rocketmqSecretKey: undefined,
    clearRocketmqCredentials: false,
    httpsEnabled: value.HTTPS_ENABLED || 'false',
    httpsPort: value.HTTPS_PORT,
    httpsPublicDomain: value.HTTPS_PUBLIC_DOMAIN,
    httpsAdminDomain: value.HTTPS_ADMIN_DOMAIN,
    httpsCertPath: value.HTTPS_CERT_PATH,
    httpsKeyPath: value.HTTPS_KEY_PATH,
    mysqlBufferPool: value.MYSQL_BUFFER_POOL,
    mysqlMaxConnections: value.MYSQL_MAX_CONNECTIONS,
    redisMaxmemory: value.REDIS_MAXMEMORY,
    redisMaxmemoryPolicy: value.REDIS_MAXMEMORY_POLICY,
    webNodeOptions: value.WEB_NODE_OPTIONS,
    mqNamesrvJavaOpts: value.MQ_NAMESRV_JAVA_OPTS,
    mqBrokerJavaOpts: value.MQ_BROKER_JAVA_OPTS
  };
};

const TASK_STATE_META: Record<string, { alert: 'info' | 'success' | 'error'; text: string }> = {
  RUNNING: { alert: 'info', text: '执行中' },
  SUCCESS: { alert: 'success', text: '成功' },
  FAILED: { alert: 'error', text: '失败' },
  CANCELLED: { alert: 'info', text: '已取消' }
};

const taskKey = (task?: { taskId?: string; action?: string; state?: string; finishedAt?: string }) =>
  [task?.taskId, task?.action, task?.state, task?.finishedAt].filter(Boolean).join('|');

const renderInlineReleaseText = (text: string, keyPrefix: string): React.ReactNode[] => {
  const parts = text.split(/(`[^`\n]+`|\*\*[^*\n]+\*\*)/g).filter(Boolean);
  return parts.map((part, index) => {
    const key = `${keyPrefix}-${index}`;
    if (part.startsWith('`') && part.endsWith('`')) {
      return (
        <Text code key={key}>
          {part.slice(1, -1)}
        </Text>
      );
    }
    if (part.startsWith('**') && part.endsWith('**')) {
      return (
        <Text strong key={key}>
          {part.slice(2, -2)}
        </Text>
      );
    }
    return <React.Fragment key={key}>{part}</React.Fragment>;
  });
};

const renderReleaseNotes = (markdown: string): React.ReactNode[] => {
  const lines = markdown.replace(/\r\n?/g, '\n').split('\n');
  const nodes: React.ReactNode[] = [];
  let index = 0;

  while (index < lines.length) {
    const line = lines[index].trim();
    if (!line) {
      index += 1;
      continue;
    }

    const heading = line.match(/^(#{1,3})\s+(.+)$/);
    if (heading) {
      const level = heading[1].length;
      const HeadingTag = `h${Math.min(level + 2, 6)}` as keyof React.JSX.IntrinsicElements;
      nodes.push(
        <HeadingTag
          className={`upgrade-page__release-heading upgrade-page__release-heading--${level}`}
          key={`heading-${index}`}
        >
          {renderInlineReleaseText(heading[2], `heading-${index}`)}
        </HeadingTag>
      );
      index += 1;
      continue;
    }

    if (/^[-*]\s+/.test(line)) {
      const items: React.ReactNode[] = [];
      while (index < lines.length) {
        const item = lines[index].trim().match(/^[-*]\s+(.+)$/);
        if (!item) break;
        items.push(<li key={`item-${index}`}>{renderInlineReleaseText(item[1], `item-${index}`)}</li>);
        index += 1;
      }
      nodes.push(
        <ul className="upgrade-page__release-list" key={`list-${index}`}>
          {items}
        </ul>
      );
      continue;
    }

    const paragraphLines: string[] = [];
    while (index < lines.length) {
      const paragraphLine = lines[index].trim();
      if (!paragraphLine || /^(#{1,3})\s+/.test(paragraphLine) || /^[-*]\s+/.test(paragraphLine)) break;
      paragraphLines.push(paragraphLine.replace(/^>\s?/, ''));
      index += 1;
    }
    nodes.push(
      <Paragraph className="upgrade-page__release-paragraph" key={`paragraph-${index}`}>
        {renderInlineReleaseText(paragraphLines.join(' '), `paragraph-${index}`)}
      </Paragraph>
    );
  }

  return nodes;
};

export default function UpgradeConfigPage() {
  const status = useUpgradeStore((state) => state.status);
  const loading = useUpgradeStore((state) => state.loading);
  const checking = useUpgradeStore((state) => state.checking);
  const loadStatusShared = useUpgradeStore((state) => state.loadStatus);

  const [installOpen, setInstallOpen] = useState(false);
  const [updaterLogs, setUpdaterLogs] = useState<UpdaterLog | null>(null);
  const [logsLoading, setLogsLoading] = useState(false);
  const [sourceLoading, setSourceLoading] = useState(true);
  const [sourceLoadError, setSourceLoadError] = useState(false);
  const [sourceSaving, setSourceSaving] = useState(false);
  const [sourceDirty, setSourceDirty] = useState(false);
  const [deploymentConfig, setDeploymentConfig] = useState<DeploymentConfig | null>(null);
  const [deploymentLoading, setDeploymentLoading] = useState(true);
  const [deploymentSaving, setDeploymentSaving] = useState(false);
  const [deploymentDirty, setDeploymentDirty] = useState(false);
  const [deploymentTestTarget, setDeploymentTestTarget] = useState<string>();
  const [deploymentCheckResults, setDeploymentCheckResults] = useState<Record<string, DeploymentCheck>>({});
  const [certificateFile, setCertificateFile] = useState<File | null>(null);
  const [privateKeyFile, setPrivateKeyFile] = useState<File | null>(null);
  const [certificateUploading, setCertificateUploading] = useState(false);
  const [certificateUploadProgress, setCertificateUploadProgress] = useState(0);
  const [assetsStatus, setAssetsStatus] = useState<OfficialAssetsStatus | null>(null);
  const [assetsLoading, setAssetsLoading] = useState(true);
  const [assetsInstalling, setAssetsInstalling] = useState(false);
  const [assetsUploadProgress, setAssetsUploadProgress] = useState(0);
  const [assetsFile, setAssetsFile] = useState<File | null>(null);
  const [rollbackVersion, setRollbackVersion] = useState<string>();
  const [rollbackConfirmOpen, setRollbackConfirmOpen] = useState(false);
  const [rollbackConfirmText, setRollbackConfirmText] = useState('');
  const [rollbackSubmitting, setRollbackSubmitting] = useState(false);
  const [taskPolling, setTaskPolling] = useState(false);
  const [cancelSubmitting, setCancelSubmitting] = useState(false);
  const [cancelRequestedTaskId, setCancelRequestedTaskId] = useState<string>();
  const [pendingTaskAction, setPendingTaskAction] = useState<string>();
  const [dismissedTaskId, setDismissedTaskId] = useState<string>();
  const [releaseNotesExpanded, setReleaseNotesExpanded] = useState(false);
  const pollingBaseline = useRef('');
  const pollingTaskSeen = useRef(false);
  const terminalRef = useRef<HTMLPreElement>(null);
  const releaseNotesCardRef = useRef<HTMLDivElement>(null);
  const [sourceForm] = Form.useForm<UpgradeSourceSetting>();
  const [deploymentForm] = Form.useForm<DeploymentConfigSaveParams>();
  const composeProfiles = Form.useWatch('composeProfiles', deploymentForm) || '';
  const manualHttpsEnabled = Form.useWatch('httpsEnabled', deploymentForm) === 'true';
  const databaseHost = (Form.useWatch('dbHost', deploymentForm) || '').trim().toLowerCase();
  const rocketmqEnabled = Form.useWatch('rocketmqEnabled', deploymentForm) === 'true';
  const rocketmqNameserver = Form.useWatch('rocketmqNameserver', deploymentForm) || '';
  const usesInternalMysql =
    deploymentConfig?.mode === 'docker' && composeProfiles.split(',').some((item) => item.trim() === 'mysql');
  const usesInternalRocketmq =
    deploymentConfig?.mode === 'docker' && composeProfiles.split(',').some((item) => item.trim() === 'mq');
  const usesInternalRedis =
    deploymentConfig?.mode === 'docker' && composeProfiles.split(',').some((item) => item.trim() === 'redis');
  const usesLocalManualMysql =
    deploymentConfig?.mode === 'systemd' && ['127.0.0.1', 'localhost', '::1'].includes(databaseHost);
  const httpsEnabled =
    deploymentConfig?.mode === 'docker'
      ? composeProfiles.split(',').some((item) => item.trim() === 'https')
      : manualHttpsEnabled;

  const updater = status?.updater;
  const configProtocolReady = updater?.ready === true && (updater.protocolVersion || 0) >= 3;
  const configProtocolIncompatible = updater?.status === 'INCOMPATIBLE' || Boolean(updater && (updater.protocolVersion || 0) < 3);
  const updaterTag = UPDATER_TAG[updater?.status || 'UNKNOWN'] || UPDATER_TAG.UNKNOWN;
  const rollbackCount = status?.rollbackReleases?.length || 0;
  const lastTask = updater?.lastTask;
  const lastTaskMeta = lastTask?.state ? TASK_STATE_META[lastTask.state] : undefined;
  const taskRunning = lastTask?.state === 'RUNNING';
  const canCancelVersionTask = Boolean(
    taskRunning && lastTask?.cancellable && lastTask?.action && VERSION_TASK_ACTIONS.has(lastTask.action)
  );
  const cancelRequestPending = Boolean(
    cancelSubmitting || lastTask?.cancelRequested || (lastTask?.taskId && cancelRequestedTaskId === lastTask.taskId)
  );
  const taskBusy = taskRunning || taskPolling;
  const progressAction = pendingTaskAction || lastTask?.action;
  const isVersionTask = Boolean(progressAction && VERSION_TASK_ACTIONS.has(progressAction));
  const awaitingVersionTask = Boolean(pendingTaskAction && taskPolling && !taskRunning && !pollingTaskSeen.current);
  const progressTaskId = awaitingVersionTask
    ? `${pendingTaskAction}-pending`
    : lastTask?.taskId || pendingTaskAction || 'pending';
  // 历史完成任务不在每次进入页面时占据首屏；本次会话发起或仍在运行的版本任务才展示终端。
  const showProgressTerminal =
    isVersionTask && (taskRunning || Boolean(pendingTaskAction)) && dismissedTaskId !== progressTaskId;
  const selectedRollback = status?.rollbackReleases?.find((item) => item.version === rollbackVersion);
  const mqActive = rocketmqEnabled && (
    deploymentConfig?.mode === 'docker'
      ? usesInternalRocketmq || Boolean(rocketmqNameserver.trim())
      : Boolean(rocketmqNameserver.trim())
  );
  const deploymentChecks = deploymentCheckResults;

  const loadStatus = useCallback(
    async (force: boolean) => {
      const next = await loadStatusShared(force);
      if (force) {
        if (next?.checkError) {
          message.warning('检查完成，更新源当前不可用');
        } else {
          message.success('检查完成');
        }
      }
      return next;
    },
    [loadStatusShared]
  );

  const loadSource = useCallback(async () => {
    setSourceLoading(true);
    setSourceLoadError(false);
    try {
      const res = await getUpgradeSource();
      sourceForm.setFieldsValue({
        releaseChannel: 'stable',
        keepBackups: 3,
        manifestUrl: '',
        ...(res.data || {})
      });
      setSourceDirty(false);
    } catch {
      setSourceLoadError(true);
    } finally {
      setSourceLoading(false);
    }
  }, [sourceForm]);

  const loadDeployment = useCallback(async () => {
    setDeploymentLoading(true);
    try {
      const res = await getDeploymentConfig();
      const next = res.data || null;
      setDeploymentConfig(next);
      if (next) deploymentForm.setFieldsValue(deploymentToForm(next));
      setDeploymentDirty(false);
    } catch {
      setDeploymentConfig(null);
    } finally {
      setDeploymentLoading(false);
    }
  }, [deploymentForm]);

  const loadUpdaterLogs = useCallback(async (silent = false) => {
    if (!silent) setLogsLoading(true);
    try {
      const res = await getUpdaterLogs();
      setUpdaterLogs(res.data || null);
    } catch {
      if (!silent) setUpdaterLogs(null);
    } finally {
      if (!silent) setLogsLoading(false);
    }
  }, []);

  const loadAssetsStatus = useCallback(async () => {
    setAssetsLoading(true);
    try {
      const res = await getOfficialAssetsStatus();
      setAssetsStatus(res.data || null);
    } catch {
      setAssetsStatus(null);
    } finally {
      setAssetsLoading(false);
    }
  }, []);

  useEffect(() => {
    // The shared store may still contain the snapshot from before navigation.
    // Always read the updater's current task on entry so an in-flight upgrade
    // restores its terminal, progress and log polling after returning here.
    loadStatus(false).catch(() => undefined);
    loadSource();
    loadDeployment();
    loadAssetsStatus();
  }, [loadAssetsStatus, loadDeployment, loadSource, loadStatus]);

  useEffect(() => {
    if (installOpen) {
      loadUpdaterLogs(false);
    }
  }, [installOpen, loadUpdaterLogs]);

  useEffect(() => {
    if (!taskRunning || !lastTask?.action) return;
    setPendingTaskAction(lastTask.action);
    setDismissedTaskId(undefined);
    pollingBaseline.current = taskKey(lastTask);
    pollingTaskSeen.current = true;
    setTaskPolling(true);
  }, [lastTask?.action, lastTask?.taskId, taskRunning]);

  useEffect(() => {
    if (!showProgressTerminal) return;
    loadUpdaterLogs(true);
    if (!taskBusy) return;
    const timer = window.setInterval(() => loadUpdaterLogs(true), 1200);
    return () => window.clearInterval(timer);
  }, [loadUpdaterLogs, showProgressTerminal, taskBusy]);

  useEffect(() => {
    if (!showProgressTerminal) return;
    const terminal = terminalRef.current;
    if (terminal) terminal.scrollTop = terminal.scrollHeight;
  }, [showProgressTerminal, updaterLogs?.lines]);

  useEffect(() => {
    setReleaseNotesExpanded(false);
  }, [status?.latestVersion]);

  useEffect(() => {
    if (!taskPolling) return;

    let attempts = 0;
    let requesting = false;
    const poll = async () => {
      if (requesting) return;
      requesting = true;
      attempts += 1;
      try {
        const next = await loadStatusShared(false);
        const nextTask = next?.updater?.lastTask;
        const changed = !!nextTask && taskKey(nextTask) !== pollingBaseline.current;
        if (changed) {
          pollingTaskSeen.current = true;
        }
        if (pollingTaskSeen.current && nextTask?.state !== 'RUNNING') {
          setTaskPolling(false);
          loadUpdaterLogs(true).catch(() => undefined);
          const completedAction = nextTask?.action || pendingTaskAction;
          if (completedAction && (CONFIG_RELOAD_ACTIONS.has(completedAction) || VERSION_TASK_ACTIONS.has(completedAction))) {
            loadDeployment().catch(() => undefined);
          }
        } else if (attempts >= 2880) {
          setTaskPolling(false);
        }
      } catch {
        if (attempts >= 2880) setTaskPolling(false);
      } finally {
        requesting = false;
      }
    };

    const firstTimer = window.setTimeout(poll, 800);
    const timer = window.setInterval(poll, 2500);
    return () => {
      window.clearTimeout(firstTimer);
      window.clearInterval(timer);
    };
  }, [loadDeployment, loadStatusShared, loadUpdaterLogs, pendingTaskAction, taskPolling]);

  useEffect(() => {
    if (!taskPolling && lastTask?.action === 'CONFIG_TEST' && lastTask.state !== 'RUNNING') {
      if (lastTask.checks) {
        setDeploymentCheckResults((current) => ({ ...current, ...lastTask.checks }));
      }
      setDeploymentTestTarget(undefined);
    }
    if (!taskPolling && lastTask?.action === 'CERT_INSTALL' && lastTask.state !== 'RUNNING') {
      setCertificateUploading(false);
      setCertificateUploadProgress(lastTask.state === 'SUCCESS' ? 100 : 0);
      if (lastTask.state === 'SUCCESS') {
        setCertificateFile(null);
        setPrivateKeyFile(null);
      }
    }
  }, [lastTask?.action, lastTask?.state, taskPolling]);

  const beginTaskPolling = useCallback((action: string) => {
    pollingBaseline.current = taskKey(useUpgradeStore.getState().status?.updater?.lastTask);
    pollingTaskSeen.current = false;
    setPendingTaskAction(action);
    setDismissedTaskId(undefined);
    setTaskPolling(true);
  }, []);

  const validateHttpsActionFields = (values: DeploymentConfigSaveParams, requirePort: boolean): boolean => {
    const publicDomain = values.httpsPublicDomain?.trim() || '';
    const adminDomain = values.httpsAdminDomain?.trim() || '';
    const domainError = !publicDomain || !adminDomain
      ? '请先填写用户端和管理端 HTTPS 域名'
      : !HOSTNAME_PATTERN.test(publicDomain) || !HOSTNAME_PATTERN.test(adminDomain)
        ? 'HTTPS 域名格式不正确'
        : publicDomain.toLowerCase() === adminDomain.toLowerCase()
          ? '用户端域名与管理端域名不能相同'
          : '';
    const port = Number(values.httpsPort);
    const portError = requirePort && (!Number.isInteger(port) || port < 1 || port > 65535)
      ? 'HTTPS 端口范围必须为 1-65535'
      : '';
    deploymentForm.setFields([
      { name: 'httpsPublicDomain', errors: domainError ? [domainError] : [] },
      { name: 'httpsAdminDomain', errors: domainError ? [domainError] : [] },
      { name: 'httpsPort', errors: portError ? [portError] : [] }
    ]);
    if (domainError || portError) {
      message.warning(domainError || portError);
      return false;
    }
    return true;
  };

  const handleSaveSource = async () => {
    if (sourceLoadError || sourceLoading) return;
    const values = await sourceForm.validateFields();
    setSourceSaving(true);
    try {
      await saveUpgradeSource({
        releaseChannel: values.releaseChannel,
        keepBackups: values.keepBackups,
        manifestUrl: values.manifestUrl?.trim() ?? ''
      });
      setSourceDirty(false);
      message.success('升级配置已保存');
      await loadStatus(true);
    } finally {
      setSourceSaving(false);
    }
  };

  const handleValidateDeployment = async () => {
    const values = await deploymentForm.validateFields();
    setDeploymentSaving(true);
    try {
      const res: any = await validateDeploymentConfig(values);
      message.success(res?.msg || '配置校验任务已受理');
      beginTaskPolling('CONFIG_VALIDATE');
    } finally {
      setDeploymentSaving(false);
    }
  };

  const handleTestDeployment = async (
    target: 'config' | 'dns' | 'certificate' | 'https' | 'mysql' | 'redis' | 'rocketmq'
  ) => {
    const fieldsByTarget: Record<string, Array<keyof DeploymentConfigSaveParams>> = {
      config: ['configPath', 'httpPort', 'adminPort', 'backendPort'],
      dns: ['configPath', 'httpsPublicDomain', 'httpsAdminDomain'],
      certificate: ['configPath', 'httpsPublicDomain', 'httpsAdminDomain', 'httpsCertPath', 'httpsKeyPath'],
      https: ['configPath', 'httpsPort', 'httpsPublicDomain', 'httpsAdminDomain'],
      mysql: ['configPath', 'dbHost', 'dbPort', 'dbName', 'dbUsername', 'dbPassword'],
      redis: ['configPath', 'redisHost', 'redisPort', 'redisUsername', 'redisPassword', 'redisDatabase', 'clearRedisPassword'],
      rocketmq: ['configPath', 'rocketmqEnabled', 'rocketmqNameserver', 'rocketmqAccessKey', 'rocketmqSecretKey', 'clearRocketmqCredentials']
    };
    await deploymentForm.validateFields(fieldsByTarget[target]);
    const allValues = deploymentForm.getFieldsValue(true);
    if (['dns', 'certificate', 'https'].includes(target)
      && !validateHttpsActionFields(allValues, target === 'https')) return;
    const values = target === 'config'
      ? allValues
      : fieldsByTarget[target].reduce<DeploymentConfigSaveParams>((result, field) => {
          result[field] = allValues[field] as never;
          return result;
        }, {});
    values.composeProfiles = allValues.composeProfiles;
    values.httpsEnabled = allValues.httpsEnabled;
    values.dataRoot = allValues.dataRoot;
    if (target === 'mysql') {
      values.mysqlRootPassword = allValues.mysqlRootPassword;
      values.mysqlPort = allValues.mysqlPort;
    }
    setDeploymentTestTarget(target);
    try {
      const res: any = await testDeploymentConfig({ ...values, targets: [target] });
      message.success(res?.msg || '配置诊断任务已受理');
      beginTaskPolling('CONFIG_TEST');
    } catch (error) {
      setDeploymentTestTarget(undefined);
      throw error;
    }
  };

  const toggleDockerProfile = (profile: string, enabled: boolean) => {
    const profiles = composeProfiles
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean)
      .filter((item) => item !== profile);
    if (enabled) profiles.push(profile);
    deploymentForm.setFieldValue('composeProfiles', profiles.join(','));
    setDeploymentDirty(true);
  };

  const handleUploadCertificate = async () => {
    if (!certificateFile || !privateKeyFile) {
      message.warning('请同时选择完整证书链和私钥');
      return;
    }
    const values = await deploymentForm.validateFields(['configPath', 'httpsPublicDomain', 'httpsAdminDomain']);
    if (!validateHttpsActionFields(values, false)) return;
    setCertificateUploading(true);
    setCertificateUploadProgress(0);
    try {
      const res: any = await installHttpsCertificate(certificateFile, privateKeyFile, values, setCertificateUploadProgress);
      message.success(res?.msg || '证书安装任务已受理');
      beginTaskPolling('CERT_INSTALL');
    } catch (error) {
      setCertificateUploading(false);
      setCertificateUploadProgress(0);
      throw error;
    }
  };

  const handleApplyDeployment = async () => {
    const values = await deploymentForm.validateFields();
    Modal.confirm({
      title: '应用配置并重启服务？',
      icon: <ExclamationCircleOutlined />,
      content: '升级器会先备份旧配置，再校验、原子写入并重启。健康检查失败将自动恢复旧配置。',
      okText: '应用并重启',
      cancelText: '取消',
      onOk: async () => {
        setDeploymentSaving(true);
        try {
          const res: any = await applyDeploymentConfig(values);
          message.success(res?.msg || '配置应用任务已受理');
          setDeploymentDirty(false);
          beginTaskPolling('CONFIG_APPLY');
        } finally {
          setDeploymentSaving(false);
        }
      }
    });
  };

  const handleRollbackDeployment = () => {
    Modal.confirm({
      title: '恢复上一份部署配置？',
      icon: <ExclamationCircleOutlined />,
      content: '恢复后服务会重新启动。仅恢复最近一次通过后台应用配置前的备份。',
      okText: '恢复并重启',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        const res: any = await rollbackDeploymentConfig();
        message.success(res?.msg || '配置恢复任务已受理');
        beginTaskPolling('CONFIG_ROLLBACK');
      }
    });
  };

  const handleStartUpgrade = () => {
    if (loading) {
      message.info('正在同步升级任务状态，请稍候');
      return;
    }
    if (taskBusy) {
      message.warning('已有升级任务正在执行，请等待完成');
      return;
    }
    const hostResources = status?.hostResources;
    const resourceRisk = Boolean(hostResources?.detected && hostResources.onlineUpgradeRisk);
    const resourceText = hostResources?.detected
      ? `${hostResources.cpuCores || '-'} 核 / ${formatBytes(hostResources.totalMemoryBytes)}`
      : '未检测到';
    Modal.confirm({
      title: resourceRisk ? '高风险：当前服务器配置可能导致升级宕机' : '确认升级系统？',
      icon: <ExclamationCircleOutlined style={resourceRisk ? { color: '#ff4d4f' } : undefined} />,
      width: resourceRisk ? 600 : undefined,
      centered: resourceRisk,
      maskClosable: resourceRisk ? false : undefined,
      keyboard: resourceRisk ? false : undefined,
      content: (
        <div>
          {resourceRisk && (
            <Alert
              className="upgrade-page__resource-risk"
              type="error"
              showIcon
              message="CPU 不超过 4 核或内存不超过 4 GiB，属于在线升级高风险配置"
              description="在线升级需要在本机拉取并编译服务端、后台管理端和 Web 端，可能持续占满 CPU 和内存，导致 AID 服务无响应、构建被系统杀死，严重时可能引起服务器宕机。"
            />
          )}
          <Descriptions size="small" column={1} className="upgrade-page__confirm-details">
            <Descriptions.Item label="当前版本">v{status?.currentVersion || '-'}</Descriptions.Item>
            <Descriptions.Item label="目标版本">v{status?.latestVersion || '-'}</Descriptions.Item>
            <Descriptions.Item label="发布渠道">
              {status?.latestChannel === 'beta' ? '测试版' : '正式版'}
            </Descriptions.Item>
            <Descriptions.Item label="服务器配置">{resourceText}</Descriptions.Item>
            <Descriptions.Item label="影响">服务将短暂重启，升级器会先创建备份</Descriptions.Item>
          </Descriptions>
          {resourceRisk && (
            <div className="upgrade-page__resource-risk-checklist">
              继续前必须确认：数据库和配置已有异机备份；当前没有生成任务；服务器已配置足够的 Swap
              或可用内存。更稳妥的做法是先升级服务器配置，再进行在线升级。
            </div>
          )}
        </div>
      ),
      okText: resourceRisk ? '我已备份，仍要升级' : '开始升级',
      okButtonProps: resourceRisk ? { danger: true } : undefined,
      cancelText: '取消',
      onOk: async () => {
        const res: any = await startUpgrade();
        message.success(res?.msg || '升级任务已受理');
        beginTaskPolling('UPGRADE');
      }
    });
  };

  const handleUpgradeUpdater = () => {
    if (taskBusy) {
      message.warning('已有升级任务正在执行，请等待完成');
      return;
    }
    Modal.confirm({
      title: '确认升级升级器？',
      icon: <ExclamationCircleOutlined />,
      content: `将从 v${updater?.version || '-'} 升级到 v${updater?.latestVersion || '-'}，升级器会自动重启。`,
      okText: '开始升级',
      cancelText: '取消',
      onOk: async () => {
        const res: any = await startUpdaterUpgrade();
        message.success(res?.msg || '升级器升级任务已受理');
        beginTaskPolling('UPDATER_UPGRADE');
      }
    });
  };

  const handleCancelUpgrade = () => {
    if (!canCancelVersionTask || cancelRequestPending) {
      message.warning('当前阶段不可取消');
      return;
    }
    Modal.confirm({
      title: '确认取消当前升级？',
      icon: <ExclamationCircleOutlined />,
      content: '升级器会安全停止当前下载、环境准备或源码构建，并保留正在运行的旧版本。进入数据库迁移或版本切换后将不允许取消。',
      okText: '确认取消升级',
      okButtonProps: { danger: true },
      cancelText: '继续升级',
      onOk: async () => {
        setCancelSubmitting(true);
        try {
          const res: any = await cancelUpgrade();
          setCancelRequestedTaskId(lastTask?.taskId);
          message.success(res?.msg || '取消请求已提交');
          await loadStatus(false);
          loadUpdaterLogs(true).catch(() => undefined);
        } finally {
          setCancelSubmitting(false);
        }
      }
    });
  };

  const handleRollbackConfirm = async () => {
    if (!selectedRollback || rollbackConfirmText.trim() !== selectedRollback.version) return;
    if (taskBusy) {
      message.warning('已有升级任务正在执行，请等待完成');
      return;
    }
    setRollbackSubmitting(true);
    try {
      const res: any = await rollbackSystem(selectedRollback.version);
      message.success(res?.msg || '回退任务已受理');
      setRollbackConfirmOpen(false);
      setRollbackConfirmText('');
      beginTaskPolling('ROLLBACK');
    } finally {
      setRollbackSubmitting(false);
    }
  };

  const handleInstallAssets = async () => {
    if (!assetsFile) {
      message.warning('请先选择资源包');
      return;
    }
    const fileName = assetsFile.name.toLowerCase();
    if (!fileName.endsWith('.tar') && !fileName.endsWith('.tar.gz')) {
      message.warning('仅支持 tar 资源包');
      return;
    }
    if (assetsStatus?.maxUploadBytes && assetsFile.size > assetsStatus.maxUploadBytes) {
      message.warning(`资源包不能超过 ${formatBytes(assetsStatus.maxUploadBytes)}`);
      return;
    }

    setAssetsInstalling(true);
    setAssetsUploadProgress(0);
    try {
      const res = await installOfficialAssets(assetsFile, setAssetsUploadProgress);
      setAssetsStatus(res.data || null);
      setAssetsUploadProgress(100);
      setAssetsFile(null);
      message.success(res.msg || '官方资源初始化成功');
    } catch {
      message.error('上传失败，请使用下方命令安装');
    } finally {
      setAssetsInstalling(false);
    }
  };

  const releaseLinks = (
    <Space className="upgrade-page__release-links" wrap size={[16, 8]}>
      {status?.giteeReleaseUrl && (
        <a href={status.giteeReleaseUrl} target="_blank" rel="noreferrer">
          <FileTextOutlined /> Gitee 发布页
        </a>
      )}
      {status?.githubReleaseUrl && (
        <a href={status.githubReleaseUrl} target="_blank" rel="noreferrer">
          <FileTextOutlined /> GitHub 发布页
        </a>
      )}
      {status?.docsUrl && (
        <a href={status.docsUrl} target="_blank" rel="noreferrer">
          <FileTextOutlined /> 使用教程
        </a>
      )}
      {status?.promptDocsUrl && (
        <a href={status.promptDocsUrl} target="_blank" rel="noreferrer">
          <FileTextOutlined /> 提示词教程
        </a>
      )}
    </Space>
  );
  const hasReleaseLinks = Boolean(
    status?.giteeReleaseUrl || status?.githubReleaseUrl || status?.docsUrl || status?.promptDocsUrl
  );

  const releaseNotes = status?.releaseNotes?.trim() || '';
  const releaseNotesIsLong = releaseNotes.length > 1200 || releaseNotes.split(/\r?\n/).length > 18;
  const handleReleaseNotesToggle = () => {
    if (releaseNotesExpanded) {
      setReleaseNotesExpanded(false);
      window.requestAnimationFrame(() => {
        releaseNotesCardRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      });
      return;
    }
    setReleaseNotesExpanded(true);
  };

  const releaseNotesCard = (
    <div className="upgrade-page__release-card-anchor" ref={releaseNotesCardRef}>
      <Card
        className="upgrade-page__release-card"
        bordered={false}
        title={
          <Space size={8}>
            <FileTextOutlined />
            <span>版本说明</span>
          </Space>
        }
      >
        <div className="upgrade-page__release-meta">
          <div>
            <Text type="secondary">最新版本</Text>
            <Text strong>{status?.latestVersion ? `v${status.latestVersion}` : '-'}</Text>
          </div>
          {status?.latestVersion && (
            <Tag color={status.latestChannel === 'beta' ? 'purple' : 'blue'}>
              {status.latestChannel === 'beta' ? '测试版' : '正式版'}
            </Tag>
          )}
          <Text className="upgrade-page__release-date" type="secondary">
            发布时间：{status?.publishedAt || '-'}
          </Text>
        </div>

        {releaseNotes ? (
          <>
            <div
              className={`upgrade-page__release-viewport${releaseNotesExpanded ? ' upgrade-page__release-viewport--expanded' : ''}`}
            >
              <div className="upgrade-page__release-content">{renderReleaseNotes(releaseNotes)}</div>
              {releaseNotesIsLong && !releaseNotesExpanded && <div className="upgrade-page__release-fade" />}
            </div>
            {releaseNotesIsLong && (
              <Button
                className="upgrade-page__release-toggle"
                type="link"
                icon={releaseNotesExpanded ? <UpOutlined /> : <DownOutlined />}
                onClick={handleReleaseNotesToggle}
              >
                {releaseNotesExpanded ? '收起版本说明' : '展开全部'}
              </Button>
            )}
          </>
        ) : (
          <div className="empty-box">
            <div style={{ marginBottom: 6 }}>
              <FileTextOutlined style={{ fontSize: 24 }} />
            </div>
            <div>暂无版本说明</div>
            <div className="help-text">发布说明补充后将在这里展示。</div>
          </div>
        )}

        {hasReleaseLinks && <div className="upgrade-page__release-footer">{releaseLinks}</div>}
      </Card>
    </div>
  );

  const statusNotice = (() => {
    if (!status) return null;
    if (status.checkError) {
      return (
        <Alert
          type="warning"
          showIcon
          message={`更新源不可用：${status.checkError}`}
          description="请检查服务器网络和升级配置，恢复后重新检查。"
          action={
            <Button size="small" loading={checking} onClick={() => loadStatus(true)}>
              重新检查
            </Button>
          }
        />
      );
    }
    if (status.hasUpdate && updater?.hasUpdate) {
      return (
        <Alert
          type="warning"
          showIcon
          message="请先升级升级器"
          description={`系统 v${status.latestVersion || '-'} 与升级器 v${updater.latestVersion || '-'} 均有更新。为保证SQL、备份和回滚协议兼容，必须先完成升级器更新。`}
          action={
            <Button type="primary" disabled={taskBusy} onClick={handleUpgradeUpdater}>
              先升级升级器
            </Button>
          }
        />
      );
    }
    if (status.hasUpdate && status.belowMinimumVersion) {
      return (
        <Alert
          type="warning"
          showIcon
          message={`当前版本低于最低直升版本 v${status.minimumVersion}`}
          description="请先安装中间版本，再执行一键升级。"
        />
      );
    }
    if (status.hasUpdate) {
      return (
        <Alert
          type="info"
          showIcon
          icon={<CloudDownloadOutlined />}
          message={
            <Space wrap size={6}>
              <Text strong>发现新版本 v{status.latestVersion}</Text>
              <Tag color={status.latestChannel === 'beta' ? 'purple' : 'blue'}>
                {status.latestChannel === 'beta' ? '测试版' : '正式版'}
              </Tag>
              <Text type="secondary">{status.publishedAt || '-'}</Text>
            </Space>
          }
          description="升级前会自动备份并校验制品，服务将在升级期间短暂重启。"
          action={
            <Button
              type="primary"
              icon={<RocketOutlined />}
              disabled={!updater?.ready || updater?.hasUpdate || taskBusy || loading}
              title={
                loading
                  ? '正在同步升级任务状态'
                  : taskBusy
                  ? '已有任务正在执行'
                  : updater?.hasUpdate
                    ? '必须先升级升级器'
                    : updater?.ready
                      ? undefined
                      : '需先安装并启动升级器'
              }
              onClick={handleStartUpgrade}
            >
              立即升级
            </Button>
          }
        />
      );
    }
    return (
      <Alert
        type="success"
        showIcon
        icon={<CheckCircleOutlined />}
        message="当前已是最新版本"
        description={`最近检查：${status.checkedAt || '-'}`}
      />
    );
  })();

  const updaterPanel = (
    <div className="upgrade-page__tab-panel">
      <div className="upgrade-page__section-toolbar">
        <div>
          <Text strong>升级器状态</Text>
          <Text type="secondary"> · {updater?.message || '负责制品校验、备份、升级和回退'}</Text>
        </div>
        <Space wrap>
          {updater && !updater.ready && (
            <Button
              type="primary"
              danger={updater.status === 'NOT_INSTALLED'}
              icon={<DownloadOutlined />}
              onClick={() => setInstallOpen(true)}
            >
              {updater.status === 'NOT_INSTALLED' ? '安装升级器' : '修复引导'}
            </Button>
          )}
          <Button icon={<SyncOutlined />} loading={loading || taskPolling} onClick={() => loadStatus(false)}>
            重新检测
          </Button>
        </Space>
      </div>

      <Descriptions bordered size="small" column={{ xs: 1, sm: 2, lg: 4 }}>
        <Descriptions.Item label="运行状态">
          <Tag color={updaterTag.color}>{updaterTag.text}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label="部署方式">
          {updater?.serviceManager === 'docker'
            ? 'Docker'
            : updater?.serviceManager === 'systemd'
              ? 'systemd'
              : '未上报'}
        </Descriptions.Item>
        <Descriptions.Item label="当前版本">{updater?.version ? `v${updater.version}` : '-'}</Descriptions.Item>
        <Descriptions.Item label="最新版本">
          {updater?.latestVersion ? `v${updater.latestVersion}` : '-'}
        </Descriptions.Item>
      </Descriptions>

      {lastTask && lastTaskMeta && (
        <Alert
          className="upgrade-page__task-alert"
          type={lastTaskMeta.alert}
          showIcon
          icon={lastTask.state === 'RUNNING' || taskPolling ? <Spin size="small" /> : undefined}
          message={`${TASK_ACTION_TEXT[lastTask.action || ''] || lastTask.action || '升级任务'} · ${lastTaskMeta.text}`}
          description={
            <Space direction="vertical" size={2}>
              {lastTask.message && <Text>{lastTask.message}</Text>}
              {lastTask.finishedAt && <Text type="secondary">完成时间：{lastTask.finishedAt}</Text>}
            </Space>
          }
        />
      )}

      {updater?.hasUpdate && (
        <Alert
          className="upgrade-page__task-alert"
          type="info"
          showIcon
          message={`升级器可升级到 v${updater.latestVersion}`}
          action={
            <Button type="primary" disabled={!updater.ready || taskBusy} onClick={handleUpgradeUpdater}>
              在线升级
            </Button>
          }
        />
      )}

      {updater && !updater.ready && (
        <Alert
          className="upgrade-page__task-alert"
          type="warning"
          showIcon
          message={updater.status === 'NOT_INSTALLED' ? '未安装升级器' : '升级器当前不可用'}
          description="一键升级和回退暂不可用，请打开修复引导处理。"
        />
      )}
    </div>
  );

  const rollbackPanel = (
    <div className="upgrade-page__tab-panel">
      <div className="upgrade-page__section-toolbar">
        <div>
          <Text strong>可回退版本</Text>
          <Text type="secondary"> · 执行前会自动备份程序、配置和数据库</Text>
        </div>
        {rollbackCount > 0 && (
          <Button
            danger
            disabled={!rollbackVersion || !updater?.ready || taskBusy}
            title={updater?.ready ? undefined : '需先安装并启动升级器'}
            onClick={() => {
              setRollbackConfirmText('');
              setRollbackConfirmOpen(true);
            }}
          >
            回退到所选版本
          </Button>
        )}
      </div>

      {rollbackCount > 0 ? (
        <Radio.Group
          value={rollbackVersion}
          onChange={(event) => setRollbackVersion(event.target.value)}
          className="upgrade-page__rollback-list"
        >
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            {status?.rollbackReleases?.map((release) => (
              <Radio key={release.version} value={release.version}>
                <span className="upgrade-page__rollback-item">
                  <Text strong>v{release.version}</Text>
                  <Text type="secondary">{release.publishedAt || '-'}</Text>
                  <Tag color={release.databaseCompatible ? 'green' : 'orange'}>
                    {release.databaseCompatible ? '数据库兼容' : '需要数据库回退'}
                  </Tag>
                  {release.notes && <Text type="secondary">{release.notes}</Text>}
                </span>
              </Radio>
            ))}
          </Space>
        </Radio.Group>
      ) : (
        <div className="upgrade-page__empty">
          <HistoryOutlined />
          <span>当前没有可回退版本</span>
        </div>
      )}
    </div>
  );

  const settingsPanel = (
    <div className="upgrade-page__tab-panel">
      <div className="upgrade-page__section-toolbar">
        <Space>
          <Text strong>升级配置</Text>
          {sourceDirty && <Tag color="gold">有未保存修改</Tag>}
        </Space>
        <Space>
          <Button icon={<ReloadOutlined />} disabled={sourceLoading || sourceSaving} onClick={loadSource}>
            重新加载
          </Button>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={sourceSaving}
            disabled={sourceLoading || sourceLoadError || !sourceDirty}
            onClick={handleSaveSource}
          >
            保存配置
          </Button>
        </Space>
      </div>

      {sourceLoadError && (
        <Alert
          type="error"
          showIcon
          message="升级配置加载失败"
          description="为避免覆盖现有配置，数据恢复前已禁止保存。"
          action={
            <Button size="small" onClick={loadSource}>
              重试
            </Button>
          }
        />
      )}

      <Spin spinning={sourceLoading}>
        <Form
          form={sourceForm}
          layout="vertical"
          className="upgrade-page__source-form"
          disabled={sourceLoading || sourceLoadError}
          onValuesChange={() => setSourceDirty(true)}
        >
          <Row gutter={24}>
            <Col xs={24} lg={14}>
              <Form.Item
                name="releaseChannel"
                label="接收版本渠道"
                rules={[{ required: true, message: '请选择版本渠道' }]}
                extra="仅正式版只读取顶层；正式版 + 测试版会比较顶层和 beta，选择版本号更高的一项。"
              >
                <Radio.Group>
                  <Space direction="vertical" size={8}>
                    <Radio value="stable">仅正式版</Radio>
                    <Radio value="all">正式版 + 测试版</Radio>
                  </Space>
                </Radio.Group>
              </Form.Item>
            </Col>
            <Col xs={24} lg={10}>
              <Form.Item
                name="keepBackups"
                label="备份保留份数"
                rules={[{ required: true, message: '请输入备份保留份数' }]}
                extra="每次升级或回退前自动备份，超出后优先清理最旧备份。"
              >
                <InputNumber min={1} max={50} precision={0} style={{ width: 180 }} />
              </Form.Item>
            </Col>
          </Row>

          <Collapse
            className="upgrade-page__advanced"
            ghost
            items={[
              {
                key: 'manifest',
                label: '高级设置',
                children: (
                  <Form.Item
                    name="manifestUrl"
                    label="版本更新清单地址"
                    extra="留空使用官方源。修改后保存，再点击页面顶部“检查更新”验证连通性。"
                    rules={[{ type: 'url', message: '请输入合法的 http/https 地址' }]}
                  >
                    <Input
                      placeholder="留空使用官方默认源"
                      allowClear
                      addonAfter={
                        <Button
                          type="link"
                          size="small"
                          onClick={() => {
                            sourceForm.setFieldValue('manifestUrl', '');
                            setSourceDirty(true);
                          }}
                        >
                          恢复官方源
                        </Button>
                      }
                    />
                  </Form.Item>
                )
              }
            ]}
          />
        </Form>
      </Spin>
    </div>
  );

  const deploymentPanel = (
    <div className="upgrade-page__tab-panel">
      <div className="upgrade-page__section-toolbar">
        <Space>
          <Text strong>运行配置</Text>
          {deploymentConfig && <Tag color="blue">{deploymentConfig.mode === 'docker' ? 'Docker' : 'systemd'}</Tag>}
          {deploymentDirty && <Tag color="gold">有未应用修改</Tag>}
        </Space>
        <Space wrap>
          <Button icon={<ReloadOutlined />} loading={deploymentLoading} onClick={() => { setDeploymentCheckResults({}); loadDeployment(); }}>
            重新加载
          </Button>
        </Space>
      </div>

      {!deploymentLoading && !deploymentConfig && (
        <Alert
          type="warning"
          showIcon
          message="运行配置不可用"
          description="请先把升级器更新到支持配置管理的版本，并确认升级器正在运行。"
        />
      )}

      {deploymentConfig && !configProtocolReady && (
        <Alert
          type="error"
          showIcon
          message={configProtocolIncompatible ? '当前升级器协议不兼容，请先升级' : '配置管理暂不可用'}
          description={configProtocolIncompatible
            ? '当前 aid-updater 不支持新版配置诊断与证书管理。为避免错误写入，测试、证书上传、保存及回滚操作已禁用。'
            : '当前 aid-updater 未正常运行。请先完成修复，为避免错误写入，测试、证书上传、保存及回滚操作已禁用。'}
        />
      )}

      {deploymentConfig && (
        <div className="upgrade-page__config-summary">
          <div>
            <span>配置真源</span>
            <strong>{deploymentConfig.mode === 'docker' ? 'Docker 环境配置' : '手动部署配置'}</strong>
            <Text ellipsis={{ tooltip: deploymentConfig.configPath }}>{deploymentConfig.configPath}</Text>
          </div>
          <div>
            <span>HTTPS</span>
            <strong>{httpsEnabled ? '已启用' : '未启用'}</strong>
            <Text type="secondary">启用后仍保留 HTTP/IP 访问</Text>
          </div>
          <div>
            <span>数据服务</span>
            <strong>{deploymentConfig.mode === 'docker'
              ? usesInternalMysql ? '内置 MySQL' : '外部 MySQL'
              : usesLocalManualMysql ? '本机 MySQL' : '外部 MySQL'}</strong>
            <Text type="secondary">密钥只写配置文件，不在页面回显</Text>
          </div>
          <div>
            <span>消息队列</span>
            <strong>{mqActive ? '已启用' : rocketmqEnabled ? '待启用组件' : '未启用'}</strong>
            <Text type="secondary">未启用时不初始化 MQ</Text>
          </div>
        </div>
      )}

      <Spin spinning={deploymentLoading}>
        <Form
          form={deploymentForm}
          layout="vertical"
          disabled={!deploymentConfig || deploymentLoading || !configProtocolReady}
          onValuesChange={() => setDeploymentDirty(true)}
        >
          <Collapse
            className="upgrade-page__config-sections"
            defaultActiveKey={['network']}
            expandIconPosition="end"
            items={[
              {
                key: 'network',
                label: (
                  <div className="upgrade-page__config-section-title">
                    <CloudServerOutlined />
                    <div><strong>基础网络</strong><span>访问端口与部署组件</span></div>
                  </div>
                ),
                children: (
                  <>
          <Row gutter={20}>
            <Col xs={24} md={8}>
              <Form.Item name="httpPort" label="用户端口" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item name="adminPort" label="后台管理端口" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item name="backendPort" label="后端端口" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
            </Col>
          </Row>

          {deploymentConfig?.mode === 'docker' && (
            <Form.Item
              name="dockerMirrors"
              label="Docker 国内镜像候选"
              extra="多个 Registry 前缀用英文逗号分隔。部署器测速排序后逐个尝试，不得填写账号、密码或查询参数。"
              rules={[{ max: 1024, message: '镜像候选内容不能超过 1024 个字符' }]}
            >
              <Input placeholder="docker.m.daocloud.io,dockerproxy.net" />
            </Form.Item>
          )}

          <Row gutter={20}>
            <Col xs={24} md={12}>
              <Form.Item
                name="dependencyInstallMode"
                label="依赖处理方式"
                extra={
                  deploymentConfig?.mode === 'docker'
                    ? '自动模式会拉取缺失镜像，已有且摘要匹配的镜像直接跳过；Docker Engine 必须预先安装。'
                    : '自动模式下载固定版本工具链；系统服务仍由发行版包管理器安装。'
                }
              >
                <Select
                  options={[
                    { label: '自动安装或拉取（推荐）', value: 'auto' },
                    { label: '仅检查并提示', value: 'manual' }
                  ]}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="dependencyRegion"
                label="依赖下载线路"
                extra="自动按服务器公网出口判断；首选源失败会切换另一条线路。"
              >
                <Select
                  options={[
                    { label: '自动判断（推荐）', value: 'auto' },
                    { label: '国内镜像优先', value: 'cn' },
                    { label: '官方地址优先', value: 'global' }
                  ]}
                />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={20}>
            <Col xs={24} md={12}>
              <Form.Item name="dataRoot" label="数据根目录" extra="运行后禁止直接迁移；证书目录也受此路径约束。">
                <Input disabled />
              </Form.Item>
            </Col>
            {deploymentConfig?.mode === 'docker' && (
              <Col xs={24} md={12}>
                <Form.Item
                  name="composeProfiles"
                  hidden
                >
                  <Input />
                </Form.Item>
                <div className="upgrade-page__component-switches">
                  <div><span><DatabaseOutlined /></span><div><Text strong>内置 MySQL 5.7</Text><Text type="secondary">关闭后使用下方外部数据库</Text></div><Switch checked={Boolean(usesInternalMysql)} onChange={(checked) => toggleDockerProfile('mysql', checked)} /></div>
                  <div><span><DatabaseOutlined /></span><div><Text strong>内置 Redis</Text><Text type="secondary">关闭后填写外部 Redis 地址</Text></div><Switch checked={Boolean(usesInternalRedis)} onChange={(checked) => toggleDockerProfile('redis', checked)} /></div>
                </div>
              </Col>
            )}
          </Row>

                  <div className="upgrade-page__section-test">
                    <div><Text strong>基础配置校验</Text><Text type="secondary">检查字段格式、Compose 与 Nginx 模板，不写入配置。</Text></div>
                    <Button
                      icon={<CheckCircleOutlined />}
                      loading={deploymentTestTarget === 'config'}
                      disabled={taskBusy || !configProtocolReady}
                      onClick={() => handleTestDeployment('config')}
                    >测试配置</Button>
                    {deploymentChecks.config && (
                      <Alert
                        showIcon
                        type={deploymentChecks.config.status === 'PASS' ? 'success' : deploymentChecks.config.status === 'SKIPPED' ? 'info' : 'error'}
                        message={deploymentChecks.config.message}
                        description={deploymentChecks.config.suggestion}
                      />
                    )}
                  </div>
                  </>
                )
              },
              {
                key: 'https',
                label: (
                  <div className="upgrade-page__config-section-title">
                    <SafetyCertificateOutlined />
                    <div><strong>域名与 HTTPS</strong><span>域名、证书、443 与连通性</span></div>
                    <Tag color={httpsEnabled ? 'green' : 'default'}>{httpsEnabled ? '已启用' : '未启用'}</Tag>
                  </div>
                ),
                children: (
                  <>
          <div className="upgrade-page__feature-switch">
            <div>
              <Text strong>启用 HTTPS 入口</Text>
              <Text type="secondary">HTTPS 与现有 HTTP/IP 入口并存，不会自动关闭 80 或管理端口。</Text>
            </div>
            <Switch
              checked={httpsEnabled}
              onChange={(checked) => {
                if (deploymentConfig?.mode === 'docker') toggleDockerProfile('https', checked);
                else {
                  deploymentForm.setFieldValue('httpsEnabled', String(checked));
                  setDeploymentDirty(true);
                }
              }}
            />
          </div>
          <Alert
            type="info"
            showIcon
            message={deploymentConfig?.mode === 'docker' ? 'Docker 内置 HTTPS' : 'Nginx HTTPS'}
            description={
              deploymentConfig?.mode === 'docker'
                ? '在 Compose Profiles 中加入 https 后生效。'
                : '选择启用后，重启服务会重新生成并校验 Nginx 站点配置。'
            }
            className="upgrade-page__modal-alert"
          />
          {deploymentConfig?.mode === 'systemd' && (
            <Form.Item name="httpsEnabled" hidden><Input /></Form.Item>
          )}
          <Row gutter={20}>
            <Col xs={24} md={8}>
              <Form.Item name="httpsPort" label="HTTPS端口" rules={portRules(httpsEnabled)}>
                <Input placeholder="443" />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item
                name="httpsPublicDomain"
                label="用户端HTTPS域名"
                rules={[
                  { required: httpsEnabled, message: '请输入用户端 HTTPS 域名' },
                  { pattern: HOSTNAME_PATTERN, message: '请输入合法域名，不要包含协议或路径' },
                  ({ getFieldValue }) => ({
                    validator: (_: unknown, value?: string) => value && value.toLowerCase() === String(getFieldValue('httpsAdminDomain') || '').toLowerCase()
                      ? Promise.reject(new Error('用户端域名与管理端域名不能相同'))
                      : Promise.resolve()
                  })
                ]}
              >
                <Input placeholder="www.example.com" />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item
                name="httpsAdminDomain"
                label="管理端HTTPS域名"
                dependencies={['httpsPublicDomain']}
                rules={[
                  { required: httpsEnabled, message: '请输入管理端 HTTPS 域名' },
                  { pattern: HOSTNAME_PATTERN, message: '请输入合法域名，不要包含协议或路径' },
                  ({ getFieldValue }) => ({
                    validator: (_: unknown, value?: string) => value && value.toLowerCase() === String(getFieldValue('httpsPublicDomain') || '').toLowerCase()
                      ? Promise.reject(new Error('管理端域名与用户端域名不能相同'))
                      : Promise.resolve()
                  })
                ]}
              >
                <Input placeholder="admin.example.com" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="httpsCertPath" label="完整证书路径" extra="由升级器固定安装到受控 ssl 目录，页面不可任意指定路径。">
                <Input readOnly />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="httpsKeyPath" label="证书私钥路径" extra="私钥内容永不回显、永不写数据库或日志。">
                <Input readOnly />
              </Form.Item>
            </Col>
          </Row>

          <div className="upgrade-page__certificate-upload">
            <div className="upgrade-page__certificate-upload-grid">
              <Upload
                accept=".pem"
                maxCount={1}
                fileList={certificateFile ? [certificateFile as any] : []}
                showUploadList={{ showPreviewIcon: false, showDownloadIcon: false }}
                beforeUpload={(file) => { setCertificateFile(file); return false; }}
                onRemove={() => { setCertificateFile(null); return true; }}
              >
                <Button icon={<FileTextOutlined />}>选择 fullchain.pem</Button>
              </Upload>
              <Upload
                accept=".pem"
                maxCount={1}
                fileList={privateKeyFile ? [privateKeyFile as any] : []}
                showUploadList={{ showPreviewIcon: false, showDownloadIcon: false }}
                beforeUpload={(file) => { setPrivateKeyFile(file); return false; }}
                onRemove={() => { setPrivateKeyFile(null); return true; }}
              >
                <Button icon={<SafetyCertificateOutlined />}>选择 privkey.pem</Button>
              </Upload>
              <Button
                type="primary"
                icon={<UploadOutlined />}
                disabled={!certificateFile || !privateKeyFile || taskBusy || !configProtocolReady}
                loading={certificateUploading}
                onClick={handleUploadCertificate}
              >安全上传证书</Button>
            </div>
            {certificateUploading && <Progress percent={certificateUploadProgress} size="small" status="active" />}
            <Text type="secondary">两份 PEM 必须成对上传。升级器会校验证书链、有效期、SAN 与公私钥匹配，并保留可恢复备份。</Text>
            {lastTask?.action === 'CERT_INSTALL' && lastTask.state !== 'RUNNING' && (
              <Alert
                showIcon
                type={lastTask.state === 'SUCCESS' ? 'success' : 'error'}
                message={lastTask.state === 'SUCCESS' ? '证书已安全安装' : '证书安装失败'}
                description={lastTask.message}
              />
            )}
          </div>

          <div className="upgrade-page__diagnostic-grid">
            {([
              ['dns', '测试 DNS', '检查两个域名是否已解析'],
              ['certificate', '测试证书', '检查有效期、SAN 与公私钥'],
              ['https', '测试 HTTPS', '从服务器本地入口完成 TLS 握手']
            ] as const).map(([target, label, description]) => (
              <div className="upgrade-page__diagnostic-card" key={target}>
                <div><Text strong>{label}</Text><Text type="secondary">{description}</Text></div>
                <Button size="small" loading={deploymentTestTarget === target} disabled={taskBusy || !configProtocolReady} onClick={() => handleTestDeployment(target)}>立即测试</Button>
                {deploymentChecks[target] && (
                  <Alert
                    showIcon
                    type={deploymentChecks[target].status === 'PASS' ? 'success' : deploymentChecks[target].status === 'SKIPPED' ? 'info' : 'error'}
                    message={deploymentChecks[target].message}
                    description={deploymentChecks[target].suggestion}
                  />
                )}
              </div>
            ))}
          </div>
                  </>
                )
              },
              {
                key: 'data',
                label: (
                  <div className="upgrade-page__config-section-title">
                    <DatabaseOutlined />
                    <div><strong>数据服务</strong><span>MySQL 与 Redis 连接配置</span></div>
                  </div>
                ),
                children: (
                  <>

          {deploymentConfig?.mode === 'docker' && (
            <Alert
              type={usesInternalMysql ? 'warning' : 'info'}
              showIcon
              message={usesInternalMysql ? '当前使用内置 MySQL 5.7' : '当前使用外部 MySQL 5.7'}
              description={
                usesInternalMysql
                  ? '数据库地址固定为 mysql:3306；已有容器的库名和账号密码不能通过修改配置直接轮换。'
                  : '保存前会校验外部数据库连接与版本；验证成功后不会启动 aid-mysql，旧容器会被移除但数据目录保留。Docker 宿主机数据库可填写 host.docker.internal。'
              }
              className="upgrade-page__modal-alert"
            />
          )}
          <Row gutter={20}>
            <Col xs={24} md={8}>
              <Form.Item name="dbHost" label="数据库地址" rules={[{ required: true }]}>
                <Input disabled={Boolean(usesInternalMysql)} />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item name="dbPort" label="数据库端口" rules={[{ required: true }]}>
                <Input disabled={Boolean(usesInternalMysql)} />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item name="dbName" label="数据库名称" rules={[{ required: true }]}>
                <Input disabled={Boolean(usesInternalMysql)} />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item name="dbUsername" label="数据库账号" rules={[{ required: true }]}>
                <Input disabled={Boolean(usesInternalMysql)} />
              </Form.Item>
            </Col>
            {!usesInternalMysql && (
              <Col xs={24} md={8}>
                <Form.Item name="dbPassword" label="数据库密码">
                  <Input.Password
                    autoComplete="new-password"
                    placeholder={
                      deploymentConfig?.configuredSecrets.includes('DB_PASSWORD')
                        ? '已配置，留空保持不变'
                        : '请输入数据库密码'
                    }
                  />
                </Form.Item>
              </Col>
            )}
          </Row>

          {usesInternalMysql && (
            <Row gutter={20}>
              <Col xs={24} md={12}>
                <Form.Item name="mysqlPort" label="内置 MySQL 宿主机端口">
                  <Input />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item
                  name="mysqlRootPassword"
                  label="内置 MySQL root 密码"
                  extra="留空保持当前密钥；仅用于升级器备份、恢复与增量 SQL。"
                >
                  <Input.Password
                    autoComplete="new-password"
                    placeholder={
                      deploymentConfig?.configuredSecrets.includes('MYSQL_ROOT_PASSWORD')
                        ? '已配置，留空保持不变'
                        : '首次启用内置 MySQL 时必须填写'
                    }
                  />
                </Form.Item>
              </Col>
            </Row>
          )}

          <Row gutter={20}>
            <Col xs={24} md={6}>
              <Form.Item name="redisHost" label="Redis地址" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24} md={4}>
              <Form.Item name="redisPort" label="Redis端口" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24} md={5}>
              <Form.Item name="redisUsername" label="Redis ACL用户名">
                <Input placeholder="传统模式留空" />
              </Form.Item>
            </Col>
            <Col xs={24} md={4}>
              <Form.Item name="redisDatabase" label="Redis库">
                <Input placeholder="0" />
              </Form.Item>
            </Col>
            <Col xs={24} md={5}>
              <Form.Item name="redisPassword" label="Redis密码">
                <Input.Password
                  autoComplete="new-password"
                  placeholder={
                    deploymentConfig?.configuredSecrets.includes('REDIS_PASSWORD')
                      ? '已配置，留空保持不变'
                      : '无密码可留空'
                  }
                />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="clearRedisPassword" valuePropName="checked">
            <Checkbox>清空当前 Redis 密码（仅用于外部 Redis 确认无密码认证时）</Checkbox>
          </Form.Item>

          <div className="upgrade-page__diagnostic-grid upgrade-page__diagnostic-grid--two">
            {([
              ['mysql', '测试 MySQL', '检查 MySQL 5.7、网络与账号认证'],
              ['redis', '测试 Redis', '检查网络、ACL/密码与 PING']
            ] as const).map(([target, label, description]) => (
              <div className="upgrade-page__diagnostic-card" key={target}>
                <div><Text strong>{label}</Text><Text type="secondary">{description}</Text></div>
                <Button size="small" loading={deploymentTestTarget === target} disabled={taskBusy || !configProtocolReady} onClick={() => handleTestDeployment(target)}>立即测试</Button>
                {deploymentChecks[target] && (
                  <Alert showIcon type={deploymentChecks[target].status === 'PASS' ? 'success' : 'error'} message={deploymentChecks[target].message} description={deploymentChecks[target].suggestion} />
                )}
              </div>
            ))}
          </div>
                  </>
                )
              },
              {
                key: 'security',
                label: (
                  <div className="upgrade-page__config-section-title">
                    <SettingOutlined />
                    <div><strong>应用运行参数</strong><span>JWT 密钥与后端 JVM</span></div>
                  </div>
                ),
                children: (
                  <>

          <Row gutter={20}>
            <Col xs={24} md={12}>
              <Form.Item name="tokenSecret" label="JWT密钥" extra="留空保持当前密钥；更换后已有登录状态会失效。">
                <Input.Password
                  autoComplete="new-password"
                  placeholder={
                    deploymentConfig?.configuredSecrets.includes('TOKEN_SECRET')
                      ? '已配置，留空保持不变'
                      : '请输入强随机密钥'
                  }
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="javaOpts" label="JVM参数">
                <Input placeholder="-Xms1g -Xmx2g" />
              </Form.Item>
            </Col>
          </Row>

                  </>
                )
              },
              {
                key: 'mq',
                label: (
                  <div className="upgrade-page__config-section-title">
                    <RocketOutlined />
                    <div><strong>消息队列</strong><span>默认关闭，启用后才初始化 RocketMQ</span></div>
                    <Tag color={mqActive ? 'green' : rocketmqEnabled ? 'orange' : 'default'}>
                      {mqActive ? '已启用' : rocketmqEnabled ? '组件未启用' : '未启用'}
                    </Tag>
                  </div>
                ),
                children: (
                  <>

          <Row gutter={20}>
            <Col xs={24} md={8}>
              <Form.Item name="rocketmqEnabled" hidden><Input /></Form.Item>
              <Form.Item label="RocketMQ 运行模式">
                <Select
                  value={!rocketmqEnabled ? 'disabled' : usesInternalRocketmq ? 'internal' : 'external'}
                  onChange={(value) => {
                    const enabled = value !== 'disabled';
                    deploymentForm.setFieldValue('rocketmqEnabled', String(enabled));
                    if (deploymentConfig?.mode === 'docker') toggleDockerProfile('mq', value === 'internal');
                    setDeploymentDirty(true);
                  }}
                  options={[
                    { label: '关闭（默认）', value: 'disabled' },
                    ...(deploymentConfig?.mode === 'docker' ? [{ label: '内置 RocketMQ', value: 'internal' }] : []),
                    { label: '外部 RocketMQ', value: 'external' }
                  ]}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={16}>
              <Form.Item name="rocketmqNameserver" label="RocketMQ NameServer">
                <Input />
              </Form.Item>
            </Col>
            {usesInternalRocketmq && (
              <Col xs={24} md={12}>
                <Form.Item
                  name="rocketmqFlushDiskType"
                  label="RocketMQ Broker刷盘"
                  extra="异步刷盘性能优先，同步刷盘持久性优先。业务投递始终等待Broker确认。"
                >
                  <Select
                    options={[
                      { label: '异步刷盘（推荐）', value: 'ASYNC_FLUSH' },
                      { label: '同步刷盘', value: 'SYNC_FLUSH' }
                    ]}
                  />
                </Form.Item>
              </Col>
            )}
            <Col xs={24} md={12}>
              <Form.Item name="rocketmqAccessKey" label="RocketMQ AccessKey">
                <Input.Password
                  autoComplete="new-password"
                  placeholder={
                    deploymentConfig?.configuredSecrets.includes('ROCKETMQ_ACCESS_KEY')
                      ? '已配置，留空保持不变'
                      : '未启用ACL可留空'
                  }
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="rocketmqSecretKey" label="RocketMQ SecretKey">
                <Input.Password
                  autoComplete="new-password"
                  placeholder={
                    deploymentConfig?.configuredSecrets.includes('ROCKETMQ_SECRET_KEY')
                      ? '已配置，留空保持不变'
                      : '未启用ACL可留空'
                  }
                />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="clearRocketmqCredentials" valuePropName="checked">
            <Checkbox>清空当前 RocketMQ ACL 凭证（AccessKey 与 SecretKey 同时清空）</Checkbox>
          </Form.Item>

          <div className="upgrade-page__section-test">
            <div><Text strong>RocketMQ 连通性</Text><Text type="secondary">仅在显式启用后检测；关闭状态显示已跳过。</Text></div>
            <Button loading={deploymentTestTarget === 'rocketmq'} disabled={taskBusy || !configProtocolReady} onClick={() => handleTestDeployment('rocketmq')}>立即测试</Button>
            {deploymentChecks.rocketmq && (
              <Alert showIcon type={deploymentChecks.rocketmq.status === 'PASS' ? 'success' : deploymentChecks.rocketmq.status === 'SKIPPED' ? 'info' : 'error'} message={deploymentChecks.rocketmq.message} description={deploymentChecks.rocketmq.suggestion} />
            )}
          </div>
                  </>
                )
              },
              {
                key: 'advanced',
                label: (
                  <div className="upgrade-page__config-section-title">
                    <SettingOutlined />
                    <div><strong>高级配置</strong><span>配置真源、下载线路与容器资源调优</span></div>
                  </div>
                ),
                children: (
                  <>

          <Form.Item
            name="configPath"
            label="配置文件路径"
            rules={[{ required: true, message: '请输入配置文件路径' }]}
            extra={`当前部署方式为 ${deploymentConfig?.mode === 'docker' ? 'Docker' : '手动部署'}。仅允许默认路径 ${deploymentConfig?.defaultConfigPath || '-'}，或 ${deploymentConfig?.allowedConfigRoot || '-'} 目录内的 .env/.conf 普通文件；服务端会拒绝软链接和越界路径。`}
          >
            <Input />
          </Form.Item>

          {deploymentConfig?.mode === 'docker' && (
            <Collapse
              ghost
              items={[
                {
                  key: 'docker-tuning',
                  label: 'Docker组件与资源调优',
                  children: (
                    <>
                      <Row gutter={20}>
                        <Col xs={24} md={12}>
                          <Form.Item name="mysqlBufferPool" label="MySQL缓冲池">
                            <Input placeholder="2G" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={12}>
                          <Form.Item name="mysqlMaxConnections" label="MySQL最大连接数">
                            <Input placeholder="500" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={12}>
                          <Form.Item name="redisMaxmemory" label="Redis内存上限">
                            <Input placeholder="1gb" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={12}>
                          <Form.Item name="redisMaxmemoryPolicy" label="Redis淘汰策略">
                            <Input placeholder="noeviction" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={12}>
                          <Form.Item name="webNodeOptions" label="Web Node参数">
                            <Input />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={12}>
                          <Form.Item name="mqNamesrvJavaOpts" label="MQ NameServer JVM">
                            <Input />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={12}>
                          <Form.Item name="mqBrokerJavaOpts" label="MQ Broker JVM">
                            <Input />
                          </Form.Item>
                        </Col>
                      </Row>
                    </>
                  )
                }
              ]}
            />
          )}
                  </>
                )
              }
            ]}
          />

          <div className="upgrade-page__config-actions">
            <div>
              <Text strong>{deploymentDirty ? '存在尚未应用的修改' : '当前表单与已加载配置一致'}</Text>
              <Text type="secondary">测试不写配置；应用时先校验和备份，重启失败会自动恢复。</Text>
            </div>
            <Space wrap>
              <Button disabled={!deploymentConfig || !configProtocolReady} loading={deploymentSaving} onClick={handleValidateDeployment}>校验全部配置</Button>
              <Button danger disabled={!deploymentConfig || taskBusy || !configProtocolReady} onClick={handleRollbackDeployment}>恢复上次配置</Button>
              <Button
                type="primary"
                icon={<SaveOutlined />}
                disabled={!deploymentConfig || !deploymentDirty || taskBusy || !configProtocolReady}
                loading={deploymentSaving}
                onClick={handleApplyDeployment}
              >保存并重启生效</Button>
            </Space>
          </div>
        </Form>
      </Spin>
    </div>
  );

  const assetsPanel = (
    <div className="upgrade-page__tab-panel">
      <div className="upgrade-page__section-toolbar">
        <Space>
          <Text strong>官方资源初始化</Text>
          <Tag color={assetsStatus?.initialized ? 'green' : 'orange'}>
            {assetsStatus?.initialized ? '已初始化' : '未初始化'}
          </Tag>
        </Space>
        <Button icon={<ReloadOutlined />} loading={assetsLoading} onClick={loadAssetsStatus}>
          刷新状态
        </Button>
      </div>

      <Spin spinning={assetsLoading}>
        <Alert
          type={assetsStatus?.initialized ? 'success' : 'warning'}
          showIcon
          message={assetsStatus?.initialized ? '官方资源已就绪' : '请初始化官方资源包'}
          description={assetsStatus?.message || '上传官方资源包后，系统会校验文件并自动解压到本地存储目录。'}
          className="upgrade-page__modal-alert"
        />

        <Descriptions bordered size="small" column={{ xs: 1, md: 2 }}>
          <Descriptions.Item label="目标目录">{assetsStatus?.targetDirectory || '-'}</Descriptions.Item>
          <Descriptions.Item label="资源文件数">{assetsStatus?.fileCount || 0}</Descriptions.Item>
          <Descriptions.Item label="资源总大小">{formatBytes(assetsStatus?.totalBytes)}</Descriptions.Item>
          <Descriptions.Item label="推荐文件名">
            {assetsStatus?.recommendedArchiveName || 'aid-official-assets_1.0.0-beta.2.tar.gz'}
          </Descriptions.Item>
        </Descriptions>

        <Alert
          type="info"
          showIcon
          message="资源包结构说明"
          description="支持 .tar 和 .tar.gz。压缩包内必须包含 files/aid 目录；系统只会安装该目录，并校验资源清单、防止路径越界。再次初始化会完整替换旧的 aid 资源目录。"
          className="upgrade-page__modal-alert"
        />

        <Upload.Dragger
          accept=".tar,.gz,application/x-tar,application/gzip"
          maxCount={1}
          disabled={assetsInstalling}
          beforeUpload={(file) => {
            setAssetsFile(file);
            setAssetsUploadProgress(0);
            return false;
          }}
          fileList={
            assetsFile
              ? [
                  {
                    uid: assetsFile.name,
                    name: assetsFile.name,
                    size: assetsFile.size,
                    status: 'done'
                  }
                ]
              : []
          }
          onRemove={() => {
            setAssetsFile(null);
            setAssetsUploadProgress(0);
          }}
        >
          <p className="ant-upload-drag-icon">
            <InboxOutlined />
          </p>
          <p className="ant-upload-text">点击或拖拽官方资源包到这里</p>
          <p className="ant-upload-hint">
            单文件最大 {formatBytes(assetsStatus?.maxUploadBytes || 1024 * 1024 * 1024)}，上传完成后自动校验并安装
          </p>
        </Upload.Dragger>

        <div className="upgrade-page__assets-actions">
          <Button
            type="primary"
            icon={<DownloadOutlined />}
            disabled={!assetsFile}
            loading={assetsInstalling}
            onClick={handleInstallAssets}
          >
            上传并初始化
          </Button>
          {assetsFile && (
            <Text type="secondary">
              {assetsFile.name}（{formatBytes(assetsFile.size)}）
            </Text>
          )}
        </div>
        {(assetsInstalling || assetsUploadProgress > 0) && (
          <Progress
            percent={assetsUploadProgress}
            status={assetsInstalling ? 'active' : assetsUploadProgress === 100 ? 'success' : 'normal'}
          />
        )}

        <Alert
          type="warning"
          showIcon
          message="浏览器无法上传时的完整安装方式"
          description={
            <div>
              <Paragraph>
                先从当前版本的发布页下载官方资源包并上传到服务器 <Text code>/data/aid/</Text>
                ，然后运行下面的命令。命令会把包内的 <Text code>files/aid</Text> 解压为{' '}
                <Text code>/data/aid/uploadPath/aid</Text>。
              </Paragraph>
              {hasReleaseLinks && <div className="upgrade-page__assets-release-links">{releaseLinks}</div>}
              <Paragraph copyable code className="upgrade-page__assets-command">
                {assetsStatus?.manualCommand ||
                  "sudo mkdir -p /data/aid/uploadPath && sudo tar -xf /data/aid/aid-official-assets_1.0.0-beta.2.tar.gz -C /data/aid/uploadPath --strip-components=2 --wildcards '*/files/aid/*'"}
              </Paragraph>
              <Text type="secondary">
                如果下载文件名以 .tar 结尾，把命令中的 .tar.gz 改为
                .tar。执行完成后回到本页点击“刷新状态”确认文件数量和总大小。
              </Text>
            </div>
          }
          className="upgrade-page__modal-alert"
        />
      </Spin>
    </div>
  );

  const awaitingTask = awaitingVersionTask;
  const terminalState = awaitingTask ? 'RUNNING' : lastTask?.state || 'RUNNING';
  const terminalStateMeta = TASK_STATE_META[terminalState] || TASK_STATE_META.RUNNING;
  const terminalProgress = awaitingTask
    ? 0
    : Math.max(0, Math.min(100, Number(lastTask?.progress ?? (terminalState === 'SUCCESS' ? 100 : 1))));
  const versionProgressPanel = showProgressTerminal ? (
    <Card className="upgrade-page__terminal-card" bordered={false}>
      <div className="upgrade-page__terminal-titlebar">
        <div className="upgrade-page__terminal-lights" aria-hidden="true">
          <span />
          <span />
          <span />
        </div>
        <div className="upgrade-page__terminal-title">
          <CodeOutlined />
          <span>AID Upgrade Console</span>
          <Tag
            color={
              terminalStateMeta.alert === 'error' ? 'red' : terminalStateMeta.alert === 'success' ? 'green' : 'blue'
            }
          >
            {terminalStateMeta.text}
          </Tag>
        </div>
        <Space size={4}>
          {(canCancelVersionTask || (taskRunning && cancelRequestPending)) && (
            <Button
              danger
              size="small"
              icon={<StopOutlined />}
              loading={cancelRequestPending}
              disabled={cancelRequestPending}
              onClick={handleCancelUpgrade}
            >
              {cancelRequestPending ? '正在取消' : '取消升级'}
            </Button>
          )}
          {taskRunning && isVersionTask && !lastTask?.cancellable && !cancelRequestPending && (
            <Tag color="orange">关键阶段不可取消</Tag>
          )}
          <Button type="text" size="small" icon={<ReloadOutlined />} onClick={() => loadUpdaterLogs(false)}>
            刷新
          </Button>
          {!taskBusy && (
            <Button
              type="text"
              size="small"
              icon={<CloseOutlined />}
              onClick={() => setDismissedTaskId(progressTaskId)}
            >
              关闭
            </Button>
          )}
        </Space>
      </div>
      <div className="upgrade-page__terminal-progress">
        <div>
          <Text>{TASK_ACTION_TEXT[progressAction || ''] || '版本任务'}</Text>
          <Text>{awaitingTask ? '等待升级器认领任务' : lastTask?.phase || lastTask?.message || '正在执行'}</Text>
          <Text>{terminalProgress}%</Text>
        </div>
        <Progress
          percent={terminalProgress}
          showInfo={false}
          status={
            terminalState === 'FAILED'
              ? 'exception'
              : terminalState === 'SUCCESS'
                ? 'success'
                : terminalState === 'CANCELLED'
                  ? 'normal'
                  : 'active'
          }
          strokeColor={
            terminalState === 'FAILED'
              ? '#ff4d4f'
              : terminalState === 'SUCCESS'
                ? '#52c41a'
                : terminalState === 'CANCELLED'
                  ? '#94a3b8'
                  : '#22c55e'
          }
          trailColor="rgba(255,255,255,.12)"
        />
        <div className="upgrade-page__terminal-meta">
          <span>任务：{lastTask?.taskId || '正在提交'}</span>
          <span>开始：{lastTask?.startedAt || '-'}</span>
          <span>更新：{lastTask?.updatedAt || lastTask?.finishedAt || '-'}</span>
        </div>
      </div>
      <pre ref={terminalRef} className="upgrade-page__terminal-output">
        {updaterLogs?.lines?.length
          ? updaterLogs.lines.join('\n')
          : awaitingTask
            ? '$ 正在等待 aid-updater 认领任务...'
            : updaterLogs?.message || '$ 暂无任务日志'}
      </pre>
      <div className="upgrade-page__terminal-footer">
        <span className={taskBusy ? 'is-running' : ''} />
        {taskBusy ? '每 1.2 秒同步实时日志；升级期间请勿重复提交或关闭服务器' : lastTask?.message || '任务已结束'}
      </div>
    </Card>
  ) : null;

  return (
    <div className="crud-page upgrade-page">
      <PageHeader
        title={
          <>
            <RocketOutlined />
            项目升级
          </>
        }
        desc={
          <>
            <span>统一管理版本发布、升级器状态与运行配置</span>
            <span style={{ marginLeft: 16 }}>最近检查：{status?.checkedAt || '-'}</span>
          </>
        }
        extra={
          <Space wrap>
            <Button type="primary" icon={<SyncOutlined />} loading={checking} onClick={() => loadStatus(true)}>
              检查更新
            </Button>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => loadStatus(false)}>
              刷新状态
            </Button>
          </Space>
        }
      />

      <Spin spinning={loading && !status}>
        <section className="upgrade-page__status-strip">
          <StatCard
            label="当前版本"
            value={status?.currentVersion ? `v${status.currentVersion}` : '-'}
            icon={<CheckCircleOutlined />}
            color="#16a34a"
          />
          <StatCard
            label="线上版本"
            value={
              <Space size={8} wrap>
                <span>{status?.latestVersion ? `v${status.latestVersion}` : '-'}</span>
                {status?.latestVersion && (
                  <Tag color={status.latestChannel === 'beta' ? 'purple' : 'blue'}>
                    {status.latestChannel === 'beta' ? '测试版' : '正式版'}
                  </Tag>
                )}
              </Space>
            }
            icon={<CloudDownloadOutlined />}
          />
          <StatCard
            label="升级器"
            value={
              <Space size={8} wrap>
                <span>{updaterTag.text}</span>
                <Tag color={updaterTag.color}>{updater?.version ? `v${updater.version}` : '-'}</Tag>
              </Space>
            }
            icon={<ControlOutlined />}
            color="#6366f1"
          />
          <StatCard
            label="可回退版本"
            value={rollbackCount}
            icon={<HistoryOutlined />}
            color="#d97706"
          />
        </section>

        {versionProgressPanel}

        <Row className="upgrade-page__overview-grid" gutter={[16, 16]}>
          <Col xs={24} lg={8}>
            <Card
              className="upgrade-page__status-card"
              bordered={false}
              title={
                <Space size={8}>
                  <SyncOutlined />
                  <span>更新状态</span>
                </Space>
              }
            >
              <div className="upgrade-page__notice">
                {statusNotice || <Text type="secondary">正在获取最新状态…</Text>}
              </div>
            </Card>
          </Col>
          <Col xs={24} lg={16}>
            {releaseNotesCard}
          </Col>
        </Row>

        <Card className="upgrade-page__workspace" bordered={false}>
          <Tabs
            defaultActiveKey="updater"
            items={[
              {
                key: 'updater',
                label: (
                  <Space>
                    <ControlOutlined />
                    升级器
                  </Space>
                ),
                children: updaterPanel
              },
              {
                key: 'rollback',
                label: (
                  <Space>
                    <DatabaseOutlined />
                    版本回退{rollbackCount > 0 && <Tag>{rollbackCount}</Tag>}
                  </Space>
                ),
                children: rollbackPanel
              },
              {
                key: 'settings',
                label: (
                  <Space>
                    <SettingOutlined />
                    升级配置{sourceDirty && <span className="upgrade-page__dirty-dot" />}
                  </Space>
                ),
                children: settingsPanel
              },
              {
                key: 'deployment',
                label: (
                  <Space>
                    <SettingOutlined />
                    运行配置{deploymentDirty && <span className="upgrade-page__dirty-dot" />}
                  </Space>
                ),
                children: deploymentPanel
              },
              {
                key: 'nginx',
                label: <Space><CloudServerOutlined />Nginx 网关</Space>,
                children: <NginxPanel config={deploymentConfig} ready={configProtocolReady} busy={taskBusy}
                  loading={deploymentLoading} lastTask={lastTask} onSubmitted={beginTaskPolling} onRefresh={loadDeployment} />
              },
              {
                key: 'assets',
                label: (
                  <Space>
                    <InboxOutlined />
                    官方资源
                  </Space>
                ),
                children: assetsPanel
              }
            ]}
          />
        </Card>
      </Spin>

      <Modal
        title={`确认回退到 v${selectedRollback?.version || '-'}？`}
        open={rollbackConfirmOpen}
        okText="确认回退"
        cancelText="取消"
        okButtonProps={{
          danger: true,
          disabled: !selectedRollback || rollbackConfirmText.trim() !== selectedRollback.version
        }}
        confirmLoading={rollbackSubmitting}
        onOk={handleRollbackConfirm}
        onCancel={() => {
          setRollbackConfirmOpen(false);
          setRollbackConfirmText('');
        }}
      >
        <Alert
          type="warning"
          showIcon
          message="回退会短暂停止服务"
          description="升级器会先备份程序、配置和数据库，再校验并安装目标制品。"
          className="upgrade-page__modal-alert"
        />
        <Descriptions size="small" column={1}>
          <Descriptions.Item label="目标版本">v{selectedRollback?.version || '-'}</Descriptions.Item>
          <Descriptions.Item label="数据库">
            {selectedRollback?.databaseCompatible
              ? '兼容当前结构'
              : `需要回退脚本 ${selectedRollback?.databaseRollback || '（未提供）'}`}
          </Descriptions.Item>
        </Descriptions>
        <Text>请输入目标版本号确认：</Text>
        <Input
          value={rollbackConfirmText}
          placeholder={selectedRollback?.version}
          onChange={(event) => setRollbackConfirmText(event.target.value)}
          className="upgrade-page__confirm-input"
        />
      </Modal>

      <Modal
        title={
          <Space>
            <DownloadOutlined />
            安装 / 修复升级器
          </Space>
        }
        open={installOpen}
        width={680}
        footer={
          <Space>
            <Button onClick={() => setInstallOpen(false)}>关闭</Button>
            <Button icon={<ReloadOutlined />} loading={logsLoading} onClick={() => loadUpdaterLogs(false)}>
              刷新日志
            </Button>
            <Button
              type="primary"
              icon={<SyncOutlined />}
              loading={loading}
              onClick={() => {
                Promise.all([loadStatus(false), loadUpdaterLogs()])
                  .then(() => message.success('已重新检测'))
                  .catch(() => undefined);
              }}
            >
              重新检测
            </Button>
          </Space>
        }
        onCancel={() => setInstallOpen(false)}
      >
        <Alert
          type="info"
          showIcon
          message="在服务器发布包的 deploy 目录执行修复命令"
          className="upgrade-page__modal-alert"
        />
        <Paragraph code copyable className="upgrade-page__command">
          sudo bash aid.sh setup-updater
        </Paragraph>
        <div className="upgrade-page__log-header">
          <FileTextOutlined />
          <Text strong>升级器运行日志</Text>
          {updaterLogs?.logFile && <Text type="secondary">{updaterLogs.logFile}</Text>}
        </div>
        <pre className="upgrade-page__log">
          {logsLoading
            ? '日志加载中...'
            : updaterLogs?.lines?.length
              ? updaterLogs.lines.join('\n')
              : updaterLogs?.message || '暂无升级器日志'}
        </pre>
      </Modal>
    </div>
  );
}
