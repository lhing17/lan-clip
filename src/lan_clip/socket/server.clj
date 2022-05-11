(ns lan-clip.socket.server
  (:require
   [clojure.java.io :as jio]
   [lan-clip.util :as util])
  (:import
   (io.netty.bootstrap ServerBootstrap)
   (io.netty.buffer ByteBuf)
   (io.netty.channel ChannelFuture ChannelHandler ChannelInboundHandlerAdapter ChannelInitializer ChannelOption)
   (io.netty.channel.nio NioEventLoopGroup)
   (io.netty.channel.socket SocketChannel)
   (io.netty.channel.socket.nio NioServerSocketChannel)
   (io.netty.handler.codec.serialization ClassResolvers ObjectDecoder)
   (io.netty.util ReferenceCountUtil)
   (java.awt Image Toolkit)
   (java.awt.datatransfer StringSelection)
   (java.util List)
   (org.apache.commons.io FileUtils)))

(def ^:private config (util/read-edn "config.edn" ))

(comment
  config
  ,)

(defprotocol RunnableServer
  (run [this]))

;; 处理接收到的不同类型的消息
(defmulti handle-msg #(.-type %))

(defmethod handle-msg String [msg]
  "处理字符串类型的消息，直接设置到剪贴版上"
  (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))]
    (.setContents clip (StringSelection. (.-content msg)) nil)))

(defmethod handle-msg Image [msg]
  "处理图片类型的消息"
  (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))]
    (println (count (.-content msg)) (type (util/bytes->image (.-content msg))))
    (.setContents clip (util/->ImageTransferable (util/bytes->image (.-content msg))) nil)))

(defmethod handle-msg ByteBuf [msg]
  "处理字节流消息，打印并舍弃"
  (while (.isReadable msg)
    (print (char (.readByte msg)))
    (flush)))

(defmethod handle-msg List [msg]
  "处理文件列表消息，放到临时文件夹中，并设置到剪贴版上"
  (let [fs (.-content msg)
        tmp (jio/file (System/getProperty "user.dir") "tmp")
        v (transient [])
        clip (.getSystemClipboard (Toolkit/getDefaultToolkit))]
    (if-not (.exists tmp)
      (.mkdirs tmp)
      (try (FileUtils/cleanDirectory tmp)
           (catch Exception _)))
    (doseq [f fs]
      (let [tmp-file (jio/file tmp (first f))]
        (jio/copy (second f) tmp-file)
        (conj! v tmp-file)))
    (.setContents clip (util/->FileListTransferable (apply list (persistent! v))) nil)))

(defn- ->handler []
  "创建一个ChannelInboundHandlerAdapter实例，用于处理接收到的消息"
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

;; Server类，代表一个netty的服务器端实例
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
