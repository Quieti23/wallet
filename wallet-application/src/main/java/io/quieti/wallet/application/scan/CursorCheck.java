package io.quieti.wallet.application.scan;

import java.util.Objects;

public record CursorCheck(boolean canonical, String reason) {

    public CursorCheck {
        reason = Objects.requireNonNullElse(reason, "");
    }
}