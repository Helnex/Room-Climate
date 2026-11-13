package com.example.roomclimate.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import com.example.roomclimate.R;

/** Общая раскладка экранов-разделов (activity_section): заголовок, описание, карточка, кнопка. */
final class SectionScreen {
    final Activity activity;
    final LinearLayout card;
    final LinearLayout extra;
    final Button primary;
    final TextView note;

    private SectionScreen(Activity activity) {
        this.activity = activity;
        this.card = activity.findViewById(R.id.sectionCard);
        this.extra = activity.findViewById(R.id.sectionExtra);
        this.primary = activity.findViewById(R.id.btnSectionPrimary);
        this.note = activity.findViewById(R.id.tvSectionNote);
    }

    static SectionScreen setUp(Activity activity, int title, int heading, int desc) {
        activity.setContentView(R.layout.activity_section);
        ScreenHeader.bindSub(activity, title);
        TextView h = activity.findViewById(R.id.tvSectionHeading);
        if (heading != 0) {
            h.setText(heading);
            h.setVisibility(View.VISIBLE);
        }
        ((TextView) activity.findViewById(R.id.tvSectionDesc)).setText(desc);
        return new SectionScreen(activity);
    }

    SectionScreen button(int text, View.OnClickListener onClick) {
        primary.setText(text);
        primary.setVisibility(View.VISIBLE);
        primary.setOnClickListener(onClick);
        return this;
    }

    SectionScreen note(CharSequence text) {
        note.setText(text);
        return this;
    }

    EditText field(int label, int inputType, CharSequence value) {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_field, card, false);
        TextView l = row.findViewById(R.id.fieldLabel);
        EditText input = row.findViewById(R.id.fieldInput);
        l.setText(label);
        l.setLabelFor(R.id.fieldInput);
        input.setInputType(inputType);
        input.setText(value);
        card.addView(row);
        return input;
    }

    SwitchCompat toggle(int title, CharSequence subtitle, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_switch, card, false);
        ((TextView) row.findViewById(R.id.switchTitle)).setText(title);
        TextView sub = row.findViewById(R.id.switchSubtitle);
        if (subtitle == null) {
            sub.setVisibility(View.GONE);
        } else {
            sub.setText(subtitle);
        }
        SwitchCompat sw = row.findViewById(R.id.switchToggle);
        sw.setChecked(checked);
        sw.setContentDescription(activity.getString(title));
        if (listener != null) {
            sw.setOnCheckedChangeListener(listener);
        }
        row.setOnClickListener(v -> sw.toggle());
        card.addView(row);
        return sw;
    }

    TextView group(int text) {
        TextView g = new TextView(activity);
        g.setTextAppearance(R.style.RC_Text_Group);
        g.setText(text);
        int pad = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        g.setPadding(pad, pad / 2, pad, pad / 4);
        card.addView(g);
        return g;
    }
}
