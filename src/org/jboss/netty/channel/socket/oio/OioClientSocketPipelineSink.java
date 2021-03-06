package org.jboss.netty.channel.socket.oio;

import org.jboss.netty.channel.ChannelEvent;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelState;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.util.ThreadNameDeterminer;
import org.jboss.netty.util.ThreadRenamingRunnable;
import org.jboss.netty.util.internal.DeadLockProofWorker;

import java.io.PushbackInputStream;
import java.net.ConnectException;
import java.net.SocketAddress;
import java.util.concurrent.Executor;

import static org.jboss.netty.channel.Channels.*;

/**
 * 这是OIO模型下，netty和Java socket直接的连接点，传输层的内部实现。
 * @author vonzhou
 *
 */
class OioClientSocketPipelineSink extends AbstractOioChannelSink {

    private final Executor workerExecutor;
    private final ThreadNameDeterminer determiner;

    OioClientSocketPipelineSink(Executor workerExecutor, ThreadNameDeterminer determiner) {
        this.workerExecutor = workerExecutor;
        this.determiner = determiner;
    }

    public void eventSunk(
            ChannelPipeline pipeline, ChannelEvent e) throws Exception {
        OioClientSocketChannel channel = (OioClientSocketChannel) e.getChannel();
        ChannelFuture future = e.getFuture();
        if (e instanceof ChannelStateEvent) {
            ChannelStateEvent stateEvent = (ChannelStateEvent) e;
            ChannelState state = stateEvent.getState();
            Object value = stateEvent.getValue();
            //根据state和value确定要执行的动作，结合那张表；
            switch (state) {
            case OPEN:
                if (Boolean.FALSE.equals(value)) {
                    AbstractOioWorker.close(channel, future);
                }
                break;
            case BOUND:
                if (value != null) {
                    bind(channel, future, (SocketAddress) value);
                } else {
                    AbstractOioWorker.close(channel, future);
                }
                break;
            case CONNECTED:
                if (value != null) {
                    connect(channel, future, (SocketAddress) value);
                } else {
                    AbstractOioWorker.close(channel, future);
                }
                break;
            case INTEREST_OPS:
                AbstractOioWorker.setInterestOps(channel, future, ((Integer) value).intValue());
                break;
            }
        } else if (e instanceof MessageEvent) {
            OioWorker.write(
                    channel, future,
                    ((MessageEvent) e).getMessage());
        }
    }

    private static void bind(
            OioClientSocketChannel channel, ChannelFuture future,
            SocketAddress localAddress) {
        try {
        	//每个通道维护的套接字对象，
            channel.socket.bind(localAddress);
            future.setSuccess();
            //绑定成功，通知上层
            fireChannelBound(channel, channel.getLocalAddress());
        } catch (Throwable t) {
            future.setFailure(t);
            fireExceptionCaught(channel, t);
        }
    }

    private void connect(
            OioClientSocketChannel channel, ChannelFuture future,
            SocketAddress remoteAddress) {

        boolean bound = channel.isBound();
        boolean connected = false;
        boolean workerStarted = false;

        future.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);

        try {
        	//真正的绑定
            channel.socket.connect(
                    remoteAddress, channel.getConfig().getConnectTimeoutMillis());
            connected = true;

            // Obtain I/O stream.然后获得对应的输入输出流
            channel.in = new PushbackInputStream(channel.socket.getInputStream(), 1);
            channel.out = channel.socket.getOutputStream();

            // Fire events.
            future.setSuccess();
            if (!bound) {
                fireChannelBound(channel, channel.getLocalAddress());
            }
            //通知上层
            fireChannelConnected(channel, channel.getRemoteAddress());

            // Start the business.重点
            DeadLockProofWorker.start(
                    workerExecutor,
                    new ThreadRenamingRunnable(
                            new OioWorker(channel),
                            "Old I/O client worker (" + channel + ')',
                            determiner));
            workerStarted = true;
        } catch (Throwable t) {
            if (t instanceof ConnectException) {
                if (t instanceof ConnectException) {
                    Throwable newT = new ConnectException(t.getMessage() + ": " + remoteAddress);
                    newT.setStackTrace(t.getStackTrace());
                    t = newT;
                }
            }
            future.setFailure(t);
            fireExceptionCaught(channel, t);
        } finally {
            if (connected && !workerStarted) {
                AbstractOioWorker.close(channel, future);
            }
        }
    }
}
