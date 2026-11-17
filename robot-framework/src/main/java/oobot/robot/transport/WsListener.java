package oobot.robot.transport;

/** Callbacks delivered by {@link WsConnection} on its read thread. */
public interface WsListener {
    void onOpen();

    void onMessage(String line);

    void onClose();

    void onError(Throwable t);
}
