package com.example.roomclimate.ui;

import android.text.InputType;
import android.widget.EditText;
import android.widget.ImageButton;

import com.example.roomclimate.R;

/**
 * Кнопка-«глаз» в поле пароля: показать / скрыть введённые символы.
 * Курсор остаётся на месте, шрифт не меняется (иначе Android ставит моноширинный).
 */
public final class PasswordToggle {
    private PasswordToggle() {}

    public static void attach(EditText field, ImageButton eye) {
        render(field, eye, false);
        eye.setOnClickListener(v -> {
            boolean visible = (field.getInputType() & InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                    == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
            render(field, eye, !visible);
        });
    }

    private static void render(EditText field, ImageButton eye, boolean show) {
        int selection = field.getSelectionEnd();
        android.graphics.Typeface typeface = field.getTypeface();
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | (show ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
        field.setTypeface(typeface);
        if (selection >= 0) {
            field.setSelection(Math.min(selection, field.length()));
        }
        eye.setImageResource(show ? R.drawable.ic_eye_off : R.drawable.ic_eye);
        eye.setContentDescription(eye.getContext().getString(
                show ? R.string.password_hide : R.string.password_show));
    }
}
