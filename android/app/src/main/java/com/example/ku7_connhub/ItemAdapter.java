package com.example.ku7_connhub;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class ItemAdapter extends RecyclerView.Adapter<ItemAdapter.ItemViewHolder> {
    public List<String> items;
    private final OnItemActionListener listener;

    // listener interface
    public interface OnItemActionListener {
        void onItemClick(String item); // click item
        void onCopyClick(String item); // click copy
    }

    public ItemAdapter(OnItemActionListener listener) {
        this.items = new ArrayList<>();
        this.listener = listener;
    }

    @NonNull
    @Override
    public ItemViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.draw_listitem, parent, false);
        return new ItemViewHolder(view); // return view
    }

    @Override
    public void onBindViewHolder(@NonNull ItemViewHolder holder, int position) {
        String item = items.get(position);
        holder.textViewItem.setText(item);

        // item click
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(item);
            }
        });

        // copy click
        holder.imageButtonCopy.setOnClickListener(v -> {
            if (listener != null) {
                listener.onCopyClick(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public void updateItems() {
        notifyDataSetChanged();
    }

    // view holder
    public static class ItemViewHolder extends RecyclerView.ViewHolder {
        TextView textViewItem;
        ImageButton imageButtonCopy;

        public ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            textViewItem = itemView.findViewById(R.id.textViewItem);
            imageButtonCopy = itemView.findViewById(R.id.imageButtonCopy);
        }
    }
}