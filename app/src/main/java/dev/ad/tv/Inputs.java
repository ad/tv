package dev.ad.tv;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.List;

final class Inputs {
    private Inputs() {}

    static List<TvInput> list(Context context) {
        TvInputManager tim = context.getSystemService(TvInputManager.class);
        if (tim == null) return List.of();
        List<TvInputInfo> all;
        try {
            all = tim.getTvInputList();
        } catch (Exception e) {
            return List.of();
        }
        List<TvInputInfo> hdmi = new ArrayList<>();
        for (TvInputInfo info : all) if (info.getType() == TvInputInfo.TYPE_HDMI) hdmi.add(info);
        List<TvInput> out = new ArrayList<>();
        for (TvInputInfo port : hdmi) {
            if (port.getParentId() != null) continue;
            TvInputInfo child = null;
            for (TvInputInfo info : hdmi) {
                if (port.getId().equals(info.getParentId()) && connected(tim, info)) {
                    child = info;
                    break;
                }
            }
            if (child == null && !connected(tim, port)) continue;
            String portName = name(context, port);
            String device = child != null ? name(context, child) : null;
            out.add(new TvInput(
                child != null ? child.getId() : port.getId(),
                device != null && !device.equals(portName) ? portName + " — " + device : portName
            ));
        }
        return out;
    }

    static void open(Context context, TvInput input) {
        Intent intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Apps.start(context, intent);
    }

    private static boolean connected(TvInputManager tim, TvInputInfo info) {
        try {
            return tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED;
        } catch (Exception e) {
            return false;
        }
    }

    private static String name(Context context, TvInputInfo info) {
        CharSequence custom = info.loadCustomLabel(context);
        if (custom != null && !custom.toString().trim().isEmpty()) return custom.toString();
        return info.loadLabel(context).toString();
    }
}
