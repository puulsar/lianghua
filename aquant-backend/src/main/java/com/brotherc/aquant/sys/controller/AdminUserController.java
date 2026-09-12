package com.brotherc.aquant.sys.controller;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.brotherc.aquant.common.exception.ExceptionEnum;
import com.brotherc.aquant.common.model.dto.ResponseDTO;
import com.brotherc.aquant.common.utils.UserContext;
import com.brotherc.aquant.sys.entity.SysUser;
import com.brotherc.aquant.sys.repository.SysUserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户管理（超级管理员）。新增/编辑/启停/删除/重置密码。
 */
@RestController
@RequestMapping("/admin/user")
@RequiredArgsConstructor
@Tag(name = "用户管理（后台）")
public class AdminUserController {

    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_USER = "user";

    private final SysUserRepository sysUserRepository;

    private void requireAdmin() {
        UserContext.requireAdmin();
    }

    @Operation(summary = "用户列表")
    @GetMapping("/list")
    public ResponseDTO<List<UserAdminVO>> list() {
        requireAdmin();
        List<UserAdminVO> result = new ArrayList<>();
        for (SysUser u : sysUserRepository.findAll()) {
            result.add(toVO(u));
        }
        return ResponseDTO.success(result);
    }

    @Operation(summary = "新增用户")
    @PostMapping("/create")
    public ResponseDTO<Void> create(@RequestBody CreateReq req) {
        requireAdmin();
        String username = req.getUsername() == null ? "" : req.getUsername().trim();
        if (username.isEmpty() || req.getPassword() == null || req.getPassword().isEmpty()) {
            throw ExceptionEnum.SYS_CHECK_ERROR.toException();
        }
        if (sysUserRepository.existsByUsername(username)) {
            throw ExceptionEnum.AUTH_USERNAME_EXISTS.toException();
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(BCrypt.withDefaults().hashToString(12, req.getPassword().toCharArray()));
        user.setNickname(StringUtils.isBlank(req.getNickname()) ? null : req.getNickname().trim());
        user.setEmail(StringUtils.isBlank(req.getEmail()) ? null : req.getEmail().trim());
        user.setStatus(1);
        user.setRole(ROLE_USER);
        sysUserRepository.save(user);
        return ResponseDTO.success();
    }

    @Operation(summary = "编辑用户（昵称/邮箱/角色）")
    @PostMapping("/update")
    public ResponseDTO<Void> update(@RequestBody UpdateReq req) {
        requireAdmin();
        SysUser user = findUser(req.id);
        if (StringUtils.isNotBlank(req.nickname)) {
            user.setNickname(req.nickname.trim());
        }
        if (req.email != null) {
            user.setEmail(req.email.trim().isEmpty() ? null : req.email.trim());
        }
        if (StringUtils.isNotBlank(req.role)) {
            String role = req.role.trim().toLowerCase();
            if (!ROLE_ADMIN.equals(role) && !ROLE_USER.equals(role)) {
                throw ExceptionEnum.SYS_CHECK_ERROR.toException();
            }
            // 保护最后一个管理员：禁止把仅剩的 admin 降级
            if (ROLE_USER.equals(role) && ROLE_ADMIN.equals(user.getRole()) && isLastAdmin(user.getId())) {
                throw ExceptionEnum.AUTH_ACCESS_DENIED.toException();
            }
            user.setRole(role);
        }
        sysUserRepository.save(user);
        return ResponseDTO.success();
    }

    @Operation(summary = "启用/禁用用户")
    @PostMapping("/status")
    public ResponseDTO<Void> status(@RequestBody StatusReq req) {
        requireAdmin();
        SysUser user = findUser(req.id);
        int newStatus = req.status == 1 ? 1 : 0;
        if (newStatus == 0 && user.getRole() != null && user.getRole().equals(ROLE_ADMIN) && isLastAdmin(user.getId())) {
            throw ExceptionEnum.AUTH_ACCESS_DENIED.toException();
        }
        user.setStatus(newStatus);
        sysUserRepository.save(user);
        return ResponseDTO.success();
    }

    @Operation(summary = "重置密码")
    @PostMapping("/reset-password")
    public ResponseDTO<Void> resetPassword(@RequestBody ResetPasswordReq req) {
        requireAdmin();
        if (req.password == null || req.password.isEmpty()) {
            throw ExceptionEnum.SYS_CHECK_ERROR.toException();
        }
        SysUser user = findUser(req.id);
        user.setPassword(BCrypt.withDefaults().hashToString(12, req.password.toCharArray()));
        sysUserRepository.save(user);
        return ResponseDTO.success();
    }

    @Operation(summary = "删除用户（禁止删自己及最后一个管理员）")
    @PostMapping("/delete")
    public ResponseDTO<Void> delete(@RequestBody DeleteReq req) {
        Long current = UserContext.requireAdmin();
        if (current != null && current.equals(req.id)) {
            throw ExceptionEnum.SYS_CHECK_ERROR.toException();
        }
        SysUser user = findUser(req.id);
        if (user.getRole() != null && user.getRole().equals(ROLE_ADMIN) && isLastAdmin(user.getId())) {
            throw ExceptionEnum.AUTH_ACCESS_DENIED.toException();
        }
        sysUserRepository.delete(user);
        return ResponseDTO.success();
    }

    private SysUser findUser(Long id) {
        if (id == null) {
            throw ExceptionEnum.SYS_CHECK_ERROR.toException();
        }
        return sysUserRepository.findById(id)
                .orElseThrow(ExceptionEnum.AUTH_USER_NOT_FOUND::toException);
    }

    private boolean isLastAdmin(Long userId) {
        long adminCount = sysUserRepository.findAll().stream()
                .filter(u -> u.getRole() != null && u.getRole().equals(ROLE_ADMIN))
                .count();
        return adminCount <= 1;
    }

    private UserAdminVO toVO(SysUser u) {
        UserAdminVO vo = new UserAdminVO();
        vo.id = u.getId();
        vo.username = u.getUsername();
        vo.nickname = u.getNickname();
        vo.email = u.getEmail();
        vo.status = u.getStatus();
        vo.role = u.getRole();
        vo.createdAt = u.getCreatedAt();
        vo.updatedAt = u.getUpdatedAt();
        return vo;
    }

    @Data
    public static class CreateReq {
        private String username;
        private String password;
        private String nickname;
        private String email;
    }

    @Data
    public static class UpdateReq {
        private Long id;
        private String nickname;
        private String email;
        private String role;
    }

    @Data
    public static class StatusReq {
        private Long id;
        private Integer status;
    }

    @Data
    public static class ResetPasswordReq {
        private Long id;
        private String password;
    }

    @Data
    public static class DeleteReq {
        private Long id;
    }

    @Data
    public static class UserAdminVO {
        private Long id;
        private String username;
        private String nickname;
        private String email;
        private Integer status;
        private String role;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }
}