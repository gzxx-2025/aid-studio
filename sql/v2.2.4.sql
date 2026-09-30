-- AID v2.2.4 incremental upgrade; MySQL 5.7, utf8mb4, repeatable.
-- Published historical scripts remain byte-for-byte unchanged.
-- Backup first and pause generation writes while switching application versions.
-- DDL is an explicit boundary; each following DML group commits atomically.
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS `aid_schema_history` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `script_name` varchar(255) NOT NULL,
  `checksum` char(64) NOT NULL,
  `status` varchar(16) NOT NULL,
  `error_message` varchar(500) DEFAULT NULL,
  `executed_at` datetime NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_script_name` (`script_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=Dynamic;
START TRANSACTION;

-- Official MiniMax H3 Max output and reference-media prices (RMB).
-- https://platform.minimax.cn/docs/guides/pricing-paygo
-- Only complete missing price rules. Do not alter saved operator prices or model enablement.
UPDATE aid_ai_model AS m
JOIN aid_ai_provider AS p ON p.id = m.provider_id AND p.provider_code = 'minimax_h3'
SET m.billing_mode = 'SKU',
    m.billing_rule_json = JSON_OBJECT(
      'mode', 'SKU', 'meterType', 'PER_SECOND', 'chargeType', 'VIDEO',
      'preHold', TRUE, 'matchStrategy', 'FIRST_HIT',
      'params', JSON_ARRAY(
        JSON_OBJECT('code', 'resolution', 'name', '分辨率', 'type', 'ENUM',
                    'options', JSON_ARRAY('480P', '768P'), 'required', TRUE),
        JSON_OBJECT('code', 'duration', 'name', '时长', 'type', 'NUMBER',
                    'unit', '秒', 'required', TRUE)),
      'skus', JSON_ARRAY(
        JSON_OBJECT('skuCode', 'MINIMAX_H3_MAX_480P', 'skuName', 'MiniMax H3 Max 480P',
                    'priority', 10, 'enabled', TRUE, 'match', JSON_OBJECT('resolution', '480P'),
                    'price', 1.65, 'pricePerSecond', 0.33,
                    'inputPricing', JSON_OBJECT(
                      'image', JSON_OBJECT('unitPrice', 0.50, 'freeCount', 2),
                      'video', JSON_OBJECT('unitPrice', 0.37, 'maxSeconds', 15))),
        JSON_OBJECT('skuCode', 'MINIMAX_H3_MAX_768P', 'skuName', 'MiniMax H3 Max 768P',
                    'priority', 20, 'enabled', TRUE, 'match', JSON_OBJECT('resolution', '768P'),
                    'price', 2.50, 'pricePerSecond', 0.50,
                    'inputPricing', JSON_OBJECT(
                      'image', JSON_OBJECT('unitPrice', 0.50, 'freeCount', 2),
                      'video', JSON_OBJECT('unitPrice', 0.97, 'maxSeconds', 15)))),
      'settleRule', JSON_OBJECT('settleMode', 'REFUND_ONLY',
                               'usageSource', 'PROVIDER_USAGE',
                               'allowRefund', TRUE, 'allowExtraCharge', FALSE)),
    m.billing_version = COALESCE(m.billing_version, 0) + 1,
    m.config_version = COALESCE(m.config_version, 0) + 1,
    m.update_time = NOW(),
    m.update_by = 'system',
    m.official_price_url = 'https://platform.minimax.cn/docs/guides/pricing-paygo'
WHERE m.model_code = 'minimax-h3-max-official'
  AND m.del_flag = '0'
  AND (m.billing_rule_json IS NULL OR JSON_VALID(m.billing_rule_json) = 0
       OR COALESCE(JSON_LENGTH(JSON_EXTRACT(m.billing_rule_json, '$.skus')), 0) = 0);

-- A repeated historical upgrade could add a second system-generated route to
-- Seedream layer decomposition after its image_to_image source route appears.
-- Retain the original layer route and never remove a route referenced by an alias.
DELETE extra
FROM aid_ai_model_protocol_binding AS extra
JOIN aid_ai_model AS m ON m.id = extra.model_id
JOIN aid_ai_model_protocol_binding AS retained
  ON retained.model_id = extra.model_id
 AND retained.capability_code = extra.capability_code
 AND retained.binding_code = 'route_layer_split'
WHERE m.model_code = 'doubao-seedream-5-0-pro-260628'
  AND extra.capability_code = 'image_layer_decomposition'
  AND extra.binding_code = 'route_8'
  AND extra.create_by = 'system'
  AND NOT EXISTS (
    SELECT 1 FROM aid_ai_model_alias AS alias_row
    WHERE alias_row.model_id = extra.model_id
      AND alias_row.capability_code = extra.capability_code
      AND alias_row.binding_code = extra.binding_code
  );

-- OpenAI GPT-6 / GPT-5.6 official catalog and missing model-price repair (2026-09-29)

-- USD official Standard tier prices use the existing catalog baseline of CNY 7.00/USD.

-- New models stay disabled until the operator verifies provider access; existing credentials are reused.

INSERT INTO aid_ai_model
  (provider_id,model_code,real_model_code,model_name,model_type,generate_mode,
   api_suffix,protocol,status,del_flag,create_time,create_by,remark,billing_mode,
   billing_rule_json,billing_version,supports_text_input,supports_system_prompt,
   supports_image_input,supports_multi_image_input,max_output_count,default_output_count,
   capability_json,capability_inited,extra_body,official_price_url,is_free,config_version)
SELECT p.id,'gpt-6-astra','gpt-6-astra','GPT-6 Astra','text','text',
       '/v1/chat/completions','openai-compatible-text','1','0',NOW(),'system',
       'Official text-and-image-input model; enable after access verification.',
       'SKU','{"mode":"SKU","meterType":"TOKEN","chargeType":"TEXT","preHold":true,"matchStrategy":"FIRST_HIT","params":[],"skus":[{"match":{"inputTokensMin":0,"inputTokensMax":272000},"enabled":true,"skuCode":"OPENAI_GPT_6_ASTRA_STD","skuName":"gpt-6-astra std context","priority":1,"inputPricePerMillion":70,"cachedInputPricePerMillion":7,"cacheWritePricePerMillion":87.5,"outputPricePerMillion":350,"reasoningPricePerMillion":350},{"match":{"inputTokensMin":272001,"inputTokensMax":100000000},"enabled":true,"skuCode":"OPENAI_GPT_6_ASTRA_LONG","skuName":"gpt-6-astra long context","priority":2,"inputPricePerMillion":140,"cachedInputPricePerMillion":14,"cacheWritePricePerMillion":175.0,"outputPricePerMillion":525.0,"reasoningPricePerMillion":525.0}],"settleRule":{"settleMode":"REFUND_ONLY","allowRefund":true,"allowExtraCharge":false,"usageSource":"PROVIDER_USAGE","charToTokenRatio":2,"usagePricingMode":"BUCKETED"}}',1,1,1,1,1,1,1,'{"sceneRules":{"textOnly":{"supportsDuration":false,"supportsSizePreset":false,"supportsAspectRatio":false}},"inputModalities":["TEXT","IMAGE"],"outputModalities":["TEXT"],"maxInputImages":10,"maxInputAudios":0,"maxInputVideos":0,"maxInputDocuments":0,"inputImageFormats":["jpeg","jpg","png","webp","gif"],"supportsImageInput":true,"supportsAudioInput":false,"supportsVideoInput":false,"supportsDocumentInput":false,"supportsJsonObject":true,"supportsReasoning":true,"supportsReasoningDisable":false,"reasoningApiStyle":"OPENAI","outputTokenApiField":"max_completion_tokens","allowedReasoningLevels":["low","medium","high","xhigh","max"],"defaultReasoningLevel":"medium","contextWindowTokens":1050000,"maxOutputTokens":128000,"capabilitySourceUrls":["https://developers.openai.com/api/docs/models/gpt-6-astra"],"capabilityVerifiedAt":"2026-09-29"}',1,JSON_OBJECT(),'https://developers.openai.com/api/docs/models/gpt-6-astra',0,1
