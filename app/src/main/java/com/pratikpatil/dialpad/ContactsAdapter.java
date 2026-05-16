package com.pratikpatil.dialpad;

import android.graphics.Color;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for displaying contacts with section headers (A-Z).
 * Supports two view types: section headers and contact items.
 */
public class ContactsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CONTACT = 1;

    /**
     * Listener for click events on contact items.
     */
    public interface OnContactActionListener {
        void onContactClick(ContactEntry contact);
        void onCallClick(ContactEntry contact);
        void onMessageClick(ContactEntry contact);
    }

    /**
     * Represents an item in the adapter — either a section header or a contact entry.
     */
    private static class ListItem {
        final int type;
        final String header;        // for TYPE_HEADER
        final ContactEntry contact; // for TYPE_CONTACT

        ListItem(String header) {
            this.type = TYPE_HEADER;
            this.header = header;
            this.contact = null;
        }

        ListItem(ContactEntry contact) {
            this.type = TYPE_CONTACT;
            this.header = null;
            this.contact = contact;
        }
    }

    private List<ListItem> items = new ArrayList<>();
    private final OnContactActionListener listener;

    // Predefined avatar background colors for visual variety
    private static final int[] AVATAR_COLORS = {
            Color.parseColor("#E57373"), // Red
            Color.parseColor("#F06292"), // Pink
            Color.parseColor("#BA68C8"), // Purple
            Color.parseColor("#9575CD"), // Deep Purple
            Color.parseColor("#7986CB"), // Indigo
            Color.parseColor("#64B5F6"), // Blue
            Color.parseColor("#4FC3F7"), // Light Blue
            Color.parseColor("#4DD0E1"), // Cyan
            Color.parseColor("#4DB6AC"), // Teal
            Color.parseColor("#81C784"), // Green
            Color.parseColor("#AED581"), // Light Green
            Color.parseColor("#FFB74D"), // Orange
            Color.parseColor("#FF8A65"), // Deep Orange
    };

    public ContactsAdapter(OnContactActionListener listener) {
        this.listener = listener;
    }

    /**
     * Updates the adapter with a new list of contacts. Automatically generates section headers.
     */
    public void updateData(List<ContactEntry> contacts) {
        items.clear();

        if (contacts == null || contacts.isEmpty()) {
            notifyDataSetChanged();
            return;
        }

        String currentSection = null;
        for (ContactEntry contact : contacts) {
            String section = contact.getSectionLetter();
            if (!section.equals(currentSection)) {
                currentSection = section;
                items.add(new ListItem(section));
            }
            items.add(new ListItem(contact));
        }

        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            View view = inflater.inflate(R.layout.item_contact_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_contact, parent, false);
            return new ContactViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ListItem item = items.get(position);

        if (holder instanceof HeaderViewHolder) {
            HeaderViewHolder headerHolder = (HeaderViewHolder) holder;
            headerHolder.tvSectionLetter.setText(item.header);
        } else if (holder instanceof ContactViewHolder) {
            ContactViewHolder contactHolder = (ContactViewHolder) holder;
            ContactEntry contact = item.contact;

            if (contact == null) return;

            // Set contact name
            contactHolder.tvContactName.setText(contact.getDisplayName());

            // Set phone number and label
            String numberInfo = contact.getPhoneNumber();
            if (contact.getPhoneLabel() != null && !contact.getPhoneLabel().isEmpty()) {
                numberInfo = contact.getPhoneLabel() + " · " + numberInfo;
            }
            contactHolder.tvContactNumber.setText(numberInfo);

            // Set avatar
            if (contact.getPhotoUri() != null) {
                try {
                    contactHolder.ivAvatar.setImageURI(contact.getPhotoUri());
                    contactHolder.ivAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    contactHolder.tvAvatarLetter.setVisibility(View.GONE);
                } catch (Exception e) {
                    setLetterAvatar(contactHolder, contact);
                }
            } else {
                setLetterAvatar(contactHolder, contact);
            }

            // Set starred indicator
            if (contact.isStarred()) {
                contactHolder.ivStarred.setVisibility(View.VISIBLE);
            } else {
                contactHolder.ivStarred.setVisibility(View.GONE);
            }

            // Click listeners
            contactHolder.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onContactClick(contact);
                }
            });

            contactHolder.btnCall.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onCallClick(contact);
                }
            });

            contactHolder.btnMessage.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onMessageClick(contact);
                }
            });
        }
    }

    private void setLetterAvatar(ContactViewHolder holder, ContactEntry contact) {
        holder.ivAvatar.setImageDrawable(null);
        holder.tvAvatarLetter.setVisibility(View.VISIBLE);

        String displayName = contact.getDisplayName();
        String letter = displayName.isEmpty() ? "?" : String.valueOf(displayName.charAt(0)).toUpperCase();
        holder.tvAvatarLetter.setText(letter);

        // Assign a consistent color based on the contact name
        int colorIndex = Math.abs(displayName.hashCode()) % AVATAR_COLORS.length;
        holder.ivAvatarBg.getBackground().setTint(AVATAR_COLORS[colorIndex]);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /**
     * Returns the section positions for fast scroller support.
     */
    public int getPositionForSection(String letter) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).type == TYPE_HEADER && letter.equals(items.get(i).header)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns all available section letters.
     */
    public List<String> getSectionLetters() {
        List<String> sections = new ArrayList<>();
        for (ListItem item : items) {
            if (item.type == TYPE_HEADER) {
                sections.add(item.header);
            }
        }
        return sections;
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView tvSectionLetter;

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            tvSectionLetter = itemView.findViewById(R.id.tvSectionLetter);
        }
    }

    static class ContactViewHolder extends RecyclerView.ViewHolder {
        View ivAvatarBg;
        ImageView ivAvatar;
        TextView tvAvatarLetter;
        TextView tvContactName;
        TextView tvContactNumber;
        ImageView ivStarred;
        ImageButton btnCall;
        ImageButton btnMessage;

        ContactViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAvatarBg = itemView.findViewById(R.id.ivAvatarBg);
            ivAvatar = itemView.findViewById(R.id.ivContactAvatar);
            tvAvatarLetter = itemView.findViewById(R.id.tvAvatarLetter);
            tvContactName = itemView.findViewById(R.id.tvContactName);
            tvContactNumber = itemView.findViewById(R.id.tvContactNumber);
            ivStarred = itemView.findViewById(R.id.ivStarred);
            btnCall = itemView.findViewById(R.id.btnContactCall);
            btnMessage = itemView.findViewById(R.id.btnContactMessage);
        }
    }
}
