import React from 'react';
import * as AntIcons from '@ant-design/icons';
import svgIconIds from 'virtual:svg-icons-names';
import SvgIcon from './SvgIcon';

interface Props {
  icon?: string;
  className?: string;
}

// 后端常用的 icon 名 → antd Icon 组件映射（尽量覆盖全部用到的名字，且各自映射到不同图标，避免重复/缺图标）
const fallbackIconMap: Record<string, string> = {
  // 通用 / 系统
  dashboard: 'DashboardOutlined',
  system: 'SettingOutlined',
  config: 'ControlOutlined',
  tool: 'ToolOutlined',
  menu: 'MenuOutlined',
  tree: 'ApartmentOutlined',
  'tree-table': 'ApartmentOutlined',
  cascader: 'PartitionOutlined',
  build: 'BuildOutlined',
  table: 'TableOutlined',
  list: 'UnorderedListOutlined',
  form: 'FormOutlined',
  edit: 'EditOutlined',
  dict: 'BookOutlined',
  clipboard: 'SnippetsOutlined',
  documentation: 'ReadOutlined',
  // 用户 / 角色
  user: 'UserOutlined',
  users: 'TeamOutlined',
  peoples: 'TeamOutlined',
  people: 'UserOutlined',
  profile: 'IdcardOutlined',
  role: 'SafetyOutlined',
  skill: 'BulbOutlined',
  // 监控 / 运维
  monitor: 'DesktopOutlined',
  server: 'CloudServerOutlined',
  druid: 'DatabaseOutlined',
  cache: 'ThunderboltOutlined',
  online: 'WifiOutlined',
  job: 'FieldTimeOutlined',
  log: 'FileTextOutlined',
  logininfor: 'LoginOutlined',
  bug: 'BugOutlined',
  swagger: 'ApiOutlined',
  // 内容 / 运营
  notice: 'NotificationOutlined',
  message: 'MessageOutlined',
  chat: 'MessageOutlined',
  question: 'QuestionCircleOutlined',
  international: 'GlobalOutlined',
  guide: 'CompassOutlined',
  star: 'StarOutlined',
  // 媒体
  video: 'VideoCameraOutlined',
  audio: 'SoundOutlined',
  sound: 'SoundOutlined',
  voice: 'AudioOutlined',
  mic: 'AudioOutlined',
  image: 'PictureOutlined',
  tag: 'TagOutlined',
  // AI / 模型
  ai: 'RobotOutlined',
  component: 'AppstoreOutlined',
  model: 'AppstoreOutlined',
  chart: 'LineChartOutlined',
  // 财务
  pay: 'CreditCardOutlined',
  money: 'WalletOutlined',
  shopping: 'ShoppingCartOutlined',
  number: 'NumberOutlined',
  'eye-open': 'EyeOutlined',
  eye: 'EyeOutlined'
};

const svgIconNames = new Set(svgIconIds.map((id) => id.replace(/^icon-/, '')));

function renderAntIcon(name: string | undefined, className?: string) {
  if (!name || !/(?:Outlined|Filled|TwoTone)$/.test(name)) return null;
  const IconCmp = (AntIcons as any)[name];
  return IconCmp ? <IconCmp className={className} /> : null;
}

export default function MenuIcon({ icon, className }: Props) {
  // 未配置图标的动态菜单也必须在折叠栏保留可识别、可点击的入口，
  // 否则 antd 会退化为截取标题首字显示。
  if (!icon || !icon.trim() || icon === '#') {
    return <AntIcons.AppstoreOutlined className={className} />;
  }
  const iconName = icon.trim();

  const mappedIcon = renderAntIcon(fallbackIconMap[iconName.toLowerCase()], className);
  if (mappedIcon) return mappedIcon;

  const directAntIcon = renderAntIcon(iconName, className);
  if (directAntIcon) return directAntIcon;

  if (
    iconName.startsWith('http://') ||
    iconName.startsWith('https://') ||
    iconName.startsWith('#') ||
    svgIconNames.has(iconName)
  ) {
    return <SvgIcon icon={iconName} className={className} size={16} />;
  }

  const DefaultIcon = AntIcons.AppstoreOutlined;
  return <DefaultIcon className={className} />;
}