FROM aid_ai_provider p
WHERE p.provider_code='openai' AND p.del_flag='0'
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model m WHERE m.provider_id=p.id AND m.model_code='gpt-6-astra' AND m.del_flag='0');

INSERT INTO aid_ai_model
  (provider_id,model_code,real_model_code,model_name,model_type,generate_mode,
   api_suffix,protocol,status,del_flag,create_time,create_by,remark,billing_mode,
   billing_rule_json,billing_version,supports_text_input,supports_system_prompt,
   supports_image_input,supports_multi_image_input,max_output_count,default_output_count,
   capability_json,capability_inited,extra_body,official_price_url,is_free,config_version)
SELECT p.id,'gpt-6-sol','gpt-6-sol','GPT-6 Sol','text','text',
       '/v1/chat/completions','openai-compatible-text','1','0',NOW(),'system',
       'Official text-and-image-input model; enable after access verification.',
       'SKU','{"mode":"SKU","meterType":"TOKEN","chargeType":"TEXT","preHold":true,"matchStrategy":"FIRST_HIT","params":[],"skus":[{"match":{"inputTokensMin":0,"inputTokensMax":272000},"enabled":true,"skuCode":"OPENAI_GPT_6_SOL_STD","skuName":"gpt-6-sol std context","priority":1,"inputPricePerMillion":14,"cachedInputPricePerMillion":1.4,"cacheWritePricePerMillion":17.5,"outputPricePerMillion":70,"reasoningPricePerMillion":70},{"match":{"inputTokensMin":272001,"inputTokensMax":100000000},"enabled":true,"skuCode":"OPENAI_GPT_6_SOL_LONG","skuName":"gpt-6-sol long context","priority":2,"inputPricePerMillion":28,"cachedInputPricePerMillion":2.8,"cacheWritePricePerMillion":35.0,"outputPricePerMillion":105.0,"reasoningPricePerMillion":105.0}],"settleRule":{"settleMode":"REFUND_ONLY","allowRefund":true,"allowExtraCharge":false,"usageSource":"PROVIDER_USAGE","charToTokenRatio":2,"usagePricingMode":"BUCKETED"}}',1,1,1,1,1,1,1,'{"sceneRules":{"textOnly":{"supportsDuration":false,"supportsSizePreset":false,"supportsAspectRatio":false}},"inputModalities":["TEXT","IMAGE"],"outputModalities":["TEXT"],"maxInputImages":10,"maxInputAudios":0,"maxInputVideos":0,"maxInputDocuments":0,"inputImageFormats":["jpeg","jpg","png","webp","gif"],"supportsImageInput":true,"supportsAudioInput":false,"supportsVideoInput":false,"supportsDocumentInput":false,"supportsJsonObject":true,"supportsReasoning":true,"supportsReasoningDisable":true,"reasoningApiStyle":"OPENAI","outputTokenApiField":"max_completion_tokens","allowedReasoningLevels":["none","low","medium","high","xhigh","max"],"defaultReasoningLevel":"medium","contextWindowTokens":1050000,"maxOutputTokens":128000,"capabilitySourceUrls":["https://developers.openai.com/api/docs/models/gpt-6-sol"],"capabilityVerifiedAt":"2026-09-29"}',1,JSON_OBJECT(),'https://developers.openai.com/api/docs/models/gpt-6-sol',0,1
FROM aid_ai_provider p
WHERE p.provider_code='openai' AND p.del_flag='0'
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model m WHERE m.provider_id=p.id AND m.model_code='gpt-6-sol' AND m.del_flag='0');

INSERT INTO aid_ai_model
  (provider_id,model_code,real_model_code,model_name,model_type,generate_mode,
   api_suffix,protocol,status,del_flag,create_time,create_by,remark,billing_mode,
   billing_rule_json,billing_version,supports_text_input,supports_system_prompt,
   supports_image_input,supports_multi_image_input,max_output_count,default_output_count,
   capability_json,capability_inited,extra_body,official_price_url,is_free,config_version)
SELECT p.id,'gpt-6-luna','gpt-6-luna','GPT-6 Luna','text','text',
       '/v1/chat/completions','openai-compatible-text','1','0',NOW(),'system',
       'Official text-and-image-input model; enable after access verification.',
       'SKU','{"mode":"SKU","meterType":"TOKEN","chargeType":"TEXT","preHold":true,"matchStrategy":"FIRST_HIT","params":[],"skus":[{"match":{"inputTokensMin":0,"inputTokensMax":272000},"enabled":true,"skuCode":"OPENAI_GPT_6_LUNA_STD","skuName":"gpt-6-luna std context","priority":1,"inputPricePerMillion":0.7,"cachedInputPricePerMillion":0.07,"cacheWritePricePerMillion":0.875,"outputPricePerMillion":3.5,"reasoningPricePerMillion":3.5},{"match":{"inputTokensMin":272001,"inputTokensMax":100000000},"enabled":true,"skuCode":"OPENAI_GPT_6_LUNA_LONG","skuName":"gpt-6-luna long context","priority":2,"inputPricePerMillion":1.4,"cachedInputPricePerMillion":0.14,"cacheWritePricePerMillion":1.75,"outputPricePerMillion":5.25,"reasoningPricePerMillion":5.25}],"settleRule":{"settleMode":"REFUND_ONLY","allowRefund":true,"allowExtraCharge":false,"usageSource":"PROVIDER_USAGE","charToTokenRatio":2,"usagePricingMode":"BUCKETED"}}',1,1,1,1,1,1,1,'{"sceneRules":{"textOnly":{"supportsDuration":false,"supportsSizePreset":false,"supportsAspectRatio":false}},"inputModalities":["TEXT","IMAGE"],"outputModalities":["TEXT"],"maxInputImages":10,"maxInputAudios":0,"maxInputVideos":0,"maxInputDocuments":0,"inputImageFormats":["jpeg","jpg","png","webp","gif"],"supportsImageInput":true,"supportsAudioInput":false,"supportsVideoInput":false,"supportsDocumentInput":false,"supportsJsonObject":true,"supportsReasoning":true,"supportsReasoningDisable":true,"reasoningApiStyle":"OPENAI","outputTokenApiField":"max_completion_tokens","allowedReasoningLevels":["none","low","medium","high","xhigh","max"],"defaultReasoningLevel":"medium","contextWindowTokens":1050000,"maxOutputTokens":128000,"capabilitySourceUrls":["https://developers.openai.com/api/docs/models/gpt-6-luna"],"capabilityVerifiedAt":"2026-09-29"}',1,JSON_OBJECT(),'https://developers.openai.com/api/docs/models/gpt-6-luna',0,1
