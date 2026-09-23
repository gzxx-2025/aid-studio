package com.aid.model.vo;

import com.aid.aid.domain.model.ModelParameter;
import com.aid.aid.domain.model.ModelParameterRule;
import com.aid.billing.vo.ModelBillingDetailVO;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** C 端可选择的模型能力视图，不暴露供应商路由、固定入参和成本配置。 */
@Data
public class ModelCapabilityOptionVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String code;
    private String label;
    private String generateMode;
    private List<ModelParameter> parameterSchema = new ArrayList<>();
    private List<ModelParameterRule> parameterRules = new ArrayList<>();
    private Map<String, Object> presentation;
    private ModelBillingDetailVO billing;
}
