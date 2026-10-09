package kr.asmbl.figuracontroller.protocol;

/** Protocol violation — both reading (broken packet) and writing (over the limit · no subject) */
public final class ProtocolException extends RuntimeException {
    public ProtocolException(String message) {
        super(message);
    }
}