FROM aid_ai_provider p
WHERE p.provider_code='openai' AND p.del_flag='0'
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model m WHERE m.provider_id=p.id AND m.model_code='gpt-6-luna' AND m.del_flag='0');

INSERT INTO aid_ai_model
  (provider_id,model_code,real_model_code,model_name,model_type,generate_mode,
   api_suffix,protocol,status,del_flag,create_time,create_by,remark,billing_mode,
   billing_rule_json,billing_version,supports_text_input,supports_system_prompt,
   supports_image_input,supports_multi_image_input,max_output_count,default_output_count,
   capability_json,capability_inited,extra_body,official_price_url,is_free,config_version)
SELECT p.id,'gpt-5.6-sol','gpt-5.6-sol','GPT-5.6 Sol','text','text',
       '/v1/chat/completions','openai-compatible-text','1','0',NOW(),'system',
       'Official text-and-image-input model; enable after access verification.',
       'SKU','{"mode":"SKU","meterType":"TOKEN","chargeType":"TEXT","preHold":true,"matchStrategy":"FIRST_HIT","params":[],"skus":[{"match":{"inputTokensMin":0,"inputTokensMax":272000},"enabled":true,"skuCode":"OPENAI_GPT_56_SOL_STD","skuName":"gpt-5.6-sol std context","priority":1,"inputPricePerMillion":28,"cachedInputPricePerMillion":2.8,"cacheWritePricePerMillion":35,"outputPricePerMillion":140,"reasoningPricePerMillion":140},{"match":{"inputTokensMin":272001,"inputTokensMax":100000000},"enabled":true,"skuCode":"OPENAI_GPT_56_SOL_LONG","skuName":"gpt-5.6-sol long context","priority":2,"inputPricePerMillion":56,"cachedInputPricePerMillion":5.6,"cacheWritePricePerMillion":70,"outputPricePerMillion":210.0,"reasoningPricePerMillion":210.0}],"settleRule":{"settleMode":"REFUND_ONLY","allowRefund":true,"allowExtraCharge":false,"usageSource":"PROVIDER_USAGE","charToTokenRatio":2,"usagePricingMode":"BUCKETED"}}',1,1,1,1,1,1,1,'{"sceneRules":{"textOnly":{"supportsDuration":false,"supportsSizePreset":false,"supportsAspectRatio":false}},"inputModalities":["TEXT","IMAGE"],"outputModalities":["TEXT"],"maxInputImages":10,"maxInputAudios":0,"maxInputVideos":0,"maxInputDocuments":0,"inputImageFormats":["jpeg","jpg","png","webp","gif"],"supportsImageInput":true,"supportsAudioInput":false,"supportsVideoInput":false,"supportsDocumentInput":false,"supportsJsonObject":true,"supportsReasoning":true,"supportsReasoningDisable":true,"reasoningApiStyle":"OPENAI","outputTokenApiField":"max_completion_tokens","allowedReasoningLevels":["none","low","medium","high","xhigh","max"],"defaultReasoningLevel":"medium","contextWindowTokens":1050000,"maxOutputTokens":128000,"capabilitySourceUrls":["https://developers.openai.com/api/docs/models/gpt-5.6-sol"],"capabilityVerifiedAt":"2026-09-29"}',1,JSON_OBJECT(),'https://developers.openai.com/api/docs/models/gpt-5.6-sol',0,1
FROM aid_ai_provider p
WHERE p.provider_code='openai' AND p.del_flag='0'
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model m WHERE m.provider_id=p.id AND m.model_code='gpt-5.6-sol' AND m.del_flag='0');

INSERT INTO aid_ai_model
  (provider_id,model_code,real_model_code,model_name,model_type,generate_mode,
   api_suffix,protocol,status,del_flag,create_time,create_by,remark,billing_mode,
   billing_rule_json,billing_version,supports_text_input,supports_system_prompt,
   supports_image_input,supports_multi_image_input,max_output_count,default_output_count,
   capability_json,capability_inited,extra_body,official_price_url,is_free,config_version)
SELECT p.id,'gpt-5.6-terra','gpt-5.6-terra','GPT-5.6 Terra','text','text',
       '/v1/chat/completions','openai-compatible-text','1','0',NOW(),'system',
       'Official text-and-image-input model; enable after access verification.',
       'SKU','{"mode":"SKU","meterType":"TOKEN","chargeType":"TEXT","preHold":true,"matchStrategy":"FIRST_HIT","params":[],"skus":[{"match":{"inputTokensMin":0,"inputTokensMax":272000},"enabled":true,"skuCode":"OPENAI_GPT_56_TERRA_STD","skuName":"gpt-5.6-terra std context","priority":1,"inputPricePerMillion":14,"cachedInputPricePerMillion":1.4,"cacheWritePricePerMillion":17.5,"outputPricePerMillion":84,"reasoningPricePerMillion":84},{"match":{"inputTokensMin":272001,"inputTokensMax":100000000},"enabled":true,"skuCode":"OPENAI_GPT_56_TERRA_LONG","skuName":"gpt-5.6-terra long context","priority":2,"inputPricePerMillion":28,"cachedInputPricePerMillion":2.8,"cacheWritePricePerMillion":35.0,"outputPricePerMillion":126.0,"reasoningPricePerMillion":126.0}],"settleRule":{"settleMode":"REFUND_ONLY","allowRefund":true,"allowExtraCharge":false,"usageSource":"PROVIDER_USAGE","charToTokenRatio":2,"usagePricingMode":"BUCKETED"}}',1,1,1,1,1,1,1,'{"sceneRules":{"textOnly":{"supportsDuration":false,"supportsSizePreset":false,"supportsAspectRatio":false}},"inputModalities":["TEXT","IMAGE"],"outputModalities":["TEXT"],"maxInputImages":10,"maxInputAudios":0,"maxInputVideos":0,"maxInputDocuments":0,"inputImageFormats":["jpeg","jpg","png","webp","gif"],"supportsImageInput":true,"supportsAudioInput":false,"supportsVideoInput":false,"supportsDocumentInput":false,"supportsJsonObject":true,"supportsReasoning":true,"supportsReasoningDisable":true,"reasoningApiStyle":"OPENAI","outputTokenApiField":"max_completion_tokens","allowedReasoningLevels":["none","low","medium","high","xhigh","max"],"defaultReasoningLevel":"medium","contextWindowTokens":1050000,"maxOutputTokens":128000,"capabilitySourceUrls":["https://developers.openai.com/api/docs/models/gpt-5.6-terra"],"capabilityVerifiedAt":"2026-09-29"}',1,JSON_OBJECT(),'https://developers.openai.com/api/docs/models/gpt-5.6-terra',0,1
FROM aid_ai_provider p
WHERE p.provider_code='openai' AND p.del_flag='0'
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model m WHERE m.provider_id=p.id AND m.model_code='gpt-5.6-terra' AND m.del_flag='0');

INSERT INTO aid_ai_model
  (provider_id,model_code,real_model_code,model_name,model_type,generate_mode,
   api_suffix,protocol,status,del_flag,create_time,create_by,remark,billing_mode,
   billing_rule_json,billing_version,supports_text_input,supports_system_prompt,
   supports_image_input,supports_multi_image_input,max_output_count,default_output_count,
   capability_json,capability_inited,extra_body,official_price_url,is_free,config_version)
