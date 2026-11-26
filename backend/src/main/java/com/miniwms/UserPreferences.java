package com.miniwms;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class UserPreferences {
    enum WeightUnit {
        JIN, GRAM;

        BigDecimal display(BigDecimal quantity, String unit) {
            return this == GRAM && "斤".equals(unit) ? quantity.multiply(BigDecimal.valueOf(500)) : quantity;
        }

        String label(String unit) { return this == GRAM && "斤".equals(unit) ? "克" : unit; }
    }
    record Settings(@NotNull WeightUnit weightUnit) {}
    private final WarehouseDb db;
    UserPreferences(WarehouseDb db) { this.db = db; }

    Settings get(long userId) {
        return db.jdbc.query("SELECT weight_unit FROM user_preferences WHERE user_id=?",
                (r, n) -> new Settings(WeightUnit.valueOf(r.getString(1))), userId)
                .stream().findFirst().orElseGet(() -> new Settings(WeightUnit.JIN));
    }

    @Transactional
    public Settings save(long userId, Settings settings) {
        db.lockActor(userId);
        boolean exists = db.jdbc.queryForObject("SELECT COUNT(*) FROM user_preferences WHERE user_id=?", Integer.class, userId) > 0;
        if (exists) db.jdbc.update("UPDATE user_preferences SET weight_unit=? WHERE user_id=?", settings.weightUnit().name(), userId);
        else db.jdbc.update("INSERT INTO user_preferences(user_id,weight_unit) VALUES(?,?)", userId, settings.weightUnit().name());
        return settings;
    }
}

@RestController
@RequestMapping("/api/auth/preferences")
class UserPreferencesApi {
    private final UserPreferences preferences;
    UserPreferencesApi(UserPreferences preferences) { this.preferences = preferences; }

    @GetMapping
    UserPreferences.Settings get(@AuthenticationPrincipal Accounts.Principal user) { return preferences.get(user.id()); }

    @PutMapping
    UserPreferences.Settings save(@AuthenticationPrincipal Accounts.Principal user,
                                  @Valid @RequestBody UserPreferences.Settings settings) {
        return preferences.save(user.id(), settings);
    }
}
