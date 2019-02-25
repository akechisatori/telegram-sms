package com.qwe7002.telegram_sms;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Telephony;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static android.content.Context.MODE_PRIVATE;
import static android.content.Context.TELEPHONY_SERVICE;
import static android.support.v4.content.PermissionChecker.checkSelfPermission;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import android.telephony.TelephonyManager;


public class sms_receiver extends BroadcastReceiver {
    public void onReceive(final Context context, Intent intent) {
        final boolean is_default = Telephony.Sms.getDefaultSmsPackage(context).equals(context.getPackageName());
        final SharedPreferences sharedPreferences = context.getSharedPreferences("data", MODE_PRIVATE);
        if (!sharedPreferences.getBoolean("initialized", false)) {
            public_func.write_log(context, "Receive SMS:Uninitialized");
            return;
        }
        String bot_token = sharedPreferences.getString("bot_token", "");
        String chat_id = sharedPreferences.getString("chat_id", "");
        String request_uri = public_func.get_url(bot_token, "sendMessage");
        if ("android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
            if (is_default) {
                return;
            }
        }
        TelephonyManager telephony = (TelephonyManager) context
                .getSystemService(Context.TELEPHONY_SERVICE);
        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            HashMap<String, Object> map = new HashMap<>();
            ArrayList<Object> slots = new ArrayList<>();
            String dual_sim = "";

            map.put("method","sms");
            SubscriptionManager manager = SubscriptionManager.from(context);
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                int slot_count = manager.getActiveSubscriptionInfoCount();

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
                if (slot_count >= 2) {
                    int slot = bundle.getInt("slot", -1);

                    if (slot != -1) {
                        String display_name = public_func.get_sim_name_title(context, slot);
                        String display = "";
                        if (display_name != null) {
                            display = "(" + display_name + ")";
                        }
                        map.put("current_slot", slot);
                        dual_sim = "SIM" + (slot + 1) + display + " ";
                    }
                } else {
                    String display_name = public_func.get_sim_display_name(context, 0);
                    map.put("current_slot",0);
                }
                map.put("slots",slots);
            }
            final int sub = bundle.getInt("subscription", -1);
            Object[] pdus = (Object[]) bundle.get("pdus");
            assert pdus != null;
            final SmsMessage[] messages = new SmsMessage[pdus.length];
            for (int i = 0; i < pdus.length; i++) {
                messages[i] = SmsMessage.createFromPdu((byte[]) pdus[i]);
                if (is_default) {
                    ContentValues values = new ContentValues();
                    values.put(Telephony.Sms.ADDRESS, messages[i].getOriginatingAddress());
                    values.put(Telephony.Sms.BODY, messages[i].getMessageBody());
                    values.put(Telephony.Sms.SUBSCRIPTION_ID, String.valueOf(sub));
                    values.put(Telephony.Sms.READ, "1");
                    context.getContentResolver().insert(Telephony.Sms.CONTENT_URI, values);
                }

            }
            if (messages.length > 0) {
                StringBuilder msgBody = new StringBuilder();
                for (SmsMessage item : messages) {
                    msgBody.append(item.getMessageBody());

                }
                String msg_address = messages[0].getOriginatingAddress();
                map.put("timestamp",messages[0].getTimestampMillis()/1000);

                map.put("mobile",msg_address);

                final request_json request_body = new request_json();
                request_body.chat_id = chat_id;
                String display_address = msg_address;
                if (display_address != null) {
                    String display_name = public_func.get_contact_name(context, display_address);
                    if (display_name != null) {
                        map.put("contact",display_name);
                        display_address = display_name + "(" + display_address + ")";
                    } else {
                        map.put("contact",null);
                    }
                }
                request_body.text = "[" + dual_sim + context.getString(R.string.receive_sms_head) + "]" + "\n" + context.getString(R.string.from) + display_address + "\n" + context.getString(R.string.content) + msgBody;
                assert msg_address != null;

                map.put("content",msgBody);


                if (checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                    if (msg_address.equals(sharedPreferences.getString("trusted_phone_number", null))) {
                        String[] msg_send_list = msgBody.toString().split("\n");
                        String msg_send_to = public_func.get_send_phone_number(msg_send_list[0]);
                        if (msg_send_to.equals("restart-service")) {
                            public_func.start_service(context.getApplicationContext(), sharedPreferences);
                            request_body.text = context.getString(R.string.system_message_head) + "\n" + context.getString(R.string.restart_service);
                        }
                        if (public_func.is_numeric(msg_send_to) && msg_send_list.length != 1) {
                            StringBuilder msg_send_content = new StringBuilder();
                            for (int i = 1; i < msg_send_list.length; i++) {
                                if (msg_send_list.length != 2 && i != 1) {
                                    msg_send_content.append("\n");
                                }
                                msg_send_content.append(msg_send_list[i]);
                            }
                            public_func.send_sms(context, msg_send_to, msg_send_content.toString(), sub);
                            return;
                        }
                    }
                }

                Gson gson = new Gson();
                String raw_json = gson.toJson(map);
                Log.d("sms",raw_json);

                String request_body_raw = gson.toJson(request_body);
                RequestBody body = RequestBody.create(public_func.JSON, request_body_raw);
                OkHttpClient okhttp_client = public_func.get_okhttp_obj();
                okhttp_client.retryOnConnectionFailure();
                okhttp_client.connectTimeoutMillis();
                String callback_url = public_func.get_callback_addr(context);

                Log.d("callback url",callback_url);
                RequestBody raw_json_body = RequestBody.create(public_func.JSON, raw_json);

                Request request_callback = new Request.Builder().url(callback_url).method("POST", raw_json_body).build();

                Request request = new Request.Builder().url(request_uri).method("POST", body).build();
                Call call = okhttp_client.newCall(request);
                Call call_callback = okhttp_client.newCall(request_callback);
                call_callback.enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {

                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        if (response.code() != 200) {
                            assert response.body() != null;
                            String error_message = "SMS forwarding to Callback failed:" + response.body().string();
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
                        String error_message = "SMS forwarding failed:" + e.getMessage();
                        public_func.write_log(context, error_message);
                        public_func.write_log(context, "message body:" + request_body.text);
                        if (checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                            if (sharedPreferences.getBoolean("fallback_sms", false)) {
                                String msg_send_to = sharedPreferences.getString("trusted_phone_number", null);
                                String msg_send_content = request_body.text;
                                if (msg_send_to != null) {
                                    public_func.send_sms(context, msg_send_to, msg_send_content, sub);
                                }
                            }
                        }
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        if (response.code() != 200) {
                            assert response.body() != null;
                            String error_message = "SMS forwarding failed:" + response.body().string();
                            public_func.write_log(context, error_message);
                            public_func.write_log(context, "message body:" + request_body.text);
                        }
                        if (response.code() == 200) {
                            assert response.body() != null;
                            String result = response.body().string();
                            JsonObject result_obj = new JsonParser().parse(result).getAsJsonObject().get("result").getAsJsonObject();
                            String message_id = result_obj.get("message_id").getAsString();
                            public_func.add_message_list(context, message_id, msg_address, bundle.getInt("slot", -1));
                        }
                    }
                });
            }
        }
    }
}