SELECT p.id,'gpt-5.6-luna','gpt-5.6-luna','GPT-5.6 Luna','text','text',
       '/v1/chat/completions','openai-compatible-text','1','0',NOW(),'system',
       'Official text-and-image-input model; enable after access verification.',
       'SKU','{"mode":"SKU","meterType":"TOKEN","chargeType":"TEXT","preHold":true,"matchStrategy":"FIRST_HIT","params":[],"skus":[{"match":{"inputTokensMin":0,"inputTokensMax":272000},"enabled":true,"skuCode":"OPENAI_GPT_56_LUNA_STD","skuName":"gpt-5.6-luna std context","priority":1,"inputPricePerMillion":1.4,"cachedInputPricePerMillion":0.14,"cacheWritePricePerMillion":1.75,"outputPricePerMillion":8.4,"reasoningPricePerMillion":8.4},{"match":{"inputTokensMin":272001,"inputTokensMax":100000000},"enabled":true,"skuCode":"OPENAI_GPT_56_LUNA_LONG","skuName":"gpt-5.6-luna long context","priority":2,"inputPricePerMillion":2.8,"cachedInputPricePerMillion":0.28,"cacheWritePricePerMillion":3.5,"outputPricePerMillion":12.6,"reasoningPricePerMillion":12.6}],"settleRule":{"settleMode":"REFUND_ONLY","allowRefund":true,"allowExtraCharge":false,"usageSource":"PROVIDER_USAGE","charToTokenRatio":2,"usagePricingMode":"BUCKETED"}}',1,1,1,1,1,1,1,'{"sceneRules":{"textOnly":{"supportsDuration":false,"supportsSizePreset":false,"supportsAspectRatio":false}},"inputModalities":["TEXT","IMAGE"],"outputModalities":["TEXT"],"maxInputImages":10,"maxInputAudios":0,"maxInputVideos":0,"maxInputDocuments":0,"inputImageFormats":["jpeg","jpg","png","webp","gif"],"supportsImageInput":true,"supportsAudioInput":false,"supportsVideoInput":false,"supportsDocumentInput":false,"supportsJsonObject":true,"supportsReasoning":true,"supportsReasoningDisable":true,"reasoningApiStyle":"OPENAI","outputTokenApiField":"max_completion_tokens","allowedReasoningLevels":["none","low","medium","high","xhigh","max"],"defaultReasoningLevel":"medium","contextWindowTokens":1050000,"maxOutputTokens":128000,"capabilitySourceUrls":["https://developers.openai.com/api/docs/models/gpt-5.6-luna"],"capabilityVerifiedAt":"2026-09-29"}',1,JSON_OBJECT(),'https://developers.openai.com/api/docs/models/gpt-5.6-luna',0,1
FROM aid_ai_provider p
WHERE p.provider_code='openai' AND p.del_flag='0'
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model m WHERE m.provider_id=p.id AND m.model_code='gpt-5.6-luna' AND m.del_flag='0');

INSERT INTO aid_ai_model
  (provider_id,model_code,real_model_code,model_name,model_type,generate_mode,
   api_suffix,protocol,status,del_flag,create_time,create_by,remark,billing_mode,
   billing_rule_json,billing_version,supports_text_input,supports_system_prompt,
   supports_image_input,supports_multi_image_input,max_output_count,default_output_count,
   capability_json,capability_inited,extra_body,official_price_url,is_free,config_version)
SELECT p.id,'gpt-5.6','gpt-5.6','GPT-5.6','text','text',
       '/v1/chat/completions','openai-compatible-text','1','0',NOW(),'system',
       'Official alias of GPT-5.6 Sol; enable after access verification.',
       'SKU','{"mode":"SKU","meterType":"TOKEN","chargeType":"TEXT","preHold":true,"matchStrategy":"FIRST_HIT","params":[],"skus":[{"match":{"inputTokensMin":0,"inputTokensMax":272000},"enabled":true,"skuCode":"OPENAI_GPT_56_STD","skuName":"gpt-5.6 std context","priority":1,"inputPricePerMillion":28,"cachedInputPricePerMillion":2.8,"cacheWritePricePerMillion":35,"outputPricePerMillion":140,"reasoningPricePerMillion":140},{"match":{"inputTokensMin":272001,"inputTokensMax":100000000},"enabled":true,"skuCode":"OPENAI_GPT_56_LONG","skuName":"gpt-5.6 long context","priority":2,"inputPricePerMillion":56,"cachedInputPricePerMillion":5.6,"cacheWritePricePerMillion":70,"outputPricePerMillion":210.0,"reasoningPricePerMillion":210.0}],"settleRule":{"settleMode":"REFUND_ONLY","allowRefund":true,"allowExtraCharge":false,"usageSource":"PROVIDER_USAGE","charToTokenRatio":2,"usagePricingMode":"BUCKETED"}}',1,1,1,1,1,1,1,'{"sceneRules":{"textOnly":{"supportsDuration":false,"supportsSizePreset":false,"supportsAspectRatio":false}},"inputModalities":["TEXT","IMAGE"],"outputModalities":["TEXT"],"maxInputImages":10,"maxInputAudios":0,"maxInputVideos":0,"maxInputDocuments":0,"inputImageFormats":["jpeg","jpg","png","webp","gif"],"supportsImageInput":true,"supportsAudioInput":false,"supportsVideoInput":false,"supportsDocumentInput":false,"supportsJsonObject":true,"supportsReasoning":true,"supportsReasoningDisable":true,"reasoningApiStyle":"OPENAI","outputTokenApiField":"max_completion_tokens","allowedReasoningLevels":["none","low","medium","high","xhigh","max"],"defaultReasoningLevel":"medium","contextWindowTokens":1050000,"maxOutputTokens":128000,"capabilitySourceUrls":["https://developers.openai.com/api/docs/models/gpt-5.6-sol"],"capabilityVerifiedAt":"2026-09-29"}',1,JSON_OBJECT(),
       'https://developers.openai.com/api/docs/models/gpt-5.6-sol',0,1
FROM aid_ai_provider p
WHERE p.provider_code='openai' AND p.del_flag='0'
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model m WHERE m.provider_id=p.id AND m.model_code='gpt-5.6' AND m.del_flag='0');


-- Preserve every existing valid operator SKU. Official alias and missing-price
-- repairs are separate; a custom upstream name is never changed.
UPDATE aid_ai_model m
JOIN aid_ai_provider p ON p.id=m.provider_id AND p.provider_code='openai'
SET m.real_model_code='gpt-5.6',
    m.config_version=COALESCE(m.config_version,0)+1,m.update_time=NOW(),m.update_by='system'
WHERE m.model_code='gpt-5.6' AND m.del_flag='0'
  AND m.real_model_code='gpt-5.6-sol';

UPDATE aid_ai_model m
JOIN aid_ai_provider p ON p.id=m.provider_id AND p.provider_code='openai'
SET m.billing_rule_json='{"mode":"SKU","meterType":"TOKEN","chargeType":"TEXT","preHold":true,"matchStrategy":"FIRST_HIT","params":[],"skus":[{"match":{"inputTokensMin":0,"inputTokensMax":272000},"enabled":true,"skuCode":"OPENAI_GPT_56_STD","skuName":"gpt-5.6 std context","priority":1,"inputPricePerMillion":28,"cachedInputPricePerMillion":2.8,"cacheWritePricePerMillion":35,"outputPricePerMillion":140,"reasoningPricePerMillion":140},{"match":{"inputTokensMin":272001,"inputTokensMax":100000000},"enabled":true,"skuCode":"OPENAI_GPT_56_LONG","skuName":"gpt-5.6 long context","priority":2,"inputPricePerMillion":56,"cachedInputPricePerMillion":5.6,"cacheWritePricePerMillion":70,"outputPricePerMillion":210.0,"reasoningPricePerMillion":210.0}],"settleRule":{"settleMode":"REFUND_ONLY","allowRefund":true,"allowExtraCharge":false,"usageSource":"PROVIDER_USAGE","charToTokenRatio":2,"usagePricingMode":"BUCKETED"}}',
    m.billing_mode='SKU',m.billing_version=COALESCE(m.billing_version,0)+1,
    m.config_version=COALESCE(m.config_version,0)+1,
    m.update_time=NOW(),m.update_by='system'
