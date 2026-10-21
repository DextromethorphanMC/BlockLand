package com.blockland.backend.common;

import java.util.List;
import java.util.regex.Pattern;

public final class Ids {
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9_\\-]{1,47}$");

    private Ids() {
    }

    public static void check(String id, String field, List<String> errors) {
        if (id == null || !ID.matcher(id).matches()) {
            errors.add(field + " 只能包含小写字母、数字、下划线和短横线（2-48 位）");
        }
    }
}
