package com.demo.gameasset.convert;

import com.demo.gameasset.common.enums.AssetStatus;
import com.demo.gameasset.entity.AssetStatusLog;
import com.demo.gameasset.vo.AssetStatusLogVO;
import org.springframework.beans.BeanUtils;

/**
 * 资产状态流转记录 → VO。
 *
 * @author 管理员
 */
public final class AssetStatusLogConverter {

    private AssetStatusLogConverter() {
    }

    /**
     * @param entity       流转记录
     * @param operatorName 操作人昵称，可为 null
     */
    public static AssetStatusLogVO toVO(AssetStatusLog entity, String operatorName) {
        if (entity == null) {
            return null;
        }
        AssetStatusLogVO vo = new AssetStatusLogVO();
        BeanUtils.copyProperties(entity, vo);
        vo.setFromStatusLabel(entity.getFromStatus() == null ? null : AssetStatus.label(entity.getFromStatus()));
        vo.setToStatusLabel(AssetStatus.label(entity.getToStatus()));
        vo.setOperatorName(operatorName);
        return vo;
    }
}
