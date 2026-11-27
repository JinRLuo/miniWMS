package com.miniwms;

import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class WeChatAccounts {
    private final WarehouseDb db;
    private final Accounts accounts;
    private final PasswordEncoder encoder;
    private final TransactionTemplate transaction;

    WeChatAccounts(WarehouseDb db, Accounts accounts, PasswordEncoder encoder, PlatformTransactionManager manager) {
        this.db = db; this.accounts = accounts; this.encoder = encoder;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    Accounts.Principal login(WeChatClient.Identity identity) {
        // A concurrent first login may win the unique identity key. Roll back the entire
        // losing transaction (including its new user), then look up the committed identity.
        for (int attempt = 0; attempt < 2; attempt++) {
            try { return transaction.execute(status -> resolve(identity)); }
            catch (DuplicateKeyException conflict) {
                if (attempt == 1) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "登录繁忙，请重试");
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    private Accounts.Principal resolve(WeChatClient.Identity identity) {
        var ids = db.jdbc.query("SELECT user_id FROM wechat_accounts WHERE app_id=? AND open_id=?",
                (r, n) -> r.getLong(1), identity.appId().getBytes(StandardCharsets.UTF_8), identity.openId().getBytes(StandardCharsets.UTF_8));
        long id;
        if (ids.isEmpty()) {
            String phone = identity.phone();
            String suffix = phone.substring(Math.max(0, phone.length() - 4));
            id = db.insert("""
                    INSERT INTO users(username,display_name,password_hash,role,active,approved,phone,login_provider)
                    VALUES(?,?,?,'OPERATOR',TRUE,FALSE,?,'WECHAT')
                    """, "wx_" + UUID.randomUUID().toString().replace("-", ""), "微信用户" + suffix,
                    encoder.encode(UUID.randomUUID().toString()), phone);
            db.jdbc.update("INSERT INTO wechat_accounts(user_id,app_id,open_id) VALUES(?,?,?)", id,
                    identity.appId().getBytes(StandardCharsets.UTF_8), identity.openId().getBytes(StandardCharsets.UTF_8));
            db.audit(id, "WECHAT_REGISTER", id);
        } else {
            id = ids.get(0);
            db.one("SELECT id FROM users WHERE id=? FOR UPDATE", (r, n) -> r.getLong(1), "账号不存在", id);
            if (!accounts.find(id).active()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已停用，请联系管理员");
            db.jdbc.update("UPDATE users SET phone=? WHERE id=?", identity.phone(), id);
        }
        db.audit(id, "WECHAT_LOGIN", id);
        return accounts.find(id);
    }
}
