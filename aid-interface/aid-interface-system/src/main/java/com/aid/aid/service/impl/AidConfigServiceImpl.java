package com.aid.aid.service.impl;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import cn.hutool.core.collection.CollectionUtil;
import com.aid.vo.ChatConfigVO;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.constant.AccountCancellationConstants;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.SecurityUtils;
import com.aid.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.mapper.AidConfigMapper;
import com.aid.aid.domain.AidConfig;
import com.aid.aid.service.IAidConfigService;

/**
 * 配置信息Service业务层处理
 *
 * @author 视觉AID
 */
@Service
@Slf4j
public class AidConfigServiceImpl extends ServiceImpl<AidConfigMapper, AidConfig> implements IAidConfigService, ConfigService {
    private static final String MODEL_PRICE_CATEGORY = "media";
    private static final String MODEL_PRICE_KEY = "ai_billing_global_multiplier";
    private static final BigDecimal MAX_MODEL_PRICE_MULTIPLIER = new BigDecimal("100000");

    @Autowired
    private AidConfigMapper aidConfigMapper;

    /**
     * 查询配置信息
     *
     * @param id 配置信息主键
     * @return 配置信息
     */
    @Override
    public AidConfig selectAidConfigById(Long id) {
        AidConfig config = this.getById(id);
        rejectDiagnosticConfig(config);
        return config;
    }

    /**
     * 查询配置信息列表
     *
     * @param aidConfig 配置信息
     * @return 配置信息
     */
    @Override
    public List<AidConfig> selectAidConfigList(AidConfig aidConfig) {
        LambdaQueryWrapper<AidConfig> lambdaQueryWrapper = Wrappers.lambdaQuery();
        lambdaQueryWrapper.ne(AidConfig::getCategory, "error_diagnostics");
        if (aidConfig != null) {
            if (StringUtils.isNotEmpty(aidConfig.getCategory())) {
                lambdaQueryWrapper.eq(AidConfig::getCategory, aidConfig.getCategory());
            }
            if (StringUtils.isNotEmpty(aidConfig.getConfigName())) {
                lambdaQueryWrapper.like(AidConfig::getConfigName, aidConfig.getConfigName());
            }
            if (StringUtils.isNotEmpty(aidConfig.getConfigValue())) {
                lambdaQueryWrapper.like(AidConfig::getConfigValue, aidConfig.getConfigValue());
            }
            if (StringUtils.isNotEmpty(aidConfig.getConfigDict())) {
                lambdaQueryWrapper.like(AidConfig::getConfigDict, aidConfig.getConfigDict());
            }
            if (aidConfig.getTenantId() != null) {
                lambdaQueryWrapper.eq(AidConfig::getTenantId, aidConfig.getTenantId());
            }
        }
        // 过滤逻辑删除行：实体未启用 @TableLogic，不加此条件会把 del_flag=1 的已下线配置展示到后台
        lambdaQueryWrapper.eq(AidConfig::getDelFlag, "0");
        // 按 order_num 升序排序（数值越小排在前面）
        lambdaQueryWrapper.orderByAsc(AidConfig::getOrderNum);
        return this.list(lambdaQueryWrapper);
    }

    /**
     * 新增配置信息
     *
     * @param aidConfig 配置信息
     * @return 结果
     */
    @Override
    public int insertAidConfig(AidConfig aidConfig) {
        rejectDiagnosticConfig(aidConfig);
        validateConfigValue(aidConfig.getCategory(), aidConfig.getConfigName(), aidConfig.getConfigValue());
        aidConfig.setCreateBy(currentOperator());
        aidConfig.setCreateTime(DateUtils.getNowDate());
        return this.save(aidConfig) ? 1 : 0;
    }

