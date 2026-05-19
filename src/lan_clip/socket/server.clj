(ns lan-clip.socket.server
  (:require
   [clojure.java.io :as jio]
   [lan-clip.socket.protocol-codec :as codec]
   [lan-clip.util :as util])
  (:import
   (io.netty.bootstrap ServerBootstrap)
   (io.netty.channel ChannelFuture ChannelHandler ChannelInboundHandlerAdapter ChannelInitializer ChannelOption)
   (io.netty.channel.nio NioEventLoopGroup)
   (io.netty.channel.socket SocketChannel)
   (io.netty.channel.socket.nio NioServerSocketChannel)
   (io.netty.util ReferenceCountUtil)
   (java.awt Image Toolkit)
   (java.awt.datatransfer StringSelection)
   (java.util List)
   (org.apache.commons.io FileUtils)))

(defprotocol RunnableServer
  (run [this]))

;; 处理接收到的不同类型的消息（按 protocol Message 的 :content-type 分发）
(defmulti handle-msg :content-type)

(defmethod handle-msg :text [msg]
  "处理文本消息，将 payload 解码为字符串并设置到剪贴板"
  (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))
        text (String. ^bytes (:payload msg) "UTF-8")]
    (.setContents clip (StringSelection. text) nil)))

(defmethod handle-msg :image [msg]
  "处理图片消息，将 payload PNG 字节解码为 BufferedImage 并设置到剪贴板"
  (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))
        img (util/bytes->image (:payload msg))]
    (.setContents clip (util/->ImageTransferable img) nil)))

(defmethod handle-msg :file-list [_msg]
  "处理文件列表消息（待 protocol file 编码完成后启用）"
  (println "File sync not yet implemented with new protocol"))

(defmethod handle-msg :default [msg]
  (println "Unknown content-type:" (:content-type msg)))

(defn- ->handler []
  "创建一个 ChannelInboundHandlerAdapter 实例，用于处理接收到的 Message"
  (proxy [ChannelInboundHandlerAdapter]
         []
    (channelRead [ctx msg]
      (try
        (tap> msg)
        (handle-msg msg)
        (finally
          (ReferenceCountUtil/release msg)
          (.close ctx))))
    (exceptionCaught [ctx cause]
      (.printStackTrace cause)
      (.close ctx))))

;; Server 类，代表一个 netty 的服务器端实例
(defrecord Server [port secret-key]
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
                                                                            [(codec/->protocol-decoder secret-key)
                                                                             (->handler)]))))))
                  (.option ChannelOption/SO_BACKLOG (int 1024))
                  (.bind port)
                  (.sync))]
          (-> f (.channel) (.closeFuture) (.sync)))
        (finally (.shutdownGracefully boss-group)
                 (.shutdownGracefully worker-group))))))
