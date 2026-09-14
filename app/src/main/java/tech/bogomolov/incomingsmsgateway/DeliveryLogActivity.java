package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The last 20 delivery attempts (see {@link DeliveryLog}), newest first. */
public class DeliveryLogActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_delivery_log);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        reload();
    }

    private void reload() {
        List<DeliveryLog.Entry> entries = DeliveryLog.getAll(this);
        ListView list = findViewById(R.id.log_list);
        list.setAdapter(new EntryAdapter(this, entries));
        findViewById(R.id.log_empty).setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.delivery_log_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == R.id.action_log_clear) {
            DeliveryLog.clear(this);
            reload();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static final class EntryAdapter extends ArrayAdapter<DeliveryLog.Entry> {

        EntryAdapter(Context context, List<DeliveryLog.Entry> entries) {
            super(context, R.layout.list_item_log, entries);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(getContext()).inflate(R.layout.list_item_log, parent, false);
            DeliveryLog.Entry entry = getItem(position);
            Context context = getContext();

            TextView title = row.findViewById(R.id.log_title);
            title.setText(context.getString(R.string.log_row_title,
                    entry.kind.isEmpty() ? "?" : entry.kind, entry.code, resultLabel(context, entry.result)));
            title.setTextColor(ContextCompat.getColor(context,
                    DeliveryStatus.RESULT_OK.equals(entry.result) ? R.color.colorSuccess
                            : DeliveryStatus.RESULT_FAILED.equals(entry.result) ? R.color.colorDanger
                            : R.color.colorMuted));

            TextView detail = row.findViewById(R.id.log_detail);
            String when = DateFormat.getDateFormat(context).format(new Date(entry.time)) + " "
                    + DateFormat.getTimeFormat(context).format(new Date(entry.time));
            String since = entry.sinceReceivedMs >= 0
                    ? String.format(Locale.getDefault(), "%.1f s", entry.sinceReceivedMs / 1000f) : "–";
            String tail = (entry.sim.isEmpty() ? "" : " · " + entry.sim)
                    + (entry.reason.isEmpty() ? "" : " · " + entry.reason);
            detail.setText(context.getString(R.string.log_row_detail,
                    when, entry.http, entry.rttMs, since) + tail);
            return row;
        }

        private static String resultLabel(Context context, String result) {
            switch (result) {
                case DeliveryStatus.RESULT_OK:
                    return context.getString(R.string.status_result_ok);
                case DeliveryStatus.RESULT_FAILED:
                    return context.getString(R.string.status_result_failed);
                default:
                    return context.getString(R.string.status_result_retrying);
            }
        }
    }
}
