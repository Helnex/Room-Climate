package com.example.roomclimate.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.example.roomclimate.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Список выбора из макета (плата / датчик / помещение): кружок, заголовок, подпись.
 * Выбранная строка — светло-зелёная с закрашенным кружком; недоступная — серая, без кружка.
 */
final class ChoiceList {
    interface OnPick {
        void picked(int index);
    }

    private final LinearLayout box;
    private final OnPick listener;
    private final List<View> rows = new ArrayList<>();
    private final List<Boolean> enabled = new ArrayList<>();
    private int selected = -1;

    ChoiceList(LinearLayout box, OnPick listener) {
        this.box = box;
        this.listener = listener;
    }

    void clear() {
        box.removeAllViews();
        rows.clear();
        enabled.clear();
        selected = -1;
    }

    int size() {
        return rows.size();
    }

    int selected() {
        return selected;
    }

    boolean isEnabled(int index) {
        return index >= 0 && index < enabled.size() && enabled.get(index);
    }

    void add(CharSequence title, CharSequence subtitle, boolean canPick) {
        View row = LayoutInflater.from(box.getContext()).inflate(R.layout.item_choice_row, box, false);
        TextView t = row.findViewById(R.id.choiceTitle);
        TextView s = row.findViewById(R.id.choiceSub);
        t.setText(title);
        if (subtitle == null || subtitle.length() == 0) {
            s.setVisibility(View.GONE);
        } else {
            s.setText(subtitle);
        }
        int index = rows.size();
        if (canPick) {
            row.setOnClickListener(v -> {
                select(index);
                listener.picked(index);
            });
        } else {
            int gray = ContextCompat.getColor(box.getContext(), R.color.gray_text_light);
            t.setTextColor(gray);
            s.setTextColor(gray);
            row.findViewById(R.id.choiceDot).setVisibility(View.INVISIBLE);
            row.setEnabled(false);
        }
        row.setContentDescription(subtitle == null ? title : title + ". " + subtitle);
        rows.add(row);
        enabled.add(canPick);
        box.addView(row);
    }

    void select(int index) {
        selected = index;
        for (int i = 0; i < rows.size(); i++) {
            View row = rows.get(i);
            boolean on = i == index;
            row.setSelected(on);
            row.setBackgroundResource(on ? R.drawable.rc_choice_selected : 0);
            ImageView dot = row.findViewById(R.id.choiceDot);
            dot.setImageResource(on ? R.drawable.rc_dot_green : R.drawable.rc_dot_ring);
        }
    }

    /** Первая доступная строка или -1. */
    int firstEnabled() {
        for (int i = 0; i < enabled.size(); i++) {
            if (enabled.get(i)) {
                return i;
            }
        }
        return -1;
    }
}
