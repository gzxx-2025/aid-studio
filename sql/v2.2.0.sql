-- AID v2.2.0：图片代理、图片编辑结果与错误诊断；MySQL 5.7，可重复执行。
SET NAMES utf8mb4;

-- 仅修正旧版错误的交流二维码默认路径；自定义地址不变。
UPDATE `aid_config`
SET `config_value` = '/profile/aid/2026/07/21/adc6942b6f0c4ffcb663961cb63e9adf.jpg'
WHERE `category` = 'basic'
  AND `config_name` = 'exchange_image_url'
  AND `config_value` = '/aid/2026/07/21/adc6942b6f0c4ffcb663961cb63e9adf.jpg';

SET @ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_ai_model'
     AND COLUMN_NAME = 'image_url_proxy_enabled') = 0,
  'ALTER TABLE `aid_ai_model` ADD COLUMN `image_url_proxy_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT ''模型输入图片是否使用代理模板'' AFTER `is_free`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_ai_model'
     AND COLUMN_NAME = 'image_url_proxy_template') = 0,
  'ALTER TABLE `aid_ai_model` ADD COLUMN `image_url_proxy_template` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''图片代理URL模板，使用{url}占位'' AFTER `image_url_proxy_enabled`',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- 图片编辑工具业务目录：MySQL 5.7；可重复执行，不覆盖管理员配置。
-- 本段仅补充业务目录数据。模型能力、协议、价格和业务绑定由模型管理维护。
SET NAMES utf8mb4;

INSERT INTO aid_ai_model_func_config
  (func_name, func_code, model_type, generate_mode, model_ids, status, del_flag,
   create_by, create_time, update_by, update_time, remark)
SELECT seed.func_name, seed.func_code, 'image', 'image_edit', JSON_ARRAY(), '0', '0',
       'system', NOW(), 'system', NOW(), '候选模型与能力在模型池配置；不改变模型本身能力或价格'
FROM (
  SELECT '图片重绘' AS func_name, 'canvas.image.redraw' AS func_code
  UNION ALL SELECT '物体擦除', 'canvas.image.erase'
  UNION ALL SELECT '图片扩图', 'canvas.image.expand'
  UNION ALL SELECT '修改打光', 'canvas.image.relight'
) seed
WHERE NOT EXISTS (
  SELECT 1 FROM aid_ai_model_func_config existing WHERE existing.func_code = seed.func_code
);

-- 有序媒体结果：保留历史记录ID和URL，不另建任务或结果表。
-- 升级期间暂停媒体任务写入；已有同名唯一索引时不重排历史序号。
SET @image_edit_schema = DATABASE();
SET @image_edit_column_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@image_edit_schema AND TABLE_NAME='aid_media_result' AND COLUMN_NAME='result_index'
);
SET @image_edit_ddl = IF(@image_edit_column_exists=0,
  'ALTER TABLE `aid_media_result` ADD COLUMN `result_index` int(11) NOT NULL DEFAULT 0 COMMENT ''同一任务内的结果序号'' AFTER `task_id`',
  'SELECT 1');
PREPARE image_edit_stmt FROM @image_edit_ddl;
EXECUTE image_edit_stmt;
DEALLOCATE PREPARE image_edit_stmt;

SET @image_edit_index_exists = (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=@image_edit_schema AND TABLE_NAME='aid_media_result' AND INDEX_NAME='uk_media_result_task_index'
);
-- 按同任务主键顺序从0编号，兼容MySQL 5.7；只重排尚未建立唯一约束的历史库。
SET @image_edit_ddl = IF(@image_edit_index_exists=0,
  'UPDATE `aid_media_result` result JOIN (SELECT ordered.id,ordered.result_index FROM (SELECT current_result.id,COUNT(previous_result.id)-1 result_index FROM `aid_media_result` current_result JOIN `aid_media_result` previous_result ON previous_result.task_id=current_result.task_id AND previous_result.id<=current_result.id GROUP BY current_result.id) ordered) migration ON migration.id=result.id SET result.result_index=migration.result_index',
  'SELECT 1');
PREPARE image_edit_stmt FROM @image_edit_ddl;
EXECUTE image_edit_stmt;
DEALLOCATE PREPARE image_edit_stmt;