WHERE m.model_code='gpt-5.6' AND m.del_flag='0'
  AND (m.billing_rule_json IS NULL OR JSON_VALID(m.billing_rule_json)=0
       OR COALESCE(JSON_LENGTH(JSON_EXTRACT(IF(JSON_VALID(m.billing_rule_json),
          m.billing_rule_json,'{}'),'$.skus')),0)=0);

INSERT INTO aid_ai_model_capability
  (model_id,capability_code,generate_mode,definition_json,sort_order,create_time,create_by)
SELECT m.id,'text','text',JSON_OBJECT(
    'code','text','label','文本生成','generateMode','text','enabled',TRUE,
    'defaultCapability',TRUE,'evidenceStatus','OFFICIAL','parameters',JSON_ARRAY(),
    'presentation',JSON_OBJECT('supportsTextInput',TRUE,'supportsImageInput',TRUE,
      'supportsMultiImageInput',TRUE,'supportsSystemPrompt',TRUE,'maxOutputCount',1,
      'defaultOutputCount',1)),0,NOW(),'system'
FROM aid_ai_model m JOIN aid_ai_provider p ON p.id=m.provider_id AND p.provider_code='openai'
WHERE m.model_code IN ('gpt-6-astra','gpt-6-sol','gpt-6-luna','gpt-5.6-sol','gpt-5.6-terra','gpt-5.6-luna','gpt-5.6') AND m.del_flag='0'
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model_capability c WHERE c.model_id=m.id AND c.capability_code='text');

INSERT INTO aid_ai_model_protocol_binding
  (model_id,capability_code,binding_code,protocol,definition_json,sort_order,create_time,create_by)
SELECT m.id,'text',CONCAT('route_',m.id),'openai-compatible-text',JSON_OBJECT(
    'code',CONCAT('route_',m.id),'protocol','openai-compatible-text',
    'upstreamModel',m.real_model_code,'apiSuffix','/v1/chat/completions',
    'enabled',TRUE,'defaultBinding',TRUE,'billingMode','SKU',
    'billingRule',JSON_EXTRACT(m.billing_rule_json,'$'),
    'costCredits',0,'capability',JSON_EXTRACT(m.capability_json,'$'),
    'presentation',JSON_OBJECT('supportsTextInput',TRUE,'supportsImageInput',TRUE,
      'supportsMultiImageInput',TRUE,'supportsSystemPrompt',TRUE,'maxOutputCount',1,
      'defaultOutputCount',1),
    'fixedParameters',JSON_OBJECT(),'parameterMapping',JSON_OBJECT()),
    0,NOW(),'system'
FROM aid_ai_model m JOIN aid_ai_provider p ON p.id=m.provider_id AND p.provider_code='openai'
WHERE m.model_code IN ('gpt-6-astra','gpt-6-sol','gpt-6-luna','gpt-5.6-sol','gpt-5.6-terra','gpt-5.6-luna','gpt-5.6') AND m.del_flag='0'
  AND NOT EXISTS (SELECT 1 FROM aid_ai_model_protocol_binding b WHERE b.model_id=m.id AND b.capability_code='text');

UPDATE aid_ai_model_protocol_binding b
JOIN aid_ai_model m ON m.id=b.model_id AND m.model_code='gpt-5.6' AND m.del_flag='0'
JOIN aid_ai_provider p ON p.id=m.provider_id AND p.provider_code='openai'
SET b.definition_json=JSON_SET(b.definition_json,'$.upstreamModel','gpt-5.6'),
    b.update_time=NOW(),b.update_by='system'
WHERE b.capability_code='text' AND JSON_VALID(b.definition_json)=1
  AND m.real_model_code='gpt-5.6'
  AND JSON_UNQUOTE(JSON_EXTRACT(b.definition_json,'$.upstreamModel'))='gpt-5.6-sol';

-- An absent override continues inheriting the model price. Never turn a custom
-- route's valid SKU rule into the official catalog price.

-- The public Kling Omni edit model uses the same operator-supplied per-second rates
-- for both audio modes. Repair only records that still have no SKUs.
UPDATE aid_ai_model m
SET m.billing_rule_json=JSON_OBJECT(
    'mode','SKU','meterType','PER_SECOND','chargeType','VIDEO','preHold',TRUE,
    'matchStrategy','FIRST_HIT','params',JSON_ARRAY(),
    'skus',JSON_ARRAY(
      JSON_OBJECT('skuCode','KLING30_OMNI_EDIT_720P','skuName','Omni edit 720P','priority',10,
                  'enabled',TRUE,'match',JSON_OBJECT('resolution','720P'),'pricePerSecond',0.7),
      JSON_OBJECT('skuCode','KLING30_OMNI_EDIT_1080P','skuName','Omni edit 1080P','priority',20,
                  'enabled',TRUE,'match',JSON_OBJECT('resolution','1080P'),'pricePerSecond',0.9),
      JSON_OBJECT('skuCode','KLING30_OMNI_EDIT_4K','skuName','Omni edit 4K','priority',30,
                  'enabled',TRUE,'match',JSON_OBJECT('resolution','4K'),'pricePerSecond',2.5)),
    'settleRule',JSON_OBJECT('settleMode','REFUND_ONLY','usageSource','PROVIDER_USAGE',
      'allowRefund',TRUE,'allowExtraCharge',FALSE)),
    m.billing_mode='SKU',m.billing_version=COALESCE(m.billing_version,0)+1,
    m.config_version=COALESCE(m.config_version,0)+1,m.update_time=NOW(),m.update_by='system'
WHERE m.model_code='kling-3.0-omni-edit' AND m.del_flag='0'
  AND (m.billing_rule_json IS NULL OR JSON_VALID(m.billing_rule_json)=0
       OR COALESCE(JSON_LENGTH(JSON_EXTRACT(m.billing_rule_json,'$.skus')),0)=0);

-- DeepSeek V4 Flash retirement: migrate active selections, preserve historical tasks and billing.
-- Repeatable on MySQL 5.7. Do not rewrite immutable Skill package versions or their digests.
COMMIT;
START TRANSACTION;

SET @aid_deepseek_flash_id := (
  SELECT m.id FROM aid_ai_model m
  JOIN aid_ai_provider p ON p.id = m.provider_id
  WHERE p.provider_code = 'deepseek' AND BINARY m.model_code = BINARY 'deepseek-flash'
    AND m.del_flag = '0' LIMIT 1
);
SET @aid_old_deepseek_flash_id := (
  SELECT m.id FROM aid_ai_model m
  JOIN aid_ai_provider p ON p.id = m.provider_id
  WHERE p.provider_code = 'deepseek' AND BINARY m.model_code = BINARY 'deepseek-v4-flash'
    AND m.del_flag = '0' LIMIT 1
);

