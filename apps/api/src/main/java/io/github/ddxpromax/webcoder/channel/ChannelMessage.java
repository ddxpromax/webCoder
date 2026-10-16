package io.github.ddxpromax.webcoder.channel;

/** Normalized inbound message shared by all Majo channel adapters. */
public record ChannelMessage(
    String channelId,
    String senderId,
    String senderName,
    String groupId,
    String text,
    String replyTo
) {
    public ChannelMessage {
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("channelId is required");
        }
        if (senderId == null || senderId.isBlank()) {
            throw new IllegalArgumentException("senderId is required");
        }
        replyTo = replyTo == null || replyTo.isBlank()
            ? (groupId == null || groupId.isBlank() ? senderId : groupId)
            : replyTo;
        senderName = senderName == null || senderName.isBlank()
            ? senderId : senderName;
        text = text == null ? "" : text;
    }

    /** Stable identity used to isolate channel conversation sessions. */
    public String identity() {
        return groupId == null || groupId.isBlank()
            ? senderId : groupId + ":" + senderId;
    }
}
