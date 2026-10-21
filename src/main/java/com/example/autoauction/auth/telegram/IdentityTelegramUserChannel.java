package com.example.autoauction.auth.telegram;

import com.example.autoauction.auth.domain.BotAccess;
import com.example.autoauction.auth.domain.ExternalIdentityProvider;
import com.example.autoauction.auth.domain.port.ExternalIdentityRepository;
import org.springframework.stereotype.Component;

/** AUTH-TELEGRAM-01: answers from the identity's own bot-access state (GRANTED only after an explicit grant). */
@Component
public class IdentityTelegramUserChannel implements TelegramUserChannel {

    private final ExternalIdentityRepository identities;

    public IdentityTelegramUserChannel(ExternalIdentityRepository identities) {
        this.identities = identities;
    }

    @Override
    public boolean canMessage(Long userId) {
        return identities.findByUserIdAndProvider(userId, ExternalIdentityProvider.TELEGRAM)
                .map(identity -> identity.getBotAccess() == BotAccess.GRANTED)
                .orElse(false);
    }
}
