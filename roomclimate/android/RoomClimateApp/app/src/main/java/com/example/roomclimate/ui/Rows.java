package com.example.roomclimate.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.roomclimate.R;

/** Строки списка из макета (item_row): иконка, заголовок, подпись, стрелка. */
public final class Rows {
    private Rows() {}

    public static View add(ViewGroup parent, int icon, CharSequence title, CharSequence subtitle, View.OnClickListener onClick) {
        View row = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_row, parent, false);
        ((ImageView) row.findViewById(R.id.rowIcon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.rowTitle)).setText(title);
        TextView sub = row.findViewById(R.id.rowSubtitle);
        if (subtitle == null || subtitle.length() == 0) {
            sub.setVisibility(View.GONE);
        } else {
            sub.setText(subtitle);
        }
        if (onClick == null) {
            row.findViewById(R.id.rowChevron).setVisibility(View.GONE);
            row.setClickable(false);
            row.setBackground(null);
        } else {
            row.setOnClickListener(onClick);
        }
        row.setContentDescription(subtitle == null ? title : title + ". " + subtitle);
        parent.addView(row);
        return row;
    }

    public static View add(ViewGroup parent, int icon, int title, int subtitle, View.OnClickListener onClick) {
        return add(parent, icon, parent.getContext().getString(title),
                subtitle == 0 ? null : parent.getContext().getString(subtitle), onClick);
    }
}
