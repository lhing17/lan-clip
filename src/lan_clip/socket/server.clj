(ns lan-clip.socket.server
  (:require
   [clojure.java.io :as jio]
   [lan-clip.history :as history]
   [lan-clip.log :as log]
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
(defmulti handle-msg (fn [msg & _] (:content-type msg)))

(defmethod handle-msg :text [msg & _]
  "处理文本消息，将 payload 解码为字符串并设置到剪贴板，返回 ClipboardData 指纹。"
  (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))
        text (String. ^bytes (:payload msg) "UTF-8")]
    (.setContents clip (StringSelection. text) nil)
    (println "remote-apply: text")
    (fingerprint/fingerprint DataFlavor/stringFlavor text)))

(defmethod handle-msg :image [msg & _]
  "处理图片消息，将 payload PNG 字节解码为 BufferedImage 并设置到剪贴板，返回 ClipboardData 指纹。"
  (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))
        img (util/bytes->image (:payload msg))]
    (.setContents clip (util/->ImageTransferable img) nil)
    (println "remote-apply: image")
    (fingerprint/fingerprint DataFlavor/imageFlavor img)))

(defmethod handle-msg :file-list [msg & [received-files-dir]]
  "处理文件列表消息，将 payload zip 字节解压到批次目录并写入剪贴板，返回 ClipboardData 指纹。
  received-files-dir 为可选参数，指定接收根目录；未提供时使用系统临时目录。"
  (let [clip (.getSystemClipboard (Toolkit/getDefaultToolkit))
        msg-id (:message-id msg)
        timestamp (.. (java.time.LocalDateTime/now)
                      (format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")))
        batch-name (if msg-id
                     (str timestamp "-" msg-id)
                     (str timestamp "-" (java.util.UUID/randomUUID)))
        base-dir (or received-files-dir (System/getProperty "java.io.tmpdir"))
        batch-dir (jio/file base-dir batch-name)
        files (util/zip-bytes->files (:payload msg) batch-dir)]
    (.setContents clip (util/->FileListTransferable files) nil)
    (println "remote-apply: file-list")
    (fingerprint/fingerprint DataFlavor/javaFileListFlavor files)))

(defmethod handle-msg :default [msg]
  (println "Unknown content-type:" (:content-type msg))
  nil)

(defn- ->handler [on-apply received-files-dir history-store]
  "创建一个 ChannelInboundHandlerAdapter 实例，用于处理接收到的 Message。
  可选的 on-apply 回调会在消息成功处理后以 ClipboardData 指纹为参数被调用。
  可选的 history-store 用于记录接收历史。
  设计决策：每个 TCP 连接只处理一条消息（单消息短连接）。channelRead 处理完消息后
  立即调用 (.close ctx) 关闭连接。这简化了状态管理，避免了长连接下的心跳、重连、
  并发消息顺序等复杂度。代价是频繁建连，但剪贴板同步频率低（秒级），可接受。"
  (proxy [ChannelInboundHandlerAdapter]
         []
    (channelRead [ctx msg]
      (try
        (tap> msg)
        (when-let [fp (handle-msg msg received-files-dir)]
          (when on-apply
            (on-apply fp))
          (when history-store
            (let [hdr (:header msg)]
              (history/record! history-store
                               {:timestamp (java.time.Instant/now)
                                :direction :receive
                                :type (:content-type msg)
                                :size (count (:payload msg))
                                :peer (or (:sender-node-id hdr)
                                          (:origin-node-id hdr)
                                          "unknown")}))))
        (finally
          (ReferenceCountUtil/release msg)
          (.close ctx))))
    (exceptionCaught [ctx cause]
      (log/log! :error (str cause))
      (.close ctx))))

(defn start-server
  "启动 Netty server，返回控制对象：
    :future — 后台 future，server 在此运行
    :stop!  — 无参函数，调用后关闭 channel 并释放资源
  可选第 4 个参数 on-apply 为回调函数，签名 (fn [ClipboardData])，在消息成功写入剪贴板后调用。
  可选第 5 个参数 received-files-dir 为文件接收根目录，用于 :file-list 批次目录创建。
  可选第 6 个参数 history-store 为历史记录存储，用于记录接收历史。"
  [port secret-key max-frame-size & [on-apply received-files-dir history-store]]
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
                                                                                        (->handler on-apply received-files-dir history-store)]))))))
                             (.option ChannelOption/SO_BACKLOG (int 1024))
                             (.bind port)
                             (.sync))]
                     (deliver channel-promise (.channel f))
                     (-> f (.channel) (.closeFuture) (.sync)))
                   (finally
                     (when-not (realized? channel-promise)
                       (deliver channel-promise nil))
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
