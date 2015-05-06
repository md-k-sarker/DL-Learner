package org.dllearner.algorithms.distributed;



public interface MessagingAgent {

    public void initMessaging() throws Exception;

    public void finalizeMessaging() throws Exception;

    public void blockingSend(MessageContainer msgContainer) throws Exception;

    public void nonBlockingSend(MessageContainer msgContainer) throws Exception;

    public MessageContainer blockingReceive() throws Exception;

    public MessageContainer nonBlockingReceive() throws Exception;

    public void terminateAgents() throws Exception;

    public boolean checkTerminateMsg() throws Exception;

    public void setAgentID(int id);

    public int getAgentID();

    public boolean isMaster();
}
