package com.venturi.websocket.publisher;


import com.pretty_tools.dde.DDEException;
import com.pretty_tools.dde.DDEMLException;
import com.pretty_tools.dde.client.DDEClientConversation;
import com.pretty_tools.dde.client.DDEClientEventListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.springframework.web.socket.sockjs.frame.Jackson2SockJsMessageCodec;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

public class PublisherMarketData {

    private static final Logger LOGGER = LogManager.getLogger(PublisherMarketData.class);


    private final static WebSocketHttpHeaders headers = new WebSocketHttpHeaders();

    public ListenableFuture<StompSession> connect() {

        Transport webSocketTransport = new WebSocketTransport(new StandardWebSocketClient());
        List<Transport> transports = Collections.singletonList(webSocketTransport);

        SockJsClient sockJsClient = new SockJsClient(transports);
        sockJsClient.setMessageCodec(new Jackson2SockJsMessageCodec());

        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);

        String url = "ws://{host}:{port}/candle";
        return stompClient.connect(url, headers, new MyHandler(), "localhost", 8082);
    }

    public void subscribeMarketData(StompSession stompSession) throws ExecutionException, InterruptedException {
        stompSession.subscribe("/topic/marketdata", new StompFrameHandler() {

            public Type getPayloadType(StompHeaders stompHeaders) {
                return byte[].class;
            }

            public void handleFrame(StompHeaders stompHeaders, Object o) {
                //LOGGER.info("Received msg " + new String((byte[]) o));
            }
        });
    }

    public void sendCandle(StompSession stompSession, String value) {
        stompSession.send("/app/candle", value.getBytes());
    }

    private class MyHandler extends StompSessionHandlerAdapter {
        public void afterConnected(StompSession stompSession, StompHeaders stompHeaders) {
            LOGGER.info("Now connected");
        }
    }

    public static void main(String[] args) throws Exception {

        try {

            System.setProperty("java.library.path", ".\\");

            PublisherMarketData publisher = new PublisherMarketData();

            ListenableFuture<StompSession> f = publisher.connect();
            StompSession stompSession = f.get();

            LOGGER.info("Subscribing to MarketData topic using session ", stompSession);
            publisher.subscribeMarketData(stompSession);

            // event to wait disconnection
            final CountDownLatch eventDisconnect = new CountDownLatch(1);

            // DDE client
            final DDEClientConversation conversation = new DDEClientConversation();
            // We can use UNICODE format if server prefers it
            //conversation.setTextFormat(ClipboardFormat.CF_UNICODETEXT);

            conversation.setEventListener(new DDEClientEventListener(){

                public void onDisconnect() {
                    LOGGER.info("onDisconnect()");
                    eventDisconnect.countDown();
                }

                public void onItemChanged(String topic, String item, String data){

                    LOGGER.info(data);

                    publisher.sendCandle(stompSession, data);
                    try {
                        if ("stop".equalsIgnoreCase(data))
                            conversation.stopAdvice(item);
                    }
                    catch (DDEException e) {
                        LOGGER.error("Exception: ", e);
                    }
                }
            });

            LOGGER.info("Connecting...");
            conversation.connect("MT4DDE", "Cotacao");
            conversation.startAdvice("Barra");

            LOGGER.info("Waiting event...");
            eventDisconnect.await();
            LOGGER.info("Disconnecting...");
            conversation.disconnect();
            LOGGER.info("Exit from thread");
        }
        catch (DDEMLException e) {
            LOGGER.error("DDEMLException: 0x"+ Integer.toHexString(e.getErrorCode())+ " "+ e.getMessage());
        }
        catch (DDEException e) {
            LOGGER.error("DDEClientException: ", e.getMessage());
        }
        catch (Exception e) {
            LOGGER.error("Exception: " ,e);
        }
    }

}