UPDATE aid_agent SET model_code = 'deepseek-flash', update_time = NOW(), update_by = 'system'
WHERE BINARY model_code = BINARY 'deepseek-v4-flash' AND @aid_deepseek_flash_id IS NOT NULL;
UPDATE aid_skill SET model_code = 'deepseek-flash', update_time = NOW(), update_by = 'system'
WHERE BINARY model_code = BINARY 'deepseek-v4-flash' AND @aid_deepseek_flash_id IS NOT NULL;
UPDATE aid_gen_agent_pool SET model_code = 'deepseek-flash', update_time = NOW(), update_by = 'system'
WHERE BINARY model_code = BINARY 'deepseek-v4-flash' AND @aid_deepseek_flash_id IS NOT NULL;
UPDATE aid_project_gen_config SET model_code = 'deepseek-flash', update_time = NOW(), update_by = 'system'
WHERE BINARY model_code = BINARY 'deepseek-v4-flash' AND @aid_deepseek_flash_id IS NOT NULL;
UPDATE aid_ai_model_alias SET model_id = @aid_deepseek_flash_id, update_time = NOW(), update_by = 'system'
WHERE model_id = @aid_old_deepseek_flash_id AND @aid_deepseek_flash_id IS NOT NULL;

-- Keep historical model IDs for task/ledger audits, but exclude retired models from selection.
UPDATE aid_ai_model m
JOIN aid_ai_provider p ON p.id = m.provider_id
SET m.status = '1', m.del_flag = '1',
    m.update_time = NOW(), m.update_by = 'system'
WHERE @aid_deepseek_flash_id IS NOT NULL AND m.del_flag = '0'
  AND ((p.provider_code = 'deepseek' AND BINARY m.model_code = BINARY 'deepseek-v4-flash')
    OR (p.provider_code = 'tokendance' AND m.real_model_code IN
      ('deepseek-v4-flash', 'deepseek-v4-flash-0731', 'deepseek-v4-flash-vision-exp')));

-- Remove historical capability rows whose pool membership has already been removed.
-- Keep rows of malformed pools for explicit administrator repair instead of guessing membership.
DELETE b FROM aid_ai_business_model_binding b
LEFT JOIN aid_ai_model_func_config f ON f.func_code = b.func_code AND f.del_flag = '0'
WHERE f.id IS NULL OR JSON_CONTAINS(
  CASE WHEN JSON_VALID(f.model_ids) THEN f.model_ids ELSE JSON_ARRAY(b.model_id) END,
  CAST(b.model_id AS CHAR)) = 0;

-- Seedance Mini uses its own TOKEN rule; never inherit a customized base model's unit.
-- Official list-price contract: https://docs.volcengine.com/docs/ark/model-pricing?lang=zh
-- Only fill missing rules or repair the exact broken 14/23-token seed with PER_SECOND.
-- Existing valid operator rules, SKU prices, model enablement and binding selection survive.
SET @aid_seedance20_mini_rule := '{"mode":"SKU","meterType":"TOKEN","chargeType":"VIDEO","preHold":true,"matchStrategy":"FIRST_HIT","settleRule":{"settleMode":"REFUND_ONLY","usageSource":"PROVIDER_USAGE","allowRefund":true,"allowExtraCharge":false},"params":[{"code":"resolution","name":"分辨率","type":"ENUM","options":["480P","720P"],"required":true}],"videoTokenEstimate":{"strategy":"PIXEL_FPS","framesPerSecond":24,"tokenDivisor":1024,"autoDurationMaxSeconds":15,"inputVideoMaxSeconds":15,"fallbackResolution":"720P","minimumInputSecondsNumerator":2,"minimumInputSecondsDenominator":3,"dimensions":{"480P":{"16:9":[864,496],"9:16":[864,496],"4:3":[752,560],"3:4":[752,560],"1:1":[640,640],"21:9":[992,432],"default":[864,496]},"720P":{"16:9":[1280,720],"9:16":[1280,720],"4:3":[1112,834],"3:4":[1112,834],"1:1":[960,960],"21:9":[1470,630],"default":[1112,834]}}},"skus":[{"skuCode":"SEEDANCE20_MINI_480P_INVIDEO","skuName":"Seedance 2.0 Mini 480P with video input","enabled":true,"priority":1,"match":{"resolution":"480P","inputVideoCountMin":1},"inputPricePerMillion":0,"outputPricePerMillion":14},{"skuCode":"SEEDANCE20_MINI_720P_INVIDEO","skuName":"Seedance 2.0 Mini 720P with video input","enabled":true,"priority":2,"match":{"resolution":"720P","inputVideoCountMin":1},"inputPricePerMillion":0,"outputPricePerMillion":14},{"skuCode":"SEEDANCE20_MINI_480P","skuName":"Seedance 2.0 Mini 480P","enabled":true,"priority":11,"match":{"resolution":"480P"},"inputPricePerMillion":0,"outputPricePerMillion":23},{"skuCode":"SEEDANCE20_MINI_720P","skuName":"Seedance 2.0 Mini 720P","enabled":true,"priority":12,"match":{"resolution":"720P"},"inputPricePerMillion":0,"outputPricePerMillion":23}]}';
DROP TEMPORARY TABLE IF EXISTS tmp_seedance_mini_model_prices;
CREATE TEMPORARY TABLE tmp_seedance_mini_model_prices (
  model_id BIGINT NOT NULL PRIMARY KEY, billing_rule LONGTEXT NOT NULL
);
INSERT INTO tmp_seedance_mini_model_prices(model_id,billing_rule)
SELECT source.model_id,CASE
  WHEN JSON_UNQUOTE(JSON_EXTRACT(source.safe_rule,'$.meterType'))='PER_SECOND'
    AND JSON_LENGTH(JSON_EXTRACT(source.safe_rule,'$.skus'))=4
    AND JSON_CONTAINS(JSON_EXTRACT(source.safe_rule,'$.skus'),'[{"skuCode":"SEEDANCE20_MINI_480P_INVIDEO","inputPricePerMillion":0,"outputPricePerMillion":14},{"skuCode":"SEEDANCE20_MINI_720P_INVIDEO","inputPricePerMillion":0,"outputPricePerMillion":14},{"skuCode":"SEEDANCE20_MINI_480P","inputPricePerMillion":0,"outputPricePerMillion":23},{"skuCode":"SEEDANCE20_MINI_720P","inputPricePerMillion":0,"outputPricePerMillion":23}]')=1
    AND JSON_CONTAINS_PATH(source.safe_rule,'one','$.skus[0].price','$.skus[0].pricePerSecond','$.skus[0].meterType','$.skus[1].price','$.skus[1].pricePerSecond','$.skus[1].meterType','$.skus[2].price','$.skus[2].pricePerSecond','$.skus[2].meterType','$.skus[3].price','$.skus[3].pricePerSecond','$.skus[3].meterType')=0
  THEN JSON_SET(source.safe_rule,'$.meterType','TOKEN',
    '$.videoTokenEstimate',JSON_EXTRACT(@aid_seedance20_mini_rule,'$.videoTokenEstimate'))
  ELSE @aid_seedance20_mini_rule END
