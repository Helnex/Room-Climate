package com.example.roomclimate.ui;

import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.roomclimate.R;
import com.example.roomclimate.network.ApiClient;
import com.google.gson.JsonObject;

/** «Помощь и обратная связь» (макет, экран 27): тема и сообщение → POST /support. */
public class HelpActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SessionGuard.requireLogin(this)) {
            return;
        }
        SectionScreen s = SectionScreen.setUp(this, R.string.help_title, R.string.help_heading, R.string.help_desc);
        EditText topic = s.field(R.string.help_topic, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, "");
        topic.setHint(R.string.help_topic_hint);
        EditText message = s.field(R.string.help_message,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, "");
        message.setHint(R.string.help_message_hint);
        message.setSingleLine(false);
        message.setMinLines(4);
        message.setGravity(android.view.Gravity.TOP);
        ((android.view.View) message.getParent()).getLayoutParams().height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        ((android.view.View) message.getParent()).setPadding(pad, pad, pad, pad);
        s.button(R.string.help_send, v -> {
            String t = topic.getText().toString().trim();
            String m = message.getText().toString().trim();
            if (t.isEmpty() || m.isEmpty()) {
                Toast.makeText(this, R.string.help_fill, Toast.LENGTH_SHORT).show();
                return;
            }
            JsonObject body = new JsonObject();
            body.addProperty("topic", t);
            body.addProperty("message", m);
            ApiClient.get(this).sendSupport(body).enqueue(Api.call(this, ok -> {
                boolean emailed = ok.has("emailed") && ok.get("emailed").getAsBoolean();
                Toast.makeText(this, emailed ? R.string.help_sent_email : R.string.help_sent, Toast.LENGTH_LONG).show();
                finish();
            }));
        });
        s.note(getString(R.string.help_note));
    }
}
