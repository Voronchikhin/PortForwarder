import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;

public class PortForwarder implements Runnable {
    private final String hostname;
    private final int hostport;
    private final int selfport;
    private boolean isStopped = false;
    public PortForwarder(String hostname, int hostport, int selfport) throws IOException {
        this.hostname = hostname;
        this.hostport = hostport;
        this.selfport = selfport;
        this.selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(selfport));
        serverChannel.configureBlocking(false);
        serverChannel.register(this.selector, SelectionKey.OP_ACCEPT);

    }

    private void write(SelectionKey key){
        Attachment attachment = (Attachment) key.attachment();
        Attachment otherAttachment = attachment.getOtherAttachment();
        otherAttachment.getBuf().flip();
        try {
            SocketChannel channel = (SocketChannel)attachment.getChannel();
            int byteWrite = channel.write(otherAttachment.getBuf());
            if (byteWrite > 0 ){
                otherAttachment.getBuf().compact();
                otherAttachment.addOption(SelectionKey.OP_READ);
            }
            if(otherAttachment.getBuf().position() == 0){
                attachment.deleteOption(SelectionKey.OP_WRITE);
                if (otherAttachment.isFinishRead()) {
                    channel.shutdownOutput();
                    attachment.setOutputShutdown(true);
                    if (otherAttachment.isOutputShutdown()) {
                        attachment.close();
                        otherAttachment.close();
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private void read(SelectionKey key){
        Attachment attachment = (Attachment) key.attachment();
        try {
            int byteRead = ((SocketChannel)attachment.getChannel()).read(attachment.getBuf());
            SocketChannel otherChannel = (SocketChannel) attachment.getOtherAttachment().getChannel();
            if ( byteRead > 0 && otherChannel.isConnected()){
                attachment.getOtherAttachment().addOption(SelectionKey.OP_WRITE);
            }
            if(byteRead == -1){
                attachment.deleteOption(SelectionKey.OP_READ);
                attachment.setFinishRead(true);
                if(attachment.getBuf().position() == 0){
                    otherChannel.shutdownOutput();
                    attachment.getOtherAttachment().setOutputShutdown(true);
                    if(attachment.isOutputShutdown() || attachment.getOtherAttachment().getBuf().position() == 0){
                        attachment.close();
                        attachment.getOtherAttachment().close();
                    }
                }
            }

            if (!attachment.getBuf().hasRemaining()) {
                attachment.deleteOption(SelectionKey.OP_READ);
            }
        } catch (IOException e) {
            e.printStackTrace();
            attachment.close();
        }

    }

    private void connect(SelectionKey key) {
        SocketChannel channel = ((SocketChannel) key.channel());
        Attachment attachment = ((Attachment) key.attachment());
        try {
            channel.finishConnect();
            attachment.deleteOption(SelectionKey.OP_CONNECT);
            attachment.addOption(SelectionKey.OP_READ);
            attachment.getOtherAttachment().addOption(SelectionKey.OP_READ);
        } catch (IOException e) {
            e.printStackTrace();
            attachment.close();
        }
    }

    private void accept(SelectionKey key) {
        SocketChannel clientChannel = null;
        SocketChannel hostChannel = null;
        try {
            clientChannel = ((ServerSocketChannel)key.channel()).accept();
            clientChannel.configureBlocking(false);
            hostChannel = SocketChannel.open();
            hostChannel.configureBlocking(false);
            hostChannel.connect(new InetSocketAddress(hostname,hostport));

            Buffer buffer = new Buffer(selector);
            buffer.setClient(clientChannel);
            buffer.setHost(hostChannel);
            Attachment client = buffer.getClientAttachment();
            Attachment newHost = buffer.getHostAttachment();

            hostChannel.register(selector, SelectionKey.OP_CONNECT, newHost);
            clientChannel.register(selector,0, client);
        } catch (IOException e) {
            e.printStackTrace();
            try {
                assert clientChannel != null;
                clientChannel.close();
                assert hostChannel != null;
                hostChannel.close();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
    }

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private int bufSize = 4096;
    @Override
    public void run() {
        System.out.println("start running");
        while (!isStopped) {
            try {
                selector.select();
            } catch (IOException e) {
                e.printStackTrace();
            }
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> iter = selectedKeys.iterator();
            while (iter.hasNext()) {
                SelectionKey key = iter.next();
                if (key.isValid() && key.isAcceptable()) {
                    accept(key);
                }
                if (key.isValid() && key.isConnectable()) {
                    connect(key);
                }
                if (key.isValid() && key.isReadable()) {
                    read(key);
                }
                if (key.isValid() && key.isWritable()) {
                    write(key);
                }

                iter.remove();
            }

        }
    }

    public static void main(String[] args) throws IOException {
        System.out.println("start app");
        new PortForwarder("fit.ippolitov.me", 80, 8000).run();
    }
}
