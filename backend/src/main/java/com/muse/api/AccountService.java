package com.muse.api;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;

import static com.muse.api.ApiModels.AccountLogin;
import static com.muse.api.ApiModels.AccountRegister;
import static com.muse.api.ApiModels.PasswordChange;

@Service
public class AccountService {
    private final StoreMapper store;
    private final PasswordEncoder passwords;
    private final Clock clock;

    public AccountService(StoreMapper store, PasswordEncoder passwords, Clock clock) {
        this.store = store;
        this.passwords = passwords;
        this.clock = clock;
    }

    @Transactional
    public Rows.AccountRow register(String ownerId, AccountRegister input) {
        if (store.findAccountByIdentity(ownerId) != null) {
            throw ApiException.conflict("当前已登录账号，请先退出");
        }
        String email = normalize(input.email());
        if (store.findAccountByEmail(email) != null) {
            throw ApiException.conflict("该邮箱已注册，请直接登录");
        }
        ensurePasswordFits(input.password());
        var account = new Rows.AccountRow();
        account.identityId = ownerId;
        account.email = email;
        account.passwordHash = passwords.encode(input.password());
        account.createdAt = clock.instant();
        account.updatedAt = account.createdAt;
        try {
            store.insertAccount(account);
        } catch (DuplicateKeyException exception) {
            throw ApiException.conflict("该邮箱已注册，请直接登录");
        }
        // Elevating an anonymous identity must not leave other anonymous sessions with account access.
        store.deleteSessionsByOwner(ownerId);
        return account;
    }

    @Transactional
    public Rows.AccountRow login(String ownerId, AccountLogin input) {
        Rows.AccountRow target = store.findAccountByEmail(normalize(input.email()));
        if (!fits(input.password()) || target == null || !passwords.matches(input.password(), target.passwordHash)) {
            throw ApiException.unauthorized("邮箱或密码不正确");
        }
        Rows.AccountRow current = store.findAccountByIdentity(ownerId);
        if (current != null && !current.identityId.equals(target.identityId)) {
            throw ApiException.conflict("当前已登录其他账号，请先退出");
        }
        if (current == null) {
            store.moveTasks(ownerId, target.identityId);
            store.moveAssets(ownerId, target.identityId);
            store.deleteSessionsByOwner(ownerId);
            store.deleteIdentityIfEmpty(ownerId);
        }
        return target;
    }

    @Transactional
    public void changePassword(String ownerId, PasswordChange input) {
        Rows.AccountRow account = store.findAccountByIdentity(ownerId);
        if (account == null) throw ApiException.unauthorized("请先登录账号");
        if (!fits(input.currentPassword()) || !passwords.matches(input.currentPassword(), account.passwordHash)) {
            throw ApiException.badRequest("当前密码不正确");
        }
        ensurePasswordFits(input.newPassword());
        store.updatePassword(ownerId, passwords.encode(input.newPassword()), clock.instant());
        // Password changes revoke every existing session; the controller then issues one fresh cookie.
        store.deleteSessionsByOwner(ownerId);
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void ensurePasswordFits(String password) {
        if (!fits(password)) throw ApiException.badRequest("密码不能超过 72 字节");
    }

    private boolean fits(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
