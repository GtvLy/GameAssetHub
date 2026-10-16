package com.zhangchi.gameasset;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 游戏项目资产与协作管理系统 —— 启动类。
 *
 * <p>模块1：项目与成员管理。端口 8082，库名 {@code game_asset_hub}。</p>
 *
 * @author 张忠驰
 */
@SpringBootApplication
@MapperScan("com.zhangchi.gameasset.mapper")
public class GameAssetHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(GameAssetHubApplication.class, args);
    }
}
