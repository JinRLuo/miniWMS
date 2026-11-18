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
    record View(long id, String username, String displayName, Role role, boolean active) {}
    record Form(@NotBlank @Pattern(regexp="[A-Za-z0-9._-]{2,64}") String username,
                @NotBlank @Size(max=100) String displayName, @NotNull Role role, @NotNull Boolean active,
                @Size(min=12, max=72) String password) {}

    record Principal(long id, String username, String displayName, String passwordHash, Role role, boolean active)
            implements UserDetails {
        @Override public String getUsername() { return username; }
        @Override public String getPassword() { return passwordHash; }
        @Override public boolean isEnabled() { return active; }
        @Override public List<? extends GrantedAuthority> getAuthorities() {
            return List.of(new SimpleGrantedAuthority("ROLE_" + role));
        }

        View view() { return new View(id, username, displayName, role, active); }
    }

    private static final RowMapper<Principal> USER = (r, n) -> new Principal(
            r.getLong("id"), r.getString("username"), r.getString("display_name"),
            r.getString("password_hash"), Role.valueOf(r.getString("role")), r.getBoolean("active"));

    private final WarehouseDb db;
    private final PasswordEncoder encoder;

    Accounts(WarehouseDb db, PasswordEncoder encoder) {
        this.db = db;
        this.encoder = encoder;
    }

    @Override
    public Principal loadUserByUsername(String username) {
        return db.jdbc.query("SELECT * FROM users WHERE username=?", USER, normalize(username)).stream()
                .findFirst().orElseThrow(() -> new UsernameNotFoundException("账号或密码错误"));
    }

    Principal find(long id) {
        return db.one("SELECT * FROM users WHERE id=?", USER, "账号不存在", id);
    }

    List<View> list() {
        return db.jdbc.query("SELECT * FROM users ORDER BY id", USER).stream().map(Principal::view).toList();
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
            userId = db.insert("INSERT INTO users(username, display_name, password_hash, role, active) VALUES(?,?,?,?,?)",
                    username, form.displayName().trim(), password, form.role().name(), form.active());
        } else {
            for (long locked : java.util.stream.LongStream.of(actor, id).distinct().sorted().toArray()) {
                db.one("SELECT id FROM users WHERE id=? FOR UPDATE", (r, n) -> r.getLong(1), "账号不存在", locked);
            }
            var administrator = find(actor);
            if (!administrator.active() || administrator.role() != Role.ADMIN) {
                throw ApiError.conflict("账号权限已变更");
            }
            var current = find(id);
            if (id == actor && (!form.active() || form.role() != Role.ADMIN)) {
                throw ApiError.conflict("当前管理员账号必须保持启用及管理员权限");
            }
            db.jdbc.update("UPDATE users SET username=?,display_name=?,password_hash=?,role=?,active=? WHERE id=?",
                    username, form.displayName().trim(), password == null ? current.passwordHash() : password,
                    form.role().name(), form.active(), id);
            userId = id;
        }
        db.audit(actor, id == null ? "USER_CREATE" : "USER_EDIT", userId);
        return find(userId).view();
    }

    long initialize(String password) {
        return db.jdbc.query("SELECT id FROM users WHERE role='ADMIN' AND active=TRUE ORDER BY id", (r, n) -> r.getLong(1))
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
