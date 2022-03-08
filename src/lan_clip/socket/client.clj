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
           (java.awt Image)
           (java.util List)))

(defprotocol RunnableClient
  (run [this]))

(defn file-content [files]
  (for [f files]
    [(.getName f) (.readAllBytes (jio/input-stream f))]))

(defn ->msg [content]
  (condp instance? content
    Image (Content. (type content) (util/image->bytes (util/buffered-image content)))
    String (Content. (type content) content)
    List (Content. (type content) (file-content content))))

(comment
  (str (->msg "abc"))
  (str (->msg (java.util.ArrayList.)))
  (def files [(jio/file "D:/a.png")])
  (file-content files)
  ,)

(defn content-handler [content]
  (proxy
      [ChannelInboundHandlerAdapter]
      []
      (channelActive [ctx]
        (let [msg (->msg content)]
          (println (.-type msg)
                   (type (.-content msg))
                   (count (.-content msg)))
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