SET @image_edit_ddl = IF(@image_edit_index_exists=0,
  'ALTER TABLE `aid_media_result` ADD UNIQUE INDEX `uk_media_result_task_index` (`task_id`,`result_index`)',
  'SELECT 1');
PREPARE image_edit_stmt FROM @image_edit_ddl;
EXECUTE image_edit_stmt;
DEALLOCATE PREPARE image_edit_stmt;

-- 完整错误诊断：默认关闭；配置仅由错误处理页面维护。
CREATE TABLE IF NOT EXISTS aid_diagnostic_event (
 event_id varchar(36) NOT NULL, request_id varchar(36) NOT NULL, correlation_key varchar(64) NULL,
 request_time varchar(40) NOT NULL, user_id bigint NULL,
 source varchar(20) NOT NULL, diagnostic_json longtext NOT NULL,
 report_id varchar(36) NULL, create_time datetime NOT NULL,
 create_by varchar(64) NOT NULL DEFAULT 'system',
 PRIMARY KEY(event_id), UNIQUE KEY uk_diagnostic_correlation(correlation_key), KEY idx_diagnostic_time(create_time),
 KEY idx_diagnostic_request_user(request_id,user_id), KEY idx_diagnostic_report(report_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='完整错误诊断事件';
CREATE TABLE IF NOT EXISTS aid_diagnostic_delivery (
 report_id varchar(36) NOT NULL, event_id varchar(36) NOT NULL,
 protected_options longtext NULL, envelope_json longtext NULL, protected_receipt text NULL, status varchar(20) NOT NULL,
 result_message varchar(255) NULL, create_time datetime NOT NULL,
 create_by varchar(64) NOT NULL DEFAULT 'system', update_time datetime NOT NULL,
 update_by varchar(64) NOT NULL DEFAULT 'system',
 PRIMARY KEY(report_id), UNIQUE KEY uk_diagnostic_event(event_id),
 KEY idx_diagnostic_delivery(status,update_time), KEY idx_diagnostic_delivery_time(create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='诊断异步发送任务';
CREATE TABLE IF NOT EXISTS aid_diagnostic_trace (
 task_key varchar(64) NOT NULL, request_id varchar(36) NOT NULL,
 request_json longtext NOT NULL, create_time datetime NOT NULL,
 create_by varchar(64) NOT NULL DEFAULT 'system',
 PRIMARY KEY(task_key), KEY idx_diagnostic_trace_time(create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异步任务诊断提交快照';
INSERT INTO aid_config(category,config_name,config_value,config_dict,del_flag,order_num,create_time,create_by)
SELECT 'error_diagnostics','protected_settings','','由错误处理页面专用接口维护','0',0,NOW(),'system'
WHERE NOT EXISTS(SELECT 1 FROM aid_config WHERE category='error_diagnostics' AND config_name='protected_settings');
INSERT INTO aid_config(category,config_name,config_value,config_dict,del_flag,order_num,create_time,create_by)
SELECT 'error_diagnostics','queue_lock','','诊断发送队列事务锁','0',1,NOW(),'system'
WHERE NOT EXISTS(SELECT 1 FROM aid_config WHERE category='error_diagnostics' AND config_name='queue_lock');
SET @diagnostic_parent=COALESCE((SELECT menu_id FROM sys_menu WHERE path='system' AND menu_type='M' ORDER BY menu_id LIMIT 1),0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '错误处理',@diagnostic_parent,20,'error-handling','aid/error-handling/index',1,1,'C','0','0','aid:diagnostics:view','bug','system',NOW()
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aid:diagnostics:view');
SET @diagnostic_menu=(SELECT menu_id FROM sys_menu WHERE perms='aid:diagnostics:view' ORDER BY menu_id LIMIT 1);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '管理错误采集',@diagnostic_menu,1,'#',1,1,'F','0','0','aid:diagnostics:manage','#','system',NOW()
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aid:diagnostics:manage');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '发送错误报告',@diagnostic_menu,2,'#',1,1,'F','0','0','aid:diagnostics:send','#','system',NOW()
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='aid:diagnostics:send');