FROM (
  SELECT m.id model_id,m.billing_rule_json,
    CASE WHEN JSON_VALID(m.billing_rule_json) THEN m.billing_rule_json ELSE JSON_OBJECT() END safe_rule
  FROM aid_ai_model m JOIN aid_ai_provider p ON p.id=m.provider_id AND p.provider_code='volcengine'
  WHERE m.model_code='doubao-seedance-2.0-mini' AND m.del_flag='0'
) source
WHERE source.billing_rule_json IS NULL OR JSON_VALID(source.billing_rule_json)=0
  OR COALESCE(JSON_LENGTH(JSON_EXTRACT(source.safe_rule,'$.skus')),0)=0
  OR (JSON_UNQUOTE(JSON_EXTRACT(source.safe_rule,'$.meterType'))='PER_SECOND'
    AND JSON_LENGTH(JSON_EXTRACT(source.safe_rule,'$.skus'))=4
    AND JSON_CONTAINS(JSON_EXTRACT(source.safe_rule,'$.skus'),'[{"skuCode":"SEEDANCE20_MINI_480P_INVIDEO","inputPricePerMillion":0,"outputPricePerMillion":14},{"skuCode":"SEEDANCE20_MINI_720P_INVIDEO","inputPricePerMillion":0,"outputPricePerMillion":14},{"skuCode":"SEEDANCE20_MINI_480P","inputPricePerMillion":0,"outputPricePerMillion":23},{"skuCode":"SEEDANCE20_MINI_720P","inputPricePerMillion":0,"outputPricePerMillion":23}]')=1
    AND JSON_CONTAINS_PATH(source.safe_rule,'one','$.skus[0].price','$.skus[0].pricePerSecond','$.skus[0].meterType','$.skus[1].price','$.skus[1].pricePerSecond','$.skus[1].meterType','$.skus[2].price','$.skus[2].pricePerSecond','$.skus[2].meterType','$.skus[3].price','$.skus[3].pricePerSecond','$.skus[3].meterType')=0);

DROP TEMPORARY TABLE IF EXISTS tmp_seedance_mini_binding_prices;
CREATE TEMPORARY TABLE tmp_seedance_mini_binding_prices (
  binding_id BIGINT NOT NULL PRIMARY KEY, model_id BIGINT NOT NULL, definition_json LONGTEXT NOT NULL
);
INSERT INTO tmp_seedance_mini_binding_prices(binding_id,model_id,definition_json)
SELECT source.binding_id,source.model_id,
  JSON_SET(source.safe_definition,'$.billingRule',
    JSON_SET(source.billing_rule,'$.meterType','TOKEN',
      '$.videoTokenEstimate',JSON_EXTRACT(@aid_seedance20_mini_rule,'$.videoTokenEstimate')))
FROM (
  SELECT b.id binding_id,b.model_id,
    CASE WHEN JSON_VALID(b.definition_json) THEN b.definition_json ELSE JSON_OBJECT() END safe_definition,
    JSON_EXTRACT(CASE WHEN JSON_VALID(b.definition_json) THEN b.definition_json ELSE JSON_OBJECT() END,'$.billingRule') billing_rule
  FROM aid_ai_model_protocol_binding b
  JOIN aid_ai_model m ON m.id=b.model_id AND m.model_code='doubao-seedance-2.0-mini' AND m.del_flag='0'
  JOIN aid_ai_provider p ON p.id=m.provider_id AND p.provider_code='volcengine'
  -- Missing route prices intentionally inherit the model rule; do not turn them into overrides.
) source
WHERE JSON_UNQUOTE(JSON_EXTRACT(source.billing_rule,'$.meterType'))='PER_SECOND'
    AND JSON_LENGTH(JSON_EXTRACT(source.billing_rule,'$.skus'))=4
    AND JSON_CONTAINS(JSON_EXTRACT(source.billing_rule,'$.skus'),'[{"skuCode":"SEEDANCE20_MINI_480P_INVIDEO","inputPricePerMillion":0,"outputPricePerMillion":14},{"skuCode":"SEEDANCE20_MINI_720P_INVIDEO","inputPricePerMillion":0,"outputPricePerMillion":14},{"skuCode":"SEEDANCE20_MINI_480P","inputPricePerMillion":0,"outputPricePerMillion":23},{"skuCode":"SEEDANCE20_MINI_720P","inputPricePerMillion":0,"outputPricePerMillion":23}]')=1
    AND JSON_CONTAINS_PATH(source.billing_rule,'one','$.skus[0].price','$.skus[0].pricePerSecond','$.skus[0].meterType','$.skus[1].price','$.skus[1].pricePerSecond','$.skus[1].meterType','$.skus[2].price','$.skus[2].pricePerSecond','$.skus[2].meterType','$.skus[3].price','$.skus[3].pricePerSecond','$.skus[3].meterType')=0;

UPDATE aid_ai_model m JOIN tmp_seedance_mini_model_prices patch ON patch.model_id=m.id
SET m.billing_rule_json=patch.billing_rule,m.billing_mode='SKU',
    m.official_price_url='https://docs.volcengine.com/docs/ark/model-pricing?lang=zh';
UPDATE aid_ai_model_protocol_binding b JOIN tmp_seedance_mini_binding_prices patch ON patch.binding_id=b.id
SET b.definition_json=patch.definition_json,b.update_time=NOW(),b.update_by='system';
UPDATE aid_ai_model m JOIN (
  SELECT model_id FROM tmp_seedance_mini_model_prices
  UNION SELECT model_id FROM tmp_seedance_mini_binding_prices
) changed ON changed.model_id=m.id
SET m.billing_version=COALESCE(m.billing_version,0)+1,
    m.config_version=COALESCE(m.config_version,0)+1,m.update_time=NOW(),m.update_by='system';
DROP TEMPORARY TABLE IF EXISTS tmp_seedance_mini_binding_prices;
DROP TEMPORARY TABLE IF EXISTS tmp_seedance_mini_model_prices;
SET @aid_seedance20_mini_rule := NULL;

COMMIT;

