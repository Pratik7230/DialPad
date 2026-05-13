package com.pratikpatil.dialpad;

/**
 * Represents a single call log entry from the device's call history.
 */
public class CallLogEntry {

    private String number;
    private String name;
    private int callType; // CallLog.Calls.INCOMING_TYPE, OUTGOING_TYPE, MISSED_TYPE
    private long date;
    private long duration; // in seconds

    public CallLogEntry(String number, String name, int callType, long date, long duration) {
        this.number = number;
        this.name = name;
        this.callType = callType;
        this.date = date;
        this.duration = duration;
    }

    public String getNumber() {
        return number;
    }

    public String getName() {
        return name;
    }

    public int getCallType() {
        return callType;
    }

    public long getDate() {
        return date;
    }

    public long getDuration() {
        return duration;
    }

    /**
     * Returns the display name: contact name if available, otherwise the phone number.
     */
    public String getDisplayName() {
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return number != null ? number : "Unknown";
    }
}
