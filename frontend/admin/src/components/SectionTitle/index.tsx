import React from 'react';

interface Props {
  /** 分区标题 */
  title: React.ReactNode;
  /** 标题右侧的辅助说明（灰色小字） */
  desc?: React.ReactNode;
  /** 右侧操作区 */
  extra?: React.ReactNode;
  style?: React.CSSProperties;
}

/**
 * 统一的表单/卡片分区标题：品牌色竖条 + 标题 + 可选说明，
 * 替代各页面散落的 <h4> 自定义写法
 */
export default function SectionTitle({ title, desc, extra, style }: Props) {
  return (
    <div className="form-section-title" style={style}>
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
        {title}
        {desc && <span className="form-section-title__desc">{desc}</span>}
      </span>
      {extra && <span style={{ marginLeft: 'auto' }}>{extra}</span>}
    </div>
  );
}
