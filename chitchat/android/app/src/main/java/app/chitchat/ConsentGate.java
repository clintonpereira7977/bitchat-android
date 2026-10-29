package app.chitchat;

/** Role-bound permission to chat. The requester consents by sending a request. */
public final class ConsentGate {
    public enum State { START, WAITING_REQUEST, WAITING_APPROVAL, INCOMING_REQUEST, WAITING_READY, CHAT, DECLINED, CLOSED }
    public final boolean hosting;
    private State state=State.START;
    public ConsentGate(boolean hosting) { this.hosting=hosting; }
    public State state() { return state; }
    public boolean canChat() { return state==State.CHAT; }
    private void require(boolean role,State expected) {
        if(hosting!=role || state!=expected) throw new IllegalStateException("Unexpected connection request state");
    }
    public void keysReady() { if(state!=State.START) throw new IllegalStateException("Duplicate handshake"); state=hosting?State.WAITING_REQUEST:State.WAITING_APPROVAL; }
    public void receiveRequest() { require(true,State.WAITING_REQUEST); state=State.INCOMING_REQUEST; }
    public void accept() { require(true,State.INCOMING_REQUEST); state=State.WAITING_READY; }
    public void receiveAcceptance() { require(false,State.WAITING_APPROVAL); state=State.CHAT; }
    public void receiveReady() { require(true,State.WAITING_READY); state=State.CHAT; }
    public void decline() { require(true,State.INCOMING_REQUEST); state=State.DECLINED; }
    public void receiveDecline() { require(false,State.WAITING_APPROVAL); state=State.DECLINED; }
    public void receiveDeclineAck() { require(true,State.DECLINED); state=State.CLOSED; }
    public void requireChat() { if(!canChat()) throw new IllegalStateException("Approval required"); }
}
