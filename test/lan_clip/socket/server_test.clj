(ns lan-clip.socket.server-test
  (:require [clojure.test :refer :all]
            [lan-clip.socket.server :as server]
            [lan-clip.socket.protocol-codec :as codec]
            [lan-clip.util :as util])
  (:import (java.net ServerSocket Socket)
           (java.awt Toolkit)
           (java.awt.image BufferedImage)
           (java.io File)
           (java.util Collections UUID)
           (java.awt.datatransfer DataFlavor)
           (io.netty.channel.embedded EmbeddedChannel)
           (io.netty.channel ChannelHandler)
           (lan_clip.fingerprint ClipboardData)))

(defn- random-port
  "返回一个当前未被占用的随机端口。"
  []
  (with-open [ss (ServerSocket. 0)]
    (.getLocalPort ss)))

(deftest server-can-start-and-stop
  (testing "server 应能启动并在 stop 后释放端口"
    (let [port (random-port)
          ctrl (server/start-server port "test-secret" 10485760)]
      (try
        (Thread/sleep 200)
        (is (some? (:future ctrl)) "应有 future")
        (is (not (future-done? (:future ctrl))) "server 应在运行中")
        (with-open [socket (Socket. "localhost" port)]
          (is (.isConnected socket)) "端口应可连接")
        (finally
          ((:stop! ctrl))
          (Thread/sleep 200)
          (is (future-done? (:future ctrl)) "stop 后 future 应完成"))))))

(deftest handle-msg-text-returns-fingerprint
  (testing "handle-msg :text 应返回 ClipboardData 指纹"
    (let [result (server/handle-msg {:content-type :text :payload (.getBytes "hello" "UTF-8")})]
      (is (instance? ClipboardData result))
      (is (= DataFlavor/stringFlavor (:flavor result)))
      (is (= 5 (:length result)))
      (is (string? (:contents result)))
      (is (= 32 (count (:contents result)))))))

(deftest handle-msg-image-returns-fingerprint
  (testing "handle-msg :image 应返回 ClipboardData 指纹"
    (let [img (BufferedImage. 2 2 BufferedImage/TYPE_INT_ARGB)
          png-bytes (util/image->bytes img)
          result (server/handle-msg {:content-type :image :payload png-bytes})]
      (is (instance? ClipboardData result))
      (is (= DataFlavor/imageFlavor (:flavor result)))
      (is (pos? (:length result)))
      (is (string? (:contents result))))))

(deftest handle-msg-file-list-returns-fingerprint
  (testing "handle-msg :file-list 应返回 ClipboardData 指纹"
    (let [temp-file (doto (File/createTempFile "test" ".txt") (.deleteOnExit))
          _ (spit temp-file "file content")
          files (Collections/singletonList temp-file)
          zip-bytes (util/files->zip-bytes files)
          result (server/handle-msg {:content-type :file-list :payload zip-bytes})]
      (is (instance? ClipboardData result))
      (is (= DataFlavor/javaFileListFlavor (:flavor result)))
      (is (= 1 (:length result)))
      (is (string? (:contents result))))))

