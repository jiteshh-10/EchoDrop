package com.dev.echodrop.viewmodels;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.dev.echodrop.models.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MessageViewModel extends ViewModel {
    private final MutableLiveData<List<Message>> messages = new MutableLiveData<>();

    public MessageViewModel() {
        seedMessages();
    }

    public LiveData<List<Message>> getMessages() {
        return messages;
    }

    public void addMessage(Message message) {
        List<Message> current = messages.getValue();
        List<Message> updated = new ArrayList<>();
        if (current != null) {
            updated.addAll(current);
        }
        updated.add(0, message);
        messages.setValue(updated);
    }

    private void seedMessages() {
        long now = System.currentTimeMillis();
        long createdUrgent = now - (20 * 60 * 1000L);
        long createdNormal = now - (35 * 60 * 1000L);
        long createdEvent = now - (10 * 60 * 1000L);

        List<Message> seed = new ArrayList<>();
        seed.add(new Message(
                "Road closed near the west gate. Use the north entrance.",
                Message.Scope.LOCAL,
                Message.Priority.ALERT,
                createdUrgent,
                createdUrgent + (60 * 60 * 1000L),
                false
        ));
        seed.add(new Message(
                "Study group meets at 6pm in the library lounge.",
                Message.Scope.ZONE,
                Message.Priority.NORMAL,
                createdNormal,
                createdNormal + (4 * 60 * 60 * 1000L),
                false
        ));
        seed.add(new Message(
                "Campus film night starts at 8pm. Bring a jacket.",
                Message.Scope.EVENT,
                Message.Priority.NORMAL,
                createdEvent,
                createdEvent + (12 * 60 * 60 * 1000L),
                false
        ));
        messages.setValue(Collections.unmodifiableList(seed));
    }
}
