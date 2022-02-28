(ns lan-clip.socket.server
  (:require [lan-clip.util :as util])
  (:import (io.netty.channel.nio NioEventLoopGroup)
           (io.netty.bootstrap ServerBootstrap)
           (io.netty.channel.socket.nio NioServerSocketChannel)
           (io.netty.channel ChannelInitializer ChannelOption ChannelFuture ChannelInboundHandlerAdapter ChannelHandler)
           (io.netty.channel.socket SocketChannel)
           (io.netty.util ReferenceCountUtil)
           (java.awt Image Toolkit)
           (io.netty.buffer ByteBuf)
           (io.netty.handler.codec.serialization ObjectDecoder ClassResolvers)
           (java.awt.datatransfer StringSelection)))

(defprotocol RunnableServer
  (run [this]))

(defmulti handle-msg #(.-type %))

(defmethod handle-msg String [msg]
  (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))]
    (.setContents clip (StringSelection. (.-content msg)) nil)))

(defmethod handle-msg Image [msg]
  (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))]
    (.setContents clip (util/->ImageTransferable (util/bytes->image (.-content msg))) nil)))

(defmethod handle-msg ByteBuf [msg]
  (while (.isReadable msg)
    (print (char (.readByte msg)))
    (flush)))

(defn- ->handler []
  (proxy [ChannelInboundHandlerAdapter]
         []
    (channelRead [ctx msg]
      (try
        (handle-msg msg)
        (finally
          (ReferenceCountUtil/release msg)
          (.close ctx))))
    (exceptionCaught [ctx cause]
      (.printStackTrace cause)
      (.close ctx)))
  )

(defrecord Server [port]
  RunnableServer
  (run [this]
    (let [boss-group (NioEventLoopGroup.)
          worker-group (NioEventLoopGroup.)
          b (ServerBootstrap.)]
      (try
        (let [^ChannelFuture f
              (-> b
                  (.group boss-group worker-group)
                  (.channel NioServerSocketChannel)
                  (.childHandler (proxy [ChannelInitializer]
                                        []
                                   (initChannel [^SocketChannel ch]
                                     (.. ch (pipeline) (addLast (into-array ChannelHandler
                                                                            [(ObjectDecoder. Integer/MAX_VALUE (ClassResolvers/weakCachingConcurrentResolver nil))
                                                                             (->handler)]))))))
                  (.option ChannelOption/SO_BACKLOG (int 1024))
                  (.bind port)
                  (.sync))]
          (-> f (.channel) (.closeFuture) (.sync)))
        (finally (.shutdownGracefully boss-group)
                 (.shutdownGracefully worker-group))))))

(defn -main [& args]
  (.run (->Server (int 9002))))