(deftest handle-msg-text-logs-remote-apply
  (testing "handle-msg :text 应输出 remote-apply 日志"
    (let [output (with-out-str
                   (server/handle-msg {:content-type :text :payload (.getBytes "remote text" "UTF-8")}))]
      (is (re-find #"remote-apply" output) "应包含 remote-apply 日志")
      (is (re-find #"text" output) "应包含内容类型 text"))))

(deftest handle-msg-image-logs-remote-apply
  (testing "handle-msg :image 应输出 remote-apply 日志"
    (let [img (BufferedImage. 2 2 BufferedImage/TYPE_INT_ARGB)
          png-bytes (util/image->bytes img)
          output (with-out-str
                   (server/handle-msg {:content-type :image :payload png-bytes}))]
      (is (re-find #"remote-apply" output) "应包含 remote-apply 日志")
      (is (re-find #"image" output) "应包含内容类型 image"))))

(deftest handle-msg-file-list-logs-remote-apply
  (testing "handle-msg :file-list 应输出 remote-apply 日志"
    (let [temp-file (doto (File/createTempFile "test" ".txt") (.deleteOnExit))
          _ (spit temp-file "file content")
          files (Collections/singletonList temp-file)
          zip-bytes (util/files->zip-bytes files)
          output (with-out-str
                   (server/handle-msg {:content-type :file-list :payload zip-bytes}))]
      (is (re-find #"remote-apply" output) "应包含 remote-apply 日志")
      (is (re-find #"file-list" output) "应包含内容类型 file-list"))))

(deftest server-on-apply-fired-for-remote-message
  (testing "server 收到消息后应调用 on-apply 回调并传入指纹"
    (let [port (random-port)
          received-fp (atom nil)
          ctrl (server/start-server port "test-secret" 10485760
                                    #(reset! received-fp %))]
      (try
        (Thread/sleep 300)
        (let [node-id (UUID/randomUUID)
              encoder-ch (EmbeddedChannel. (into-array ChannelHandler [(codec/->protocol-encoder node-id node-id "test-secret")]))
              _ (.writeOutbound encoder-ch (into-array Object ["remote text"]))
              frame (.readOutbound encoder-ch)
              frame-bytes (byte-array (.readableBytes frame))]
          (.getBytes frame 0 frame-bytes)
          (with-open [socket (Socket. "localhost" port)
                      out (.getOutputStream socket)]
            (.write out frame-bytes)
            (.flush out))
          (Thread/sleep 500)
          (is (instance? ClipboardData @received-fp))
          (is (= DataFlavor/stringFlavor (:flavor @received-fp)))
          (.finish encoder-ch))
        (finally
          ((:stop! ctrl))
          (Thread/sleep 200))))))

(deftest handle-msg-file-list-creates-batch-dir
  (testing "handle-msg :file-list 应在 received-files-dir 下创建批次目录 yyyyMMdd-HHmmss-message-id"
    (let [received-dir (doto (File/createTempFile "received" "") (.delete))
          temp-file (doto (File/createTempFile "test" ".txt") (.deleteOnExit))
          _ (spit temp-file "batch dir test")
          files (Collections/singletonList temp-file)
          zip-bytes (util/files->zip-bytes files)
          msg-id (UUID/randomUUID)
          msg {:content-type :file-list :payload zip-bytes :message-id msg-id}
          result (server/handle-msg msg (.getAbsolutePath received-dir))]
      (try
        (is (instance? ClipboardData result))
        (is (.exists received-dir) "received-files-dir 应被创建")
        (let [batch-dirs (.listFiles received-dir)]
          (is (= 1 (count batch-dirs)) "应只有一个批次目录")
          (let [batch-name (.getName (first batch-dirs))]
            (is (re-find #"^\d{8}-\d{6}-" batch-name) "批次目录名应以 yyyyMMdd-HHmmss- 开头")
            (is (.endsWith batch-name (str msg-id)) "批次目录名应以 message-id 结尾")))
        (finally
          (when (.exists received-dir)
            (org.apache.commons.io.FileUtils/deleteDirectory received-dir)))))))

(deftest handle-msg-file-list-writes-real-files-to-clipboard
  (testing "handle-msg :file-list 应把真实本地文件列表写入系统剪贴板"
    (let [temp-file (doto (File/createTempFile "clip-test" ".txt") (.deleteOnExit))
          _ (spit temp-file "clipboard file test")
          files (Collections/singletonList temp-file)
          zip-bytes (util/files->zip-bytes files)
          clip (.getSystemClipboard (Toolkit/getDefaultToolkit))
          original-contents (.getContents clip nil)]
      (try
        (server/handle-msg {:content-type :file-list :payload zip-bytes})
        (let [received-files (.getData clip DataFlavor/javaFileListFlavor)]
          (is (= 1 (count received-files)) "剪贴板应包含一个文件")
          (is (every? #(instance? File %) received-files) "剪贴板内容应为 File 对象")
          (is (every? #(.exists %) received-files) "剪贴板中的文件应真实存在于磁盘"))
        (finally
          (when original-contents
            (.setContents clip original-contents nil)))))))

(deftest server-stop-does-not-block-when-start-fails
  (testing "当端口被占用导致启动失败时，stop! 不应阻塞 10 秒"
    (let [port (random-port)]
      (with-open [ss (ServerSocket. port)]
        (let [ctrl (server/start-server port "test-secret" 10485760)]
          (Thread/sleep 500)
          (let [start (System/currentTimeMillis)
                _ ((:stop! ctrl))
                elapsed (- (System/currentTimeMillis) start)]
            (is (< elapsed 1000) (str "stop! 应在 1 秒内返回，实际耗时 " elapsed " ms"))))))))
