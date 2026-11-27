package com.miniwms;

import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class Accounts implements UserDetailsService {
    enum Role { ADMIN, OPERATOR }
    record View(long id, String username, String displayName, Role role, boolean active,
                boolean approved, String phone, boolean wechatBound) {}
    record Form(@NotBlank @Pattern(regexp="[A-Za-z0-9._-]{2,64}") String username,
                @NotBlank @Size(max=100) String displayName, @NotNull Role role, @NotNull Boolean active,
                @Size(min=12, max=72) String password, Boolean approved) {
        Form(String username, String displayName, Role role, Boolean active, String password) {
            this(username, displayName, role, active, password, null);
        }
    }
    record PasswordChange(@NotEmpty @Size(max=72) String currentPassword,
                          @NotBlank @Size(min=12, max=72) String newPassword,
                          @NotBlank @Size(max=72) String confirmPassword) {}

    record Principal(long id, String username, String displayName, String passwordHash, Role role, boolean active,
                     boolean approved, String phone, boolean wechatBound)
            implements UserDetails {
        @Override public String getUsername() { return username; }
        @Override public String getPassword() { return passwordHash; }
        @Override public boolean isEnabled() { return active; }
        @Override public List<? extends GrantedAuthority> getAuthorities() {
            return List.of(new SimpleGrantedAuthority("ROLE_" + (approved ? role : "PENDING")));
        }

        View view() { return new View(id, username, displayName, role, active, approved, phone, wechatBound); }
    }

    private static final RowMapper<Principal> USER = (r, n) -> new Principal(
            r.getLong("id"), r.getString("username"), r.getString("display_name"),
            r.getString("password_hash"), Role.valueOf(r.getString("role")), r.getBoolean("active"),
            r.getBoolean("approved"), r.getString("phone"), "WECHAT".equals(r.getString("login_provider")));

    private final WarehouseDb db;
    private final PasswordEncoder encoder;

    Accounts(WarehouseDb db, PasswordEncoder encoder) {
        this.db = db;
        this.encoder = encoder;
    }

    @Override
    public Principal loadUserByUsername(String username) {
        return db.jdbc.query("SELECT * FROM users WHERE username=? AND login_provider='PASSWORD'", USER, normalize(username)).stream()
                .findFirst().orElseThrow(() -> new UsernameNotFoundException("账号或密码错误"));
    }

    Principal find(long id) {
        return db.one("SELECT * FROM users WHERE id=?", USER, "账号不存在", id);
    }

    List<View> list() {
        return db.jdbc.query("SELECT * FROM users ORDER BY id", USER).stream().map(Principal::view).toList();
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void changePassword(long userId, PasswordChange form) {
        db.lockActor(userId);
        var current = find(userId);
        if (current.wechatBound()) throw ApiError.bad("微信账号请使用微信登录，无需设置仓库密码");
        if (form.currentPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || !encoder.matches(form.currentPassword(), current.passwordHash()))
            throw ApiError.bad("当前密码不正确，请重新输入");
        if (!form.newPassword().equals(form.confirmPassword())) throw ApiError.bad("两次输入的新密码不一致");
        if (form.currentPassword().equals(form.newPassword())) throw ApiError.bad("新密码不能与当前密码相同");
        String password = hash(form.newPassword());
        db.jdbc.update("UPDATE users SET password_hash=? WHERE id=?", password, userId);
        db.audit(userId, "PASSWORD_CHANGE", userId);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public View save(Long id, Form form, long actor) {
        String username = normalize(form.username());
        String password = form.password() == null ? null : hash(form.password());
        long userId;
        if (id == null) {
            db.lockActor(actor);
            if (password == null) throw ApiError.bad("请设置初始密码");
            if (find(actor).role() != Role.ADMIN) throw ApiError.conflict("账号权限已变更");
            userId = db.insert("INSERT INTO users(username, display_name, password_hash, role, active, approved) VALUES(?,?,?,?,?,?)",
                    username, form.displayName().trim(), password, form.role().name(), form.active(), form.approved() == null || form.approved());
        } else {
            for (long locked : java.util.stream.LongStream.of(actor, id).distinct().sorted().toArray()) {
                db.one("SELECT id FROM users WHERE id=? FOR UPDATE", (r, n) -> r.getLong(1), "账号不存在", locked);
            }
            var administrator = find(actor);
            if (!administrator.active() || !administrator.approved() || administrator.role() != Role.ADMIN) {
                throw ApiError.conflict("账号权限已变更");
            }
            var current = find(id);
            if (id == actor && (!form.active() || Boolean.FALSE.equals(form.approved()) || form.role() != Role.ADMIN)) {
                throw ApiError.conflict("当前管理员账号必须保持启用及管理员权限");
            }
            if (current.wechatBound() && password != null) throw ApiError.bad("微信账号不支持设置或重置仓库密码");
            db.jdbc.update("UPDATE users SET username=?,display_name=?,password_hash=?,role=?,active=?,approved=? WHERE id=?",
                    username, form.displayName().trim(), password == null ? current.passwordHash() : password,
                    form.role().name(), form.active(), form.approved() == null ? current.approved() : form.approved(), id);
            userId = id;
        }
        db.audit(actor, id == null ? "USER_CREATE" : "USER_EDIT", userId);
        return find(userId).view();
    }

    long initialize(String password) {
        return db.jdbc.query("SELECT id FROM users WHERE role='ADMIN' AND active=TRUE AND approved=TRUE ORDER BY id", (r, n) -> r.getLong(1))
                .stream().findFirst().orElseGet(() -> {
                    if (password.isBlank()) throw new IllegalStateException("首次启动请设置 WMS_ADMIN_PASSWORD");
                    return db.insert(
                            "INSERT INTO users(username,display_name,password_hash,role) VALUES('admin',?,?,'ADMIN')",
                            "仓库管理员", hash(password));
                });
    }

    private String hash(String password) {
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.length() < 12 || bytes > 72) throw ApiError.bad("密码至少 12 个字符，最多 72 字节");
        return encoder.encode(password);
    }

    private String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
