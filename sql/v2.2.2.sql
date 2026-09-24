-- AID v2.2.2 图像主体检测默认配置。
-- 幂等：仅补齐缺失项，不覆盖管理员已保存的开关、凭证或取图方式。
INSERT INTO `aid_config` (`category`, `config_name`, `config_value`, `config_dict`, `del_flag`, `order_num`, `create_time`, `create_by`, `remark`) VALUES
('image_object_detection', 'enabled', 'false', '启用图像主体检测', '0', 1, NOW(), 'system', '默认关闭；平台承担供应商费用'),
('image_object_detection', 'provider', 'tencent_ci', '检测供应商', '0', 2, NOW(), 'system', '腾讯云数据万象'),
('image_object_detection', 'credentialSource', 'COS_STORAGE', '凭证来源', '0', 3, NOW(), 'system', 'COS_STORAGE 或 DEDICATED'),
('image_object_detection', 'region', '', '独立凭证地域', '0', 4, NOW(), 'system', '仅 DEDICATED 使用'),
('image_object_detection', 'bucketName', '', '独立调用桶', '0', 5, NOW(), 'system', '仅 DEDICATED 使用'),
('image_object_detection', 'secretId', '', '独立 SecretId', '0', 6, NOW(), 'system', '仅 DEDICATED 使用；真实密钥从后台配置'),
('image_object_detection', 'secretKey', '', '独立 SecretKey', '0', 7, NOW(), 'system', '仅 DEDICATED 使用；真实密钥从后台配置'),
('image_object_detection', 'cosImageAccessMode', 'COS_OBJECT', 'COS 原图取图方式', '0', 8, NOW(), 'system', 'COS_OBJECT 或 PUBLIC_URL'),
('image_object_detection', 'connectTimeoutMs', '3000', '连接超时毫秒', '0', 9, NOW(), 'system', NULL),
('image_object_detection', 'readTimeoutMs', '15000', '读取超时毫秒', '0', 10, NOW(), 'system', NULL),
('image_object_detection', 'maxCallsPerUserMinute', '10', '单用户每分钟调用上限', '0', 11, NOW(), 'system', NULL)
ON DUPLICATE KEY UPDATE `config_name` = VALUES(`config_name`);
