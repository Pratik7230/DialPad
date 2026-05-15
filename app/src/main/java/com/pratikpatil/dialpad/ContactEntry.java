package com.pratikpatil.dialpad;

import android.net.Uri;

/**
 * Represents a single contact entry from the device's contacts.
 */
public class ContactEntry {

    private final long id;
    private final String name;
    private final String phoneNumber;
    private final String phoneLabel;
    private final Uri photoUri;
    private boolean starred;

    public ContactEntry(long id, String name, String phoneNumber, String phoneLabel,
                        Uri photoUri, boolean starred) {
        this.id = id;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.phoneLabel = phoneLabel;
        this.photoUri = photoUri;
        this.starred = starred;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getPhoneLabel() {
        return phoneLabel;
    }

    public Uri getPhotoUri() {
        return photoUri;
    }

    public boolean isStarred() {
        return starred;
    }

    public void setStarred(boolean starred) {
        this.starred = starred;
    }

    /**
     * Returns the display name: contact name if available, otherwise the phone number.
     */
    public String getDisplayName() {
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return phoneNumber != null ? phoneNumber : "Unknown";
    }

    /**
     * Returns the first letter of the display name (uppercase) for section grouping.
     */
    public String getSectionLetter() {
        String display = getDisplayName();
        if (display.isEmpty()) {
            return "#";
        }
        char first = Character.toUpperCase(display.charAt(0));
        if (Character.isLetter(first)) {
            return String.valueOf(first);
        }
        return "#";
    }
}
