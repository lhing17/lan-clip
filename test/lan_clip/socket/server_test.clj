(ns lan-clip.socket.server-test
  (:require [clojure.test :refer :all]
            [lan-clip.socket.server :as server]
            [lan-clip.socket.protocol-codec :as codec]
            [lan-clip.util :as util])
  (:import (java.net ServerSocket Socket)
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