    /**
     * 修改配置信息
     *
     * @param aidConfig 配置信息
     * @return 结果
     */
    @Override
    public int updateAidConfig(AidConfig aidConfig) {
        AidConfig existing = aidConfig.getId() == null ? null : this.getById(aidConfig.getId());
        rejectDiagnosticConfig(existing);
        rejectDiagnosticConfig(aidConfig);
        String category = StringUtils.isBlank(aidConfig.getCategory()) && existing != null
                ? existing.getCategory() : aidConfig.getCategory();
        String configName = StringUtils.isBlank(aidConfig.getConfigName()) && existing != null
                ? existing.getConfigName() : aidConfig.getConfigName();
        validateConfigValue(category, configName, aidConfig.getConfigValue());
        aidConfig.setUpdateBy(currentOperator());
        aidConfig.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidConfig) ? 1 : 0;
    }

    /**
     * 批量删除配置信息
     *
     * @param ids 需要删除的配置信息主键
     * @return 结果
     */
    @Override
    public int deleteAidConfigByIds(Long[] ids) {
        if (ids == null || ids.length == 0) {
            return 0;
        }
        this.list(Wrappers.<AidConfig>lambdaQuery().select(AidConfig::getId, AidConfig::getCategory)
                .in(AidConfig::getId, Arrays.asList(ids))).forEach(this::rejectDiagnosticConfig);
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除配置信息信息
     *
     * @param id 配置信息主键
     * @return 结果
     */
    @Override
    public int deleteAidConfigById(Long id) {
        if (id == null) {
            return 0;
        }
        rejectDiagnosticConfig(this.getById(id));
        return this.removeById(id) ? 1 : 0;
    }

    /**
     * 根据配置类型和配置key获取值
     *
     * @param category
     * @param configKey
     * @return
     */
    @Override
    public String getConfigValue(String category, String configKey) {
        LambdaQueryWrapper<AidConfig> lqw = Wrappers.lambdaQuery();
        lqw.select(AidConfig::getId, AidConfig::getConfigValue);
        lqw.eq(AidConfig::getCategory, category);
        lqw.eq(AidConfig::getConfigName, configKey);
        lqw.eq(AidConfig::getDelFlag, "0");
        lqw.orderByDesc(AidConfig::getId);
        lqw.last("LIMIT 1");
        AidConfig aidConfig = this.getOne(lqw, false);
        if (Objects.isNull(aidConfig)) {
            return null;
        }
        return aidConfig.getConfigValue();
    }

    /**
     * 根据分类获取值
     *
     * @param category
     * @return
     */
    @Override
    public  Map<String, String> getConfigValues(String category) {
        ChatConfigVO bo = new ChatConfigVO();
        bo.setCategory(category);
        LambdaQueryWrapper<AidConfig> lqw = buildQueryWrapper(bo);
        List<AidConfig> list = this.list(lqw);
        if (CollectionUtil.isEmpty(list)) {
            throw new RuntimeException(category +"||不存在");
        }
        Map<String, String> configMap = list.stream()
                .collect(Collectors.toMap(
                        AidConfig::getConfigName,
                        AidConfig::getConfigValue,
                        (v1, v2) -> v2  // 如果有重复key，取后面的
                ));

        return configMap;
    }

    /**
     * 按「分类 + 配置名」精确 upsert 配置值：存在则更新，不存在则新增
     *
     * @param category    配置分类
     * @param configName  配置名
     * @param configValue 配置值
     */
    @Override
    public void upsertConfigValue(String category, String configName, String configValue) {
        validateConfigValue(category, configName, configValue);
        // 精确匹配分类 + 配置名（区别于列表查询的 like）
        LambdaQueryWrapper<AidConfig> lqw = Wrappers.lambdaQuery();
        lqw.eq(AidConfig::getCategory, category);
        lqw.eq(AidConfig::getConfigName, configName);
        lqw.orderByDesc(AidConfig::getId);
        lqw.last("LIMIT 1");
        // 历史软删除记录仍复用原行，避免唯一键冲突。
        AidConfig existing = this.getOne(lqw, false);
        String operator = currentOperator();
        if (Objects.nonNull(existing)) {
            // 已存在：更新值与更新者/更新时间
            existing.setConfigValue(configValue);
            existing.setDelFlag("0");
            existing.setUpdateBy(operator);
            existing.setUpdateTime(DateUtils.getNowDate());
            this.updateById(existing);
            return;
        }
        // 不存在：新增并填充创建者/创建时间
        AidConfig config = new AidConfig();
        config.setCategory(category);
        config.setConfigName(configName);
        config.setConfigValue(configValue);
        config.setDelFlag("0");
        config.setCreateBy(operator);
        config.setCreateTime(DateUtils.getNowDate());
        this.save(config);
    }

    /**
     * 获取当前操作者，无登录上下文时回退系统标识
     *
     * @return 操作者标识
     */
    private String currentOperator() {
        try {
            String username = SecurityUtils.getUsername();
            return StringUtils.isBlank(username) ? "system" : username;
        } catch (Exception e) {
            // 无登录上下文回退系统标识
            return "system";
        }
    }

    private void validateConfigValue(String category, String configName, String configValue) {
        validateModelPriceMultiplier(category, configName, configValue);
        validateAccountCancellationConfig(category, configName, configValue);
    }

    /**
     * 模型基础倍率必须为正数，防止计费为零或异常放大。
     */
    private void validateModelPriceMultiplier(String category, String configName, String configValue) {
        if (!MODEL_PRICE_CATEGORY.equals(category) || !MODEL_PRICE_KEY.equals(configName)) {
            return;
        }
        try {
            BigDecimal multiplier = new BigDecimal(configValue);
            if (multiplier.compareTo(BigDecimal.ZERO) <= 0
                    || multiplier.compareTo(MAX_MODEL_PRICE_MULTIPLIER) > 0) {
                throw new NumberFormatException("out of range");
            }
        } catch (Exception e) {
            log.error("模型基础倍率配置错误, value={}", configValue, e);
            throw new ServiceException("倍率配置错误");
        }
    }

    private void validateAccountCancellationConfig(String category, String configName, String configValue) {
        if (!Objects.equals(AccountCancellationConstants.CONFIG_CATEGORY, category)) {
            return;
        }
        if (Objects.equals(AccountCancellationConstants.CONFIG_ENABLED, configName)) {
            if (!"true".equalsIgnoreCase(configValue) && !"false".equalsIgnoreCase(configValue)) {
                log.error("注销再注册开关配置错误: value={}", configValue);
                throw new ServiceException("开关配置错误");
            }
            return;
        }
        if (!Objects.equals(AccountCancellationConstants.CONFIG_DAYS, configName)) {
            return;
        }
        try {
            int days = Integer.parseInt(configValue);
            if (days < AccountCancellationConstants.MIN_DAYS
                    || days > AccountCancellationConstants.MAX_DAYS) {
                throw new NumberFormatException("out of range");
            }
        } catch (Exception e) {
            log.error("注销再注册天数配置错误: value={}", configValue, e);
            throw new ServiceException("限制天数错误");
        }
    }

    private LambdaQueryWrapper<AidConfig> buildQueryWrapper(ChatConfigVO vo) {
        Map<String, Object> params = vo.getParams();
        LambdaQueryWrapper<AidConfig> lqw = Wrappers.lambdaQuery();
        lqw.eq(StringUtils.isNotBlank(vo.getCategory()), AidConfig::getCategory, vo.getCategory());
        lqw.like(StringUtils.isNotBlank(vo.getConfigName()), AidConfig::getConfigName, vo.getConfigName());
        lqw.eq(StringUtils.isNotBlank(vo.getConfigValue()), AidConfig::getConfigValue, vo.getConfigValue());
        lqw.eq(StringUtils.isNotBlank(vo.getConfigDict()), AidConfig::getConfigDict, vo.getConfigDict());
        lqw.eq(AidConfig::getDelFlag, "0");
        lqw.orderByAsc(AidConfig::getId);
        return lqw;
    }
    private void rejectDiagnosticConfig(AidConfig config) {
        if (config != null && "error_diagnostics".equals(config.getCategory())) {
            throw new ServiceException("请在错误处理页面管理诊断配置");
        }
    }
}
