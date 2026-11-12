package com.example.roomclimate.ui;

import java.util.regex.Pattern;

/**
 * Проверка email перед отправкой на сервер.
 * android.util.Patterns.EMAIL_ADDRESS не пропускает кириллицу (иван@почта.рф),
 * поэтому правило то же, что на сервере: «что-то@домен.зона».
 */
public final class AuthValidation {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private AuthValidation() {}

    public static boolean isEmail(String value) {
        return value != null && EMAIL.matcher(value.trim()).matches();
    }
}