-- Seedance 2.0 / Fast / Mini allow explicit ratios for first/last-frame generation.
-- Repair the old adaptive-only public seed at all three persisted capability layers.
-- Keep 2.5 constraints, custom non-adaptive choices, prices, status and pool relations.
-- Official: https://docs.volcengine.com/docs/ark/create-video-generation-task-api?lang=zh
START TRANSACTION;
DROP TEMPORARY TABLE IF EXISTS tmp_seedance20_ratio_models;
CREATE TEMPORARY TABLE tmp_seedance20_ratio_models (id BIGINT PRIMARY KEY);
INSERT INTO tmp_seedance20_ratio_models
SELECT id FROM aid_ai_model
WHERE LOWER(COALESCE(NULLIF(real_model_code,''),model_code)) REGEXP 'seedance-2[.-]0($|[-.])';
DROP TEMPORARY TABLE IF EXISTS tmp_seedance20_ratio_docs;
CREATE TEMPORARY TABLE tmp_seedance20_ratio_docs (
 kind CHAR(1),id BIGINT,model_id BIGINT,before_doc LONGTEXT,doc LONGTEXT,
 PRIMARY KEY(kind,id)
);
INSERT INTO tmp_seedance20_ratio_docs
SELECT 'M',m.id,m.id,m.capability_json,m.capability_json FROM aid_ai_model m
JOIN tmp_seedance20_ratio_models t ON t.id=m.id WHERE JSON_VALID(m.capability_json);
INSERT INTO tmp_seedance20_ratio_docs
SELECT 'B',b.id,b.model_id,b.definition_json,b.definition_json FROM aid_ai_model_protocol_binding b
JOIN tmp_seedance20_ratio_models t ON t.id=b.model_id
WHERE b.capability_code IN ('image_to_video','start_end_to_video') AND JSON_VALID(b.definition_json);
INSERT INTO tmp_seedance20_ratio_docs
SELECT 'C',c.id,c.model_id,c.definition_json,c.definition_json FROM aid_ai_model_capability c
JOIN tmp_seedance20_ratio_models t ON t.id=c.model_id
WHERE c.capability_code IN ('image_to_video','start_end_to_video') AND JSON_VALID(c.definition_json);
UPDATE tmp_seedance20_ratio_docs
SET doc=JSON_SET(doc,'$.aspectRatioOptions',JSON_ARRAY('adaptive','16:9','9:16','4:3','3:4','1:1','21:9'),'$.defaultAspectRatio','16:9')
WHERE kind='M' AND JSON_EXTRACT(doc,'$.aspectRatioOptions')=JSON_ARRAY('adaptive');
UPDATE tmp_seedance20_ratio_docs
SET doc=JSON_SET(doc,'$.sceneRules.imageToVideo.aspectRatioOptions',JSON_ARRAY('adaptive','16:9','9:16','4:3','3:4','1:1','21:9'),'$.sceneRules.imageToVideo.defaultAspectRatio','16:9')
WHERE kind='M' AND JSON_EXTRACT(doc,'$.sceneRules.imageToVideo.aspectRatioOptions')=JSON_ARRAY('adaptive');
UPDATE tmp_seedance20_ratio_docs
SET doc=JSON_SET(doc,'$.sceneRules.startEndToVideo.aspectRatioOptions',JSON_ARRAY('adaptive','16:9','9:16','4:3','3:4','1:1','21:9'),'$.sceneRules.startEndToVideo.defaultAspectRatio','16:9')
WHERE kind='M' AND JSON_EXTRACT(doc,'$.sceneRules.startEndToVideo.aspectRatioOptions')=JSON_ARRAY('adaptive');
UPDATE tmp_seedance20_ratio_docs
SET doc=JSON_SET(doc,'$.capability.aspectRatioOptions',JSON_ARRAY('adaptive','16:9','9:16','4:3','3:4','1:1','21:9'),'$.capability.defaultAspectRatio','16:9')
WHERE kind='B' AND JSON_EXTRACT(doc,'$.capability.aspectRatioOptions')=JSON_ARRAY('adaptive');
UPDATE tmp_seedance20_ratio_docs
SET doc=JSON_SET(doc,'$.capability.sceneRules.imageToVideo.aspectRatioOptions',JSON_ARRAY('adaptive','16:9','9:16','4:3','3:4','1:1','21:9'),'$.capability.sceneRules.imageToVideo.defaultAspectRatio','16:9')
WHERE kind='B' AND JSON_EXTRACT(doc,'$.capability.sceneRules.imageToVideo.aspectRatioOptions')=JSON_ARRAY('adaptive');
UPDATE tmp_seedance20_ratio_docs
SET doc=JSON_SET(doc,'$.capability.sceneRules.startEndToVideo.aspectRatioOptions',JSON_ARRAY('adaptive','16:9','9:16','4:3','3:4','1:1','21:9'),'$.capability.sceneRules.startEndToVideo.defaultAspectRatio','16:9')
WHERE kind='B' AND JSON_EXTRACT(doc,'$.capability.sceneRules.startEndToVideo.aspectRatioOptions')=JSON_ARRAY('adaptive');
UPDATE tmp_seedance20_ratio_docs
SET doc=JSON_SET(doc,'$.presentation.defaultAspectRatio','16:9')
WHERE kind='B' AND NOT(doc <=> before_doc)
  AND JSON_UNQUOTE(JSON_EXTRACT(doc,'$.presentation.defaultAspectRatio'))='adaptive';
-- Find the parameter by name instead of assuming an array position.
UPDATE tmp_seedance20_ratio_docs
SET doc=JSON_SET(doc,
 REPLACE(JSON_UNQUOTE(JSON_SEARCH(doc,'one','aspectRatio',NULL,'$.parameters[*].name')),'.name','.choices'),JSON_ARRAY('adaptive','16:9','9:16','4:3','3:4','1:1','21:9'),
 REPLACE(JSON_UNQUOTE(JSON_SEARCH(doc,'one','aspectRatio',NULL,'$.parameters[*].name')),'.name','.defaultValue'),'16:9',
 '$.presentation.defaultAspectRatio','16:9')
WHERE kind='C' AND JSON_EXTRACT(doc,
 REPLACE(JSON_UNQUOTE(JSON_SEARCH(doc,'one','aspectRatio',NULL,'$.parameters[*].name')),'.name','.choices'))=JSON_ARRAY('adaptive');
UPDATE aid_ai_model m JOIN tmp_seedance20_ratio_docs d ON d.kind='M' AND d.id=m.id
SET m.capability_json=d.doc,
 m.default_aspect_ratio=IF(m.default_aspect_ratio='adaptive','16:9',m.default_aspect_ratio)
WHERE NOT(d.doc <=> d.before_doc);
UPDATE aid_ai_model_capability c JOIN tmp_seedance20_ratio_docs d ON d.kind='C' AND d.id=c.id
SET c.definition_json=d.doc,c.update_by='system',c.update_time=NOW()
WHERE NOT(d.doc <=> d.before_doc);
UPDATE aid_ai_model_protocol_binding b JOIN tmp_seedance20_ratio_docs d ON d.kind='B' AND d.id=b.id
SET b.definition_json=d.doc,b.update_by='system',b.update_time=NOW()
WHERE NOT(d.doc <=> d.before_doc);
UPDATE aid_ai_model m JOIN (
 SELECT DISTINCT model_id FROM tmp_seedance20_ratio_docs WHERE NOT(doc <=> before_doc)
) changed ON changed.model_id=m.id
SET m.config_version=COALESCE(m.config_version,0)+1,m.update_by='system',m.update_time=NOW();
DROP TEMPORARY TABLE tmp_seedance20_ratio_docs;
DROP TEMPORARY TABLE tmp_seedance20_ratio_models;

-- Existing installations do not rerun a previously recorded v2.2.3 script.
-- Normalize numeric STRING values without changing custom scene minimums.
UPDATE aid_ai_model_protocol_binding b
JOIN aid_ai_model m ON m.id=b.model_id AND m.del_flag='0'
SET b.definition_json=JSON_SET(b.definition_json,'$.capability.referenceVideoMinDurationSeconds',
  CAST(JSON_UNQUOTE(JSON_EXTRACT(b.definition_json,'$.capability.referenceVideoMinDurationSeconds')) AS DECIMAL(12,6))),
  b.update_time=NOW(),b.update_by='system'
WHERE b.protocol='seedance-video' AND JSON_VALID(b.definition_json)=1
  AND JSON_TYPE(JSON_EXTRACT(b.definition_json,'$.capability.referenceVideoMinDurationSeconds'))='STRING'
  AND JSON_UNQUOTE(JSON_EXTRACT(b.definition_json,'$.capability.referenceVideoMinDurationSeconds')) REGEXP '^[0-9]+([.][0-9]+)?$'
  AND LENGTH(JSON_UNQUOTE(JSON_EXTRACT(b.definition_json,'$.capability.referenceVideoMinDurationSeconds')))<=12;
COMMIT;
