-- AID v2.2.1：媒体结果可保存图片尺寸、层级与位置元数据；MySQL 5.7，可重复执行。
SET @image_result_metadata_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_media_result'
           AND COLUMN_NAME = 'metadata_json'),
  'SELECT 1',
  'ALTER TABLE `aid_media_result` ADD COLUMN `metadata_json` JSON NULL COMMENT ''有序图片结果的尺寸与图层位置元数据'' AFTER `file_size`'
);
PREPARE image_result_metadata_stmt FROM @image_result_metadata_ddl;
EXECUTE image_result_metadata_stmt;
DEALLOCATE PREPARE image_result_metadata_stmt;

-- Seedream 5.0 Pro 官方图层分离能力；保持模型现有启停状态与管理员价格配置。
INSERT INTO aid_ai_model_capability
  (model_id,capability_code,generate_mode,definition_json,sort_order,create_time,create_by)
SELECT model.id,'image_layer_decomposition','image_edit',
  JSON_SET(COALESCE(source.definition_json,
    JSON_OBJECT('presentation',JSON_OBJECT(),'parameters',JSON_ARRAY(),'rules',JSON_ARRAY())),
    '$.code','image_layer_decomposition','$.label','图层分离',
    '$.generateMode','image_edit','$.defaultCapability',
      IF(source.model_id IS NULL,JSON_EXTRACT('true','$'),JSON_EXTRACT('false','$')),
    '$.enabled',JSON_EXTRACT('true','$'),'$.evidenceStatus','OFFICIAL',
    '$.sourceUrls',JSON_ARRAY('https://docs.volcengine.com/docs/ark/seedream-5-0-pro'),
    '$.rules',JSON_ARRAY(),
    '$.parameters',JSON_ARRAY(
      JSON_OBJECT('name','prompt','label','拆分要求','type','string'),
      JSON_OBJECT('name','referenceImageUrl','label','原图','type','string','materialRole','reference_image','required',true),
      JSON_OBJECT('name','size','label','输出规格','type','string','choices',JSON_ARRAY('auto','1K','1.5K','2K'),'defaultValue','1.5K'),
      JSON_OBJECT('name','expectedImageCount','label','最大预估输出张数','type','integer','defaultValue',17,'minimum',17,'maximum',17)),
    '$.presentation.supportsImageInput',JSON_EXTRACT('true','$'),
    '$.presentation.supportsMultiImageInput',JSON_EXTRACT('false','$'),
    '$.presentation.supportsAspectRatio',JSON_EXTRACT('false','$'),
    '$.presentation.defaultSizeCode','1.5K',
    '$.presentation.maxOutputCount',17,'$.presentation.defaultOutputCount',17),
  50,NOW(),'system'
FROM aid_ai_model model
LEFT JOIN aid_ai_model_capability source ON source.model_id=model.id AND source.capability_code='image_to_image'
WHERE model.model_code='doubao-seedream-5-0-pro-260628'
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model_capability existing
                  WHERE existing.model_id=model.id AND existing.capability_code='image_layer_decomposition');

INSERT INTO aid_ai_model_protocol_binding
  (model_id,capability_code,binding_code,protocol,definition_json,sort_order,create_time,create_by)
SELECT model.id,'image_layer_decomposition',COALESCE(source.binding_code,'route_layer_split'),'seedream-image',
  JSON_SET(COALESCE(source.definition_json,
    JSON_OBJECT('billingRule',JSON_OBJECT('settleRule',JSON_OBJECT(),
        'inputPricing',JSON_OBJECT('image',JSON_OBJECT())),
      'capability',JSON_OBJECT(),'presentation',JSON_OBJECT())),
    '$.code',COALESCE(source.binding_code,'route_layer_split'),
    '$.protocol','seedream-image','$.upstreamModel',model.real_model_code,
    '$.apiSuffix',model.api_suffix,'$.billingMode','SKU',
    '$.defaultBinding',JSON_EXTRACT('true','$'),'$.enabled',JSON_EXTRACT('true','$'),
    '$.billingRule.mode','SKU','$.billingRule.meterType','PER_IMAGE',
    '$.billingRule.chargeType','IMAGE','$.billingRule.preHold',JSON_EXTRACT('true','$'),
    '$.billingRule.matchStrategy','FIRST_HIT',
    '$.billingRule.settleRule.settleMode','REFUND_ONLY',
    '$.billingRule.settleRule.allowRefund',JSON_EXTRACT('true','$'),
    '$.billingRule.settleRule.allowExtraCharge',JSON_EXTRACT('false','$'),
    '$.billingRule.settleRule.usageSource','PROVIDER_USAGE',
    '$.billingRule.skus',JSON_ARRAY(JSON_OBJECT('skuCode','SEEDREAM50_LAYER_MAX',
      'skuName','图层分离最大单张价','enabled',true,'priority',1,'match',JSON_OBJECT(),
      'price',0.30)),
    '$.billingRule.params',JSON_ARRAY(),
    '$.billingRule.inputPricing.image.freeCount',1,
    '$.billingRule.inputPricing.image.maxCount',1,
    '$.billingRule.settleRule.imageOutputPixelTiers',JSON_ARRAY(
      JSON_OBJECT('maxPixels',2610000,'price',0.15),
      JSON_OBJECT('maxPixels',NULL,'price',0.30)),
    '$.capability.defaultSize','1.5K',
    '$.capability.sizeOptions',JSON_ARRAY('auto','1K','1.5K','2K'),
    '$.capability.maxReferenceImages',1,'$.capability.minReferenceImages',1,
    '$.capability.supportsSizePreset',JSON_EXTRACT('true','$'),
    '$.capability.supportsAspectRatio',JSON_EXTRACT('false','$'),
    '$.capability.aspectRatioOptions',JSON_ARRAY(),
    '$.capability.sceneRules.imageToImage',JSON_OBJECT('supportsSizePreset',true,'supportsAspectRatio',false),
    '$.presentation.maxOutputCount',17,'$.presentation.defaultOutputCount',17,
    '$.presentation.supportsMultiImageInput',JSON_EXTRACT('false','$'),
    '$.presentation.supportsSizePreset',JSON_EXTRACT('true','$'),
    '$.presentation.supportsAspectRatio',JSON_EXTRACT('false','$'),
    '$.presentation.defaultSizeCode','1.5K'),
  50,NOW(),'system'
FROM aid_ai_model model
LEFT JOIN aid_ai_model_protocol_binding source ON source.model_id=model.id
  AND source.capability_code='image_to_image' AND source.protocol='seedream-image'
WHERE model.model_code='doubao-seedream-5-0-pro-260628'
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model_protocol_binding existing
                  WHERE existing.model_id=model.id
                    AND existing.capability_code='image_layer_decomposition'
                    AND existing.binding_code=COALESCE(source.binding_code,'route_layer_split'));
