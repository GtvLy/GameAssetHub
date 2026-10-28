package com.demo.gameasset.convert;

import com.demo.gameasset.entity.SysUser;
import com.demo.gameasset.vo.UserVO;
import org.springframework.beans.BeanUtils;

/**
 * 用户实体 → VO。
 *
 * <p>用 Spring 的 {@link BeanUtils#copyProperties} 而不是 MapStruct：
 * MapStruct 需要额外配置注解处理器，一旦 processor 没挂上会
 * <b>静默生成空实现</b>——编译通过、运行期字段全是 null，
 * 排查成本远高于手写几行 copy。本项目字段量不大，反射拷贝足够。</p>
 *
 * @author 管理员
 */
public final class UserConverter {

    private UserConverter() {
    }

    public static UserVO toVO(SysUser user) {
        if (user == null) {
            return null;
        }
        UserVO vo = new UserVO();
        // 只拷同名同类型字段；password 不在 UserVO 中，自然不会泄露
        BeanUtils.copyProperties(user, vo);
        if (vo.getNickname() == null || vo.getNickname().isBlank()) {
            // 昵称没填时用登录名兜底，避免前端显示空白
            vo.setNickname(user.getUsername());
        }
        return vo;
    }
}
