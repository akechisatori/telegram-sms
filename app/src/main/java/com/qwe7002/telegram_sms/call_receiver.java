package com.qwe7002.telegram_sms;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static android.content.Context.MODE_PRIVATE;
import static android.support.v4.content.PermissionChecker.checkSelfPermission;

public class call_receiver extends BroadcastReceiver {
    private static int slot;
    private static String incoming_number;
    @Override
    public void onReceive(Context context, Intent intent) {
        switch (Objects.requireNonNull(intent.getAction())) {
            case "android.intent.action.PHONE_STATE":
                if (intent.getStringExtra("incoming_number") != null) {
                    incoming_number = intent.getStringExtra("incoming_number");
                }
                TelephonyManager telephony = (TelephonyManager) context
                        .getSystemService(Context.TELEPHONY_SERVICE);
                call_state_listener custom_phone_listener = new call_state_listener(context, slot, incoming_number);
                telephony.listen(custom_phone_listener, PhoneStateListener.LISTEN_CALL_STATE);
                break;
            case "android.intent.action.SUBSCRIPTION_PHONE_STATE":
                slot = intent.getIntExtra("slot", -1);

        }
    }
}

class call_state_listener extends PhoneStateListener {
    private static int lastState = TelephonyManager.CALL_STATE_IDLE;
    private Context context;
    private int slot;
    private static String incoming_number;

    call_state_listener(Context context, int slot, String incoming_number) {
        super();
        this.context = context;
        this.slot = slot;
        call_state_listener.incoming_number = incoming_number;
    }

    public void onCallStateChanged(int state, String incomingNumber) {
        if (lastState == TelephonyManager.CALL_STATE_RINGING
                && state == TelephonyManager.CALL_STATE_IDLE) {
            when_miss_call();
        }

        lastState = state;
    }

    private void when_miss_call() {
        HashMap<String,Object> map = new HashMap<>();
        ArrayList<Object> slots = new ArrayList<>();
        map.put("method","call");
        String dual_sim = "";
        int slot_count = public_func.get_active_card(context);
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            for (int i=0;i < slot_count;i++) {
                HashMap<String,Object> card = new HashMap<>();
                card.put("name",public_func.get_sim_display_name(context, i));
                SubscriptionInfo info = SubscriptionManager.from(context).getActiveSubscriptionInfoForSimSlotIndex(i);
                if (info == null) {
                    if (slot_count == 1 && i == 0) {
                        info = SubscriptionManager.from(context).getActiveSubscriptionInfoForSimSlotIndex(1);
                    }
                }
                card.put("slot",info.getSimSlotIndex());
                card.put("roaming",info.getDataRoaming());
                card.put("number",info.getNumber());
                card.put("iso",info.getCountryIso());
                slots.add(card);
            }
        }


        map.put("current_slot",slot);

        map.put("slots",slots);
        map.put("timestamp",System.currentTimeMillis()/1000);
        if (slot_count >= 2) {
            String display_name = public_func.get_sim_name_title(context, slot);
            String display = "";
            if (display_name != null) {
                display = "(" + display_name + ")";
            }
            if (slot != -1) {
                dual_sim = "SIM" + (slot + 1) + display + " ";
            }
        }



        final SharedPreferences sharedPreferences = context.getSharedPreferences("data", MODE_PRIVATE);
        if (!sharedPreferences.getBoolean("initialized", false)) {
            public_func.write_log(context, "Receive Phone:Uninitialized");
            return;
        }
        String bot_token = sharedPreferences.getString("bot_token", "");
        String chat_id = sharedPreferences.getString("chat_id", "");
        String request_uri = public_func.get_url(bot_token, "sendMessage");
        final request_json request_body = new request_json();
        request_body.chat_id = chat_id;
        String display_address = incoming_number;
        if (display_address != null) {
            String display_name = public_func.get_contact_name(context, incoming_number);
            map.put("mobile",incoming_number);
            map.put("contact",display_name);

            if (display_name != null) {
                display_address = display_name + "(" + incoming_number + ")";
            }
        }

        Log.d("call",map.toString());
        request_body.text = "[" + dual_sim + context.getString(R.string.missed_call_head) + "]" + "\n" + context.getString(R.string.Incoming_number) + display_address;
        Gson gson = new Gson();


        String raw_json = gson.toJson(map);


        String request_body_raw = gson.toJson(request_body);

        String callback_url = public_func.get_callback_addr(context);

        RequestBody body = RequestBody.create(public_func.JSON, request_body_raw);
        RequestBody callback_body = RequestBody.create(public_func.JSON,raw_json);

        OkHttpClient okhttp_client = public_func.get_okhttp_obj();
        Request request = new Request.Builder().url(request_uri).method("POST", body).build();

        Request callback = new Request.Builder().url(callback_url).method("POST",callback_body).build();

        Call call = okhttp_client.newCall(request);

        Call req_callback = okhttp_client.newCall(callback);

        req_callback.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {

            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.code() != 200) {
                    assert response.body() != null;
                    String error_message = "Call Missed forwarding to Callback failed:" + response.body().string();
                    public_func.write_log(context, error_message);
                    public_func.write_log(context, "message body:" + request_body.text);
                }
                if (response.code() == 200) {
                    assert response.body() != null;
                    String result = response.body().string();
                    Log.d("CallbackResult",result);
                }
            }
        });
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                String error_message = "Send missed call error:" + e.getMessage();
                public_func.write_log(context, error_message);
                if (checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                    if (sharedPreferences.getBoolean("fallback_sms", false)) {
                        String msg_send_to = sharedPreferences.getString("trusted_phone_number", null);
                        String msg_send_content = request_body.text;
                        if (msg_send_to != null) {
                            public_func.send_sms(context, msg_send_to, msg_send_content, -1);
                        }
                    }
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.code() != 200) {
                    assert response.body() != null;
                    String error_message = "Send missed call error:" + response.body().string();
                    public_func.write_log(context, error_message);
                }
                if (response.code() == 200) {
                    assert response.body() != null;
                    String result = response.body().string();
                    JsonObject result_obj = new JsonParser().parse(result).getAsJsonObject().get("result").getAsJsonObject();
                    String message_id = result_obj.get("message_id").getAsString();
                    public_func.add_message_list(context, message_id, incoming_number, slot);
                }
            }
        });
    }
}
