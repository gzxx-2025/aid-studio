package com.aid.model.definition;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.compose.ComposeConstants;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.MinimaxH3Constants;
import com.aid.service.IAiModelConfigService;
import com.aid.tokendance.credential.TokenDanceCredentialStore;
import com.aid.tokendance.credential.TokenDanceTaskRouteSnapshot;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 排队提交、调度补偿与轮询共用任务提交时的路由。 */
@Service
@RequiredArgsConstructor
public class ModelTaskConfigurationResolver {
    private final IAiModelConfigService configurations;
    private final TokenDanceCredentialStore tokenDanceCredentials;

    public AiModelConfigVo resolve(AidMediaTask task) {
        if (task == null || task.getModelName() == null) return null;
        String snapshot = task.getProviderRouteSnapshotJson();
        if (ModelTaskRouteSnapshot.supports(snapshot)) {
            Long modelId = JSON.parseObject(snapshot).getLong("id");
            return ModelTaskRouteSnapshot.restore(snapshot,
                    configurations.selectTaskCredentials(modelId, task.getModelName(), task.getUserId()));
        }
        if (snapshot != null && !snapshot.isBlank()) return TokenDanceTaskRouteSnapshot.restore(snapshot, tokenDanceCredentials);
        if (ComposeConstants.MEDIA_TYPE_COMPOSE.equals(task.getMediaType())) return configurations.selectByModelCode(task.getModelName());
        return configurations.selectByModelCodeForUser(task.getModelName(), task.getUserId());
    }

    /** MiniMax H3 轮询读取当前供应商查询路径；提交路由及其他任务字段仍保持原任务快照。 */
    public AiModelConfigVo resolveForQuery(AidMediaTask task) {
        if (task == null || !MinimaxH3Constants.PROTOCOL_VIDEO.equals(task.getProtocol())
                || !ModelTaskRouteSnapshot.supports(task.getProviderRouteSnapshotJson())) {
            return resolve(task);
        }
        Long modelId = JSON.parseObject(task.getProviderRouteSnapshotJson()).getLong("id");
        AiModelConfigVo current = configurations.selectTaskCredentials(modelId, task.getModelName(), task.getUserId());
        String currentQueryPath = current == null ? null : current.getTaskQuerySuffix();
        AiModelConfigVo restored = ModelTaskRouteSnapshot.restore(task.getProviderRouteSnapshotJson(), current);
        restored.setTaskQuerySuffix(currentQueryPath);
        return restored;
    }
}
