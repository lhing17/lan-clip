(ns lan-clip.socket.client
  (:gen-class)
  (:require [clojure.java.io :as jio]
            [lan-clip.util :as util])
  (:import (io.netty.channel.nio NioEventLoopGroup)
           (io.netty.bootstrap Bootstrap)
           (io.netty.channel ChannelFuture ChannelOption ChannelInitializer ChannelHandler ChannelInboundHandlerAdapter)
           (io.netty.channel.socket.nio NioSocketChannel)
           (io.netty.handler.codec.serialization ObjectEncoder ObjectDecoder ClassResolvers)
           (io.netty.channel.socket SocketChannel)
           (lan_clip.socket.content Content)
           (javax.imageio ImageIO)
           (java.awt Image)))

(defprotocol RunnableClient
  (run [this]))

(defn ->msg [content]
  (if (instance? Image content)
    (Content. (type content) (util/image->bytes (util/buffered-image content)))
    (Content. (type content) content)))

(defn content-handler [content]
  (proxy
    [ChannelInboundHandlerAdapter]
    []
    (channelActive [ctx]
      (let [msg (->msg content)]
        (println (type content)
                 (if (instance? String content)
                   content
                   "Image"))
        (.writeAndFlush ctx msg)))

    (exceptionCaught [ctx cause]
      (.printStackTrace cause)
      (.close ctx))))

(defn get-handlers [content]
  (into-array
    ChannelHandler [(ObjectEncoder.) (ObjectDecoder. (ClassResolvers/weakCachingConcurrentResolver nil)) (content-handler content)]))

(defn ->chan-init [content]
  (proxy [ChannelInitializer]
         []
    (initChannel [^SocketChannel ch]
      (-> ch (.pipeline) (.addLast (get-handlers content))))))

(defrecord Client [host port content]
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
                  (.handler (->chan-init content))
                  (.connect host port)
                  (.sync))]
          (-> f (.channel) (.closeFuture) (.sync)))
        (finally
          (.shutdownGracefully worker-group))))))


