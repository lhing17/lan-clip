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

(defmethod handle-msg :file-list [msg]
  "处理文件列表消息，将 payload zip 字节解压到临时目录并写入剪贴板"
  (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))
        temp-dir (jio/file (System/getProperty "java.io.tmpdir") (str "lan-clip-" (System/currentTimeMillis)))
        files (util/zip-bytes->files (:payload msg) temp-dir)]
    (.setContents clip (util/->FileListTransferable files) nil)))

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

(defn start-server
  "启动 Netty server，返回控制对象：
    :future — 后台 future，server 在此运行
    :stop!  — 无参函数，调用后关闭 channel 并释放资源"
  [port secret-key max-frame-size]
  (let [channel-promise (promise)]
    {:future (future
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
                                                                                       [(codec/->protocol-decoder secret-key max-frame-size)
                                                                                        (->handler)]))))))
                             (.option ChannelOption/SO_BACKLOG (int 1024))
                             (.bind port)
                             (.sync))]
                     (deliver channel-promise (.channel f))
                     (-> f (.channel) (.closeFuture) (.sync)))
                   (finally
                     (.shutdownGracefully boss-group)
                     (.shutdownGracefully worker-group)))))
     :stop! (fn []
              (let [ch (deref channel-promise 10000 nil)]
                (when ch
                  (.close ch))))}))

;; Server 类（保留向后兼容，建议新代码使用 start-server）
(defrecord Server [port secret-key max-frame-size]
  RunnableServer
  (run [this]
    (let [ctrl (start-server port secret-key max-frame-size)]
      @(:future ctrl))))
