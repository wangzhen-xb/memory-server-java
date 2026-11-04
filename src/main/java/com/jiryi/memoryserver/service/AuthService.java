package com.jiryi.memoryserver.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jiryi.memoryserver.common.BizException;
import com.jiryi.memoryserver.common.MaskUtils;
import com.jiryi.memoryserver.common.ResultCode;
import com.jiryi.memoryserver.config.MemoryProperties;
import com.jiryi.memoryserver.dto.LoginDTO;
import com.jiryi.memoryserver.dto.RegisterDTO;
import com.jiryi.memoryserver.entity.UserPO;
import com.jiryi.memoryserver.mapper.UserMapper;
import com.jiryi.memoryserver.vo.LoginVO;
import com.jiryi.memoryserver.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 注册 / 登录 / 登出
 *
 * 安全要点：
 * 1. 密码 scrypt 加盐哈希，绝不明文入库；
 * 2. 登录失败计数 + 锁定，防爆破（原 Node 版完全没有这层）；
 * 3. 账号不存在与密码错误返回同一提示，不泄露"用户名是否存在"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 账号或密码错误的统一话术，避免用户名枚举 */
    private static final String CREDENTIAL_ERROR = "用户名或密码错误";

    private final UserMapper userMapper;

    private final SessionService sessionService;

    private final RedisSessionStore redisStore;

    private final PasswordEncoder passwordEncoder;

    private final MemoryProperties props;

    /**
     * 注册并直接建立会话（与原 Node 行为一致）
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginVO register(RegisterDTO dto) {
        String username = trim(dto.getUsername());
        String nickname = trim(dto.getNickName());
        // 动态阈值走配置，注解里的静态上限只是兜底
        checkLength(username, props.getUsernameMaxLength(), "用户名长度不合法");
        checkLength(dto.getPassword(), props.getPasswordMaxLength(), "密码长度不合法");
        if (dto.getPassword().length() < props.getPasswordMinLength()) {
            throw BizException.badRequest("密码至少 " + props.getPasswordMinLength() + " 位");
        }
        // 昵称为空回落用户名，与原实现一致
        String nickName = nickname.isEmpty() ? username : nickname;
        checkLength(nickName, props.getNicknameMaxLength(), "昵称长度不合法");

        if (existsByUsername(username)) {
            throw BizException.conflict("用户名已存在");
        }
        UserPO po = new UserPO();
        po.setUsername(username);
        po.setNickName(nickName);
        po.setPassword(encode(dto.getPassword()));
        try {
            userMapper.insert(po);
        } catch (DuplicateKeyException e) {
            // 并发注册的最终防线：唯一索引冲突，不查第二次
            throw BizException.conflict("用户名已存在");
        }
        String token = sessionService.create(po.getId());
        log.info("注册成功 userId={}, username={}", po.getId(), MaskUtils.maskUsername(username));
        return new LoginVO(token, UserVO.from(po));
    }

    /**
     * 登录：先判锁定，再校验密码，成功则清计数并按需升级哈希格式
     */
    public LoginVO login(LoginDTO dto) {
        String username = trim(dto.getUsername());
        long lockSeconds = redisStore.loginLockSeconds(username);
        if (lockSeconds > 0) {
            // 向上取整：剩 15 分钟不应报 16 分钟，不足 1 分钟也不能报 0
            throw BizException.locked("账号已锁定，请 " + ((lockSeconds + 59) / 60) + " 分钟后重试");
        }

        UserPO po = findByUsername(username);
        boolean matched = po != null && verify(dto.getPassword(), po.getPassword());
        if (!matched) {
            handleLoginFail(username);
            throw new BizException(ResultCode.BAD_CREDENTIALS, CREDENTIAL_ERROR);
        }

        redisStore.clearLoginFail(username);
        // 存量无标识前缀的格式，顺带升级；参数与生成时一致，比对结果不受影响
        if (passwordEncoder.isLegacy(po.getPassword())) {
            UserPO update = new UserPO();
            update.setId(po.getId());
            update.setPassword(passwordEncoder.upgrade(po.getPassword()));
            userMapper.updateById(update);
            log.info("存量密码格式已升级 userId={}", po.getId());
        }
        String token = sessionService.create(po.getId());
        log.info("登录成功 userId={}, username={}", po.getId(), MaskUtils.maskUsername(username));
        return new LoginVO(token, UserVO.from(po));
    }

    /** 当前登录用户信息 */
    public UserVO currentUser(Long userId) {
        UserPO po = userMapper.selectById(userId);
        if (po == null) {
            throw BizException.notFound("用户不存在");
        }
        return UserVO.from(po);
    }

    public void logout(String token) {
        sessionService.destroy(token);
    }

    // ==================== 内部方法 ====================

    private void handleLoginFail(String username) {
        long count = redisStore.increaseLoginFail(username);
        // -1 表示 Redis 不可用：只做审计日志，不锁定，避免缓存故障阻断正常登录
        if (count > 0 && count >= props.getLoginFailMax()) {
            redisStore.markLoginLock(username);
            log.warn("登录连续失败达阈值已锁定 username={}, count={}", MaskUtils.maskUsername(username), count);
        }
    }

    private boolean existsByUsername(String username) {
        return userMapper.exists(Wrappers.<UserPO>lambdaQuery().eq(UserPO::getUsername, username));
    }

    /** 取登录校验与响应所需列；deleted 已由 @TableField(select = false) 排除，password 不离开本层 */
    private UserPO findByUsername(String username) {
        return userMapper.selectOne(Wrappers.<UserPO>lambdaQuery()
                .eq(UserPO::getUsername, username));
    }

    private String encode(String raw) {
        MemoryProperties.Scrypt s = props.getScrypt();
        return passwordEncoder.encode(raw, s.getN(), s.getR(), s.getP(), s.getDkLen(), s.getSaltBytes());
    }

    private boolean verify(String raw, String stored) {
        MemoryProperties.Scrypt s = props.getScrypt();
        return passwordEncoder.matches(raw, stored, s.getN(), s.getR(), s.getP(), s.getDkLen());
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static void checkLength(String value, int max, String msg) {
        if (value.isEmpty() || value.length() > max) {
            throw BizException.badRequest(msg);
        }
        if (!Objects.equals(value, value.trim())) {
            throw BizException.badRequest("字段首尾不能留空格");
        }
    }
}
