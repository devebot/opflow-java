package com.devebot.opflow;

import com.devebot.opflow.exception.OpflowConstructorException;
import com.devebot.opflow.exception.OpflowOperationException;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 *
 * @author drupalex
 */
public class OpflowExecutor {
    private final OpflowEngine engine;
    
    public OpflowExecutor(OpflowEngine engine) {
        this.engine = engine;
    }
    
    public void assertQueue(final String queueName) throws OpflowConstructorException {
        try {
            declareQueue(queueName);
        } catch (IOException ioe) {
            throw new OpflowConstructorException(ioe);
        } catch (TimeoutException te) {
            throw new OpflowConstructorException(te);
        }
    }
    
    public int countQueue(final String queueName) {
        try {
            return declareQueue(queueName).getMessageCount();
        } catch (Exception exception) {
            throw new OpflowOperationException(exception);
        }
    }
    
    public AMQP.Queue.DeclareOk defineQueue(final String queueName) {
        try {
            return declareQueue(queueName);
        } catch (Exception exception) {
            throw new OpflowOperationException(exception);
        }
    }
    
    private AMQP.Queue.DeclareOk declareQueue(final String queueName) throws IOException, TimeoutException {
        if (queueName == null) return null;
        try {
            return engine.acquireChannel(new OpflowEngine.Operator() {
                @Override
                public AMQP.Queue.DeclareOk handleEvent(Channel _channel) throws IOException {
                    return _channel.queueDeclarePassive(queueName);
                }
            });
        } catch (IOException e1) {
            return engine.acquireChannel(new OpflowEngine.Operator() {
                @Override
                public AMQP.Queue.DeclareOk handleEvent(Channel _channel) throws IOException {
                    return _channel.queueDeclare(queueName, true, false, false, null);
                }
            });
        }
    }
    
    public AMQP.Queue.PurgeOk purgeQueue(final String queueName) {
        if (queueName == null) return null;
        try {
            return engine.acquireChannel(new OpflowEngine.Operator() {
                @Override
                public AMQP.Queue.PurgeOk handleEvent(Channel _channel) throws IOException {
                    return _channel.queuePurge(queueName);
                }
            });
        } catch (Exception exception) {
            throw new OpflowOperationException(exception);
        }
    }
    
    public AMQP.Queue.DeleteOk deleteQueue(final String queueName) {
        try {
            return engine.acquireChannel(new OpflowEngine.Operator() {
                @Override
                public Object handleEvent(Channel channel) throws IOException {
                    return channel.queueDelete(queueName, true, false);
                }
            });
        } catch (IOException exception) {
            throw new OpflowOperationException(exception);
        } catch (TimeoutException exception) {
            throw new OpflowOperationException(exception);
        }
    }
    
    public AMQP.Exchange.DeclareOk defineExchange(final String exchangeName, final String exchangeType) {
        try {
            return declareExchange(exchangeName, exchangeType);
        } catch (IOException ioe) {
            throw new OpflowOperationException(ioe);
        } catch (TimeoutException te) {
            throw new OpflowOperationException(te);
        }
    }
    
    private AMQP.Exchange.DeclareOk declareExchange(final String exchangeName, final String exchangeType)
            throws IOException, TimeoutException {
        if (exchangeName == null) return null;
        try {
            return engine.acquireChannel(new OpflowEngine.Operator() {
                @Override
                public AMQP.Exchange.DeclareOk handleEvent(Channel _channel) throws IOException {
                    return _channel.exchangeDeclarePassive(exchangeName);
                }
            });
        } catch (IOException e1) {
            final String _type = (exchangeType != null) ? exchangeType : "direct";
            return engine.acquireChannel(new OpflowEngine.Operator() {
                @Override
                public AMQP.Exchange.DeclareOk handleEvent(Channel _channel) throws IOException {
                    return _channel.exchangeDeclare(exchangeName, _type, true, false, null);
                }
            });
        }
    }
    
    public AMQP.Exchange.DeleteOk deleteExchange(final String exchangeName) {
        try {
            return engine.acquireChannel(new OpflowEngine.Operator() {
                @Override
                public Object handleEvent(Channel channel) throws IOException {
                    return channel.exchangeDelete(exchangeName, true);
                }
            });
        } catch (IOException exception) {
            throw new OpflowOperationException(exception);
        } catch (TimeoutException exception) {
            throw new OpflowOperationException(exception);
        }
    }
}