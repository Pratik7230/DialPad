package com.pratikpatil.dialpad;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.InCallService;

/**
 * InCallService implementation.
 * Android routes all active calls here when this app is the default phone app.
 * We launch CallScreenActivity so the user sees the custom UI.
 */
public class MyInCallService extends InCallService {

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);

        // Store for use in CallScreenActivity
        CallManager.getInstance().setCurrentCall(call);

        // Extract number and name from call details
        String number = "";
        String name   = "";
        if (call.getDetails() != null && call.getDetails().getHandle() != null) {
            number = call.getDetails().getHandle().getSchemeSpecificPart();
        }
        if (call.getDetails() != null
                && call.getDetails().getCallerDisplayName() != null
                && !call.getDetails().getCallerDisplayName().isEmpty()) {
            name = call.getDetails().getCallerDisplayName();
        }

        // Launch our custom call screen (needs NEW_TASK because this is a Service)
        Intent intent = CallScreenActivity.buildIntent(this, number, name);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        CallManager.getInstance().setCurrentCall(null);
    }
}
