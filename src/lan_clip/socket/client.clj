(ns lan-clip.socket.client
  (:gen-class)
  (:require [lan-clip.socket.protocol-codec :as codec])
  (:import (io.netty.channel.nio NioEventLoopGroup)
           (io.netty.bootstrap Bootstrap)
           (io.netty.channel ChannelFuture ChannelOption ChannelInitializer ChannelHandler ChannelInboundHandlerAdapter)
           (io.netty.channel.socket.nio NioSocketChannel)
           (io.netty.channel.socket SocketChannel)
           (java.awt Image)
           (java.io File)
           (java.util List)))

(defprotocol RunnableClient
  (run [this]))

(defn content-handler [content]
  (proxy
      [ChannelInboundHandlerAdapter]
      []
      (channelActive [ctx]
        (when (or (string? content)
                  (instance? Image content)
                  (and (instance? List content)
                       (every? #(instance? File %) content)))
          (.writeAndFlush ctx content)))

      (exceptionCaught [ctx cause]
        (.printStackTrace cause)
        (.close ctx))))

(defn get-handlers [content secret-key node-id]
  (into-array
    ChannelHandler [(codec/->protocol-encoder node-id node-id secret-key)
                    (codec/->protocol-decoder secret-key)
                    (content-handler content)]))

(defn ->chan-init [content secret-key node-id]
  (proxy [ChannelInitializer]
         []
    (initChannel [^SocketChannel ch]
      (-> ch (.pipeline) (.addLast (get-handlers content secret-key node-id))))))

(defrecord Client [host port content secret-key node-id]
  RunnableClient
  (run [this]
    (let [worker-group (NioEventLoopGroup.)
          b (Bootstrap.)]
      (try
        (let [^ChannelFuture f
              (-> b
                  (.group worker-group)
                  (.channel NioSocketChannel)
                  (.option ChannelOption/TCP_NODELAY true)
                  (.handler (->chan-init content secret-key node-id))
                  (.connect host port)
                  (.sync))]
          (-> f (.channel) (.closeFuture) (.sync)))
        (finally
          (.shutdownGracefully worker-group))))))
