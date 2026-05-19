(ns lan-clip.socket.server
  (:require
   [clojure.java.io :as jio]
   [lan-clip.socket.protocol-codec :as codec]
   [lan-clip.fingerprint :as fingerprint]
   [lan-clip.util :as util])
  (:import
   (io.netty.bootstrap ServerBootstrap)
   (io.netty.channel ChannelFuture ChannelHandler ChannelInboundHandlerAdapter ChannelInitializer ChannelOption)
   (io.netty.channel.nio NioEventLoopGroup)
   (io.netty.channel.socket SocketChannel)
   (io.netty.channel.socket.nio NioServerSocketChannel)
   (io.netty.util ReferenceCountUtil)
   (java.awt Image Toolkit)
   (java.awt.datatransfer DataFlavor StringSelection)
   (java.util List)
   (org.apache.commons.io FileUtils)))

(defprotocol RunnableServer
  (run [this]))

;; 处理接收到的不同类型的消息（按 protocol Message 的 :content-type 分发）
(defmulti handle-msg :content-type)

(defmethod handle-msg :text [msg]
  "处理文本消息，将 payload 解码为字符串并设置到剪贴板，返回 ClipboardData 指纹。"
  (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))
        text (String. ^bytes (:payload msg) "UTF-8")]
    (.setContents clip (StringSelection. text) nil)
    (fingerprint/fingerprint DataFlavor/stringFlavor text)))

(defmethod handle-msg :image [msg]
  "处理图片消息，将 payload PNG 字节解码为 BufferedImage 并设置到剪贴板，返回 ClipboardData 指纹。"
  (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))
        img (util/bytes->image (:payload msg))]
    (.setContents clip (util/->ImageTransferable img) nil)
    (fingerprint/fingerprint DataFlavor/imageFlavor img)))

(defmethod handle-msg :file-list [msg]
  "处理文件列表消息，将 payload zip 字节解压到临时目录并写入剪贴板，返回 ClipboardData 指纹。"
  (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))
        temp-dir (jio/file (System/getProperty "java.io.tmpdir") (str "lan-clip-" (System/currentTimeMillis)))
        files (util/zip-bytes->files (:payload msg) temp-dir)]
    (.setContents clip (util/->FileListTransferable files) nil)
    (fingerprint/fingerprint DataFlavor/javaFileListFlavor files)))

(defmethod handle-msg :default [msg]
  (println "Unknown content-type:" (:content-type msg))
  nil)

(defn- ->handler [on-apply]
  "创建一个 ChannelInboundHandlerAdapter 实例，用于处理接收到的 Message。
  可选的 on-apply 回调会在消息成功处理后以 ClipboardData 指纹为参数被调用。"
  (proxy [ChannelInboundHandlerAdapter]
         []
    (channelRead [ctx msg]
      (try
        (tap> msg)
        (when-let [fp (handle-msg msg)]
          (when on-apply
            (on-apply fp)))
        (finally
          (ReferenceCountUtil/release msg)
          (.close ctx))))
    (exceptionCaught [ctx cause]
      (.printStackTrace cause)
      (.close ctx))))

(defn start-server
  "启动 Netty server，返回控制对象：
    :future — 后台 future，server 在此运行
    :stop!  — 无参函数，调用后关闭 channel 并释放资源
  可选第 4 个参数 on-apply 为回调函数，签名 (fn [ClipboardData])，在消息成功写入剪贴板后调用。"
  [port secret-key max-frame-size & [on-apply]]
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
                                                                                        (->handler on-apply)]))))))
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
