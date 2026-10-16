package com.zhangchi.gameasset.convert;

import com.zhangchi.gameasset.common.util.FileUtil;
import com.zhangchi.gameasset.entity.AssetVersion;
import com.zhangchi.gameasset.vo.AssetVersionVO;
import org.springframework.beans.BeanUtils;

/**
 * 资产版本实体 → VO。
 *
 * @author 张忠驰
 */
public final class AssetVersionConverter {

    /** 下载接口的路径模板，前端拿到即可直接发起下载 */
    private static final String DOWNLOAD_URL_TEMPLATE = "/api/assets/%d/versions/%d/download";

    private AssetVersionConverter() {
    }

    /**
     * @param entity       版本实体
     * @param uploaderName 上传人昵称，可为 null（用户被删等极端情况）
     */
    public static AssetVersionVO toVO(AssetVersion entity, String uploaderName) {
        if (entity == null) {
            return null;
        }
        AssetVersionVO vo = new AssetVersionVO();
        BeanUtils.copyProperties(entity, vo);

        vo.setFileSizeText(FileUtil.humanSize(entity.getFileSize() == null ? 0L : entity.getFileSize()));
        vo.setCurrent(Integer.valueOf(1).equals(entity.getIsCurrent()));
        if (entity.getId() != null && entity.getAssetId() != null) {
            vo.setDownloadUrl(String.format(DOWNLOAD_URL_TEMPLATE, entity.getAssetId(), entity.getId()));
        }
        vo.setUploaderName(uploaderName);
        return vo;
    }
}
