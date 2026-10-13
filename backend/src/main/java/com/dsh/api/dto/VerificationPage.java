package com.dsh.api.dto;

import java.util.List;

public record VerificationPage(
        List<Verification> items,
        String nextCursor,
        boolean hasMore
) {
}
