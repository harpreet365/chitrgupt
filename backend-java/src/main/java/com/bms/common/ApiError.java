package com.bms.common;

import java.time.OffsetDateTime;
import java.util.Map;

/** The single JSON shape used for every error response. */
public record ApiError(OffsetDateTime timestamp, int status, String code, String message, String path,
                       Map<String, String> fieldErrors, String reference) {

    public static ApiError of(int status, String code, String message, String path) {
        return new ApiError(OffsetDateTime.now(), status, code, message, path, null, null);
    }
}
