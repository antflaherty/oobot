package oobot.robot;

/** Callbacks delivered by {@link WsConnection} on its read thread. */
interface WsListener {
    void onOpen();

    void onMessage(String line);

    void onClose();

    void onError(Throwable t);
}